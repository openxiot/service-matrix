package cc.openxiot.matrix.api.modbus.alarm;

import cc.openxiot.matrix.db.modbus.alarm.ModbusServiceAlarm;
import cc.openxiot.matrix.db.modbus.alarm.ModbusServiceAlarmRepository;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceField;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFieldAlarm;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFieldBit;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFunction;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jboss.logging.Logger;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ModbusAlarmService} 的落库行为：不连数据库，用 mock 钉住几条不能破的约定
 * （Modbus-Alarm.md 第三、四节）。
 *
 * <ul>
 *   <li>没配告警的方法**一次数据库操作都不做** —— 与历史「绝大多数采集一个数据库操作都没有」同一条纪律。</li>
 *   <li>值仍越限、而告警已开着时**一个写操作都没有** —— 「一次越限一条」就落在这里。</li>
 *   <li>开出来的行带着**触发那一刻的定义快照**（含单位；位不带单位）。</li>
 *   <li>关闭带 {@code VALUE} / {@code DEFINITION} 两种 closeType —— UI 靠它解释一条没有恢复样本的关闭。</li>
 *   <li>判不了的出值要说一声（**每条只说一次**），否则「配了却不工作」还是没人知道。</li>
 *   <li>失败照常外传 —— 记日志、咽下去是调用方（{@code ModbusServiceService}）的决定，这里不替它做主。</li>
 * </ul>
 */
class ModbusAlarmServiceTest {

    private static final String SERVICE_ID = "65f0c0ffee0123456789abcd";
    private static final int FUNCTION_INDEX = 1;

    private ModbusAlarmService service;
    private ModbusServiceAlarmRepository alarmRepo;
    private Logger logger;

    @BeforeEach
    void setUp() {
        alarmRepo = mock(ModbusServiceAlarmRepository.class);
        logger = mock(Logger.class);

        service = new ModbusAlarmService();
        service.alarmRepo = alarmRepo;
        service.logger = logger;
    }

    // ===== 两道闸门：零库操作 / 稳态零写 =====

    @Test
    void withoutConfigurationNothingIsTouchedAtAll() {
        // 第一道闸门是纯内存判断：没配告警的方法连「有哪些开着」都不查
        assertNull(service.evaluate(modbusService(), function(field("进水温度", null)),
                sample("进水温度", 99.0)).await().indefinitely());
        assertNull(service.evaluate(modbusService(), function(field("进水温度", disabled())),
                sample("进水温度", 99.0)).await().indefinitely());
        verifyNoInteractions(alarmRepo);
    }

    @Test
    void steadyStateWritesNothing() {
        // 值仍越限、而告警已经开着：读一次「哪些开着」，然后一个写操作都没有 ——
        // 「一次越限一条」这条口径的落点，破了就是每天上千条重复告警
        when(alarmRepo.listOpenFields(SERVICE_ID, FUNCTION_INDEX))
                .thenReturn(Uni.createFrom().item(List.of("进水温度")));

        service.evaluate(modbusService(), function(field("进水温度", alarm(">", 80.0))),
                sample("进水温度", 99.0)).await().indefinitely();

        verify(alarmRepo).listOpenFields(SERVICE_ID, FUNCTION_INDEX);
        verify(alarmRepo, never()).insertOpen(any());
        verify(alarmRepo, never()).closeByKeys(anyString(), any(), any(), anyString(), any());
    }

    // ===== 开 =====

    @Test
    void opensWithTheDefinitionSnapshot() {
        when(alarmRepo.listOpenFields(SERVICE_ID, FUNCTION_INDEX)).thenReturn(Uni.createFrom().item(List.of()));
        when(alarmRepo.insertOpen(any())).thenReturn(Uni.createFrom().item(true));

        ModbusServiceField field = field("进水温度", alarm(">", 80.0));
        field.unit = "℃";
        service.evaluate(modbusService(), function(field), sample("进水温度", 85.5)).await().indefinitely();

        ModbusServiceAlarm row = insertedRow();
        assertEquals(SERVICE_ID, row.serviceId);
        assertEquals(FUNCTION_INDEX, row.functionIndex.intValue());
        assertEquals("进水温度", row.field);
        // 触发那一刻的定义快照：定义是活的、行是不可变的历史
        assertEquals(">", row.compare);
        assertEquals(80.0, row.threshold);
        assertNull(row.state);
        assertEquals("WARN", row.level);
        assertEquals("温度过高", row.text);
        assertEquals("℃", row.unit);
        // 触发那一刻的值，不是当前值
        assertEquals(85.5, row.sample);
        assertEquals(Boolean.FALSE, row.handled);
        // 开行标识：未恢复的行才有它，「这个出值当前开着吗」查的就是它
        assertEquals(ModbusServiceAlarm.openKeyOf(SERVICE_ID, FUNCTION_INDEX, "进水温度"), row.openKey);
        assertEquals(new Date().getTime() / 1000, row.at.getTime() / 1000, 60_000);
    }

    @Test
    void aBitOpensWithoutAUnit() {
        // 位没有自己的单位：父字段那个（比如 ℃）贴到 0/1 的位上只会让列表里出现「1 ℃」
        when(alarmRepo.listOpenFields(SERVICE_ID, FUNCTION_INDEX)).thenReturn(Uni.createFrom().item(List.of()));
        when(alarmRepo.insertOpen(any())).thenReturn(Uni.createFrom().item(true));

        ModbusServiceField bits = bits("进水阀位", "进水阀", alarm("=", 1.0));
        bits.unit = "℃";
        service.evaluate(modbusService(), function(bits), sample("进水阀", 1)).await().indefinitely();

        ModbusServiceAlarm row = insertedRow();
        assertEquals("进水阀", row.field);
        assertNull(row.unit);
        assertEquals(1, row.sample);
    }

    // ===== 关 =====

    @Test
    void closesByValueWhenTheValueComesBack() {
        when(alarmRepo.listOpenFields(SERVICE_ID, FUNCTION_INDEX))
                .thenReturn(Uni.createFrom().item(List.of("进水温度")));
        when(alarmRepo.closeByKeys(anyString(), any(), any(), anyString(), any()))
                .thenReturn(Uni.createFrom().item(1L));

        service.evaluate(modbusService(), function(field("进水温度", alarm(">", 80.0))),
                sample("进水温度", 20.0)).await().indefinitely();

        assertEquals(ModbusServiceAlarm.CLOSE_VALUE, closedType());
        assertEquals(List.of("进水温度"), closedFields());
        verify(alarmRepo, never()).insertOpen(any());
    }

    @Test
    void closesByDefinitionWhenTheNewDefinitionDroppedTheAlarm() {
        // 字段还在、告警被关掉（或字段被删）：采样里有这个出值、而新定义不再覆盖它 ⇒ 按定义关。
        // 不关的话这条再也判定不到、会永远挂在那里。
        // 注意这个方法的**另一个字段仍配着告警** —— 这正是这条路径能走到的形状（局部修改），
        // 而「最后一个告警也被关掉」那种整体回退走的是下面那条：闸门在这里就返回了
        when(alarmRepo.listOpenFields(SERVICE_ID, FUNCTION_INDEX))
                .thenReturn(Uni.createFrom().item(List.of("进水温度")));
        when(alarmRepo.closeByKeys(anyString(), any(), any(), anyString(), any()))
                .thenReturn(Uni.createFrom().item(1L));

        service.evaluate(modbusService(), function(
                        field("进水温度", null),
                        field("回水温度", alarm(">", 80.0))),
                sample("进水温度", 20.0).put("回水温度", 20.0)).await().indefinitely();

        assertEquals(ModbusServiceAlarm.CLOSE_DEFINITION, closedType());
        assertEquals(List.of("进水温度"), closedFields());
        verify(alarmRepo, never()).insertOpen(any());
    }

    @Test
    void switchingOffTheLastAlarmLeavesTheGateClosedOnPurpose() {
        // 边界（有意为之，不是漏掉）：一个方法上**最后一个**告警也被关掉之后，第一层闸门就把它
        // 整个跳过了 —— 连「哪些开着」都不查，于是那条未恢复的告警在这里**不会被关**。
        //
        // 不为此松开闸门：那会让「绝大多数采集一个数据库操作都没有」这条纪律失效，而这个残局的
        // 数量与「定义刚被改过」完全同阶（一次编辑最多留下几条），不该用**每次采样**的代价去兜。
        // 它归 ModbusAlarmReconciler 的定义变更对账扫掉（Modbus-Alarm.md 第五节末）。
        // 这条测试钉住的是「闸门在前、对账在后」这个分工，别让人以为是漏判。
        service.evaluate(modbusService(), function(field("进水温度", null)),
                sample("进水温度", 20.0)).await().indefinitely();

        verifyNoInteractions(alarmRepo);
    }

    @Test
    void oneSampleCanOpenAndCloseAtOnce() {
        when(alarmRepo.listOpenFields(SERVICE_ID, FUNCTION_INDEX))
                .thenReturn(Uni.createFrom().item(List.of("回水温度")));
        when(alarmRepo.insertOpen(any())).thenReturn(Uni.createFrom().item(true));
        when(alarmRepo.closeByKeys(anyString(), any(), any(), anyString(), any()))
                .thenReturn(Uni.createFrom().item(1L));

        service.evaluate(modbusService(), function(
                        field("进水温度", alarm(">", 80.0)),
                        field("回水温度", alarm("<", 5.0))),
                sample("进水温度", 85.0).put("回水温度", 9.0)).await().indefinitely();

        assertEquals("进水温度", insertedRow().field);
        assertEquals(List.of("回水温度"), closedFields());
    }

    // ===== 判不了：说一声，但只说一次 =====

    @Test
    void notComparableIsWarnedOnceNotEveryCycle() {
        // 判不了意味着这条配置当前不工作 —— 而「配了却不工作、还没有声音」正是要消灭的失效模式。
        // 但它往往是持续状态（老数据 / 固件与点表不符）：5 秒一条会刷成一万七千行/天，
        // 所以同一个出值只报一次
        when(alarmRepo.listOpenFields(SERVICE_ID, FUNCTION_INDEX)).thenReturn(Uni.createFrom().item(List.of()));
        ModbusServiceFunction function = function(field("进水温度", alarm(">", 80.0)));

        // 定义比数、运行期给的是字符串（这一帧拿不出可比的值）
        service.evaluate(modbusService(), function, sample("进水温度", "ON")).await().indefinitely();
        service.evaluate(modbusService(), function, sample("进水温度", "ON")).await().indefinitely();
        // 另一帧里干脆没有这个出值（截断的应答）：同一个出值，还是那条
        service.evaluate(modbusService(), function, sample("别的字段", 1)).await().indefinitely();

        verify(logger, times(1)).warnv(anyString(), capturedParams());
        // 判不了既不开也不关：读了一次「哪些开着」，写操作一次都没有
        verify(alarmRepo, times(3)).listOpenFields(SERVICE_ID, FUNCTION_INDEX);
        verify(alarmRepo, never()).insertOpen(any());
        verify(alarmRepo, never()).closeByKeys(anyString(), any(), any(), anyString(), any());
    }

    @Test
    void warningNamesTheFieldAndWhatWasConfigured() {
        when(alarmRepo.listOpenFields(SERVICE_ID, FUNCTION_INDEX)).thenReturn(Uni.createFrom().item(List.of()));

        service.evaluate(modbusService(), function(field("进水温度", alarm(">", 80.0))),
                sample("进水温度", "ON")).await().indefinitely();

        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(logger).warnv(anyString(), params.capture());
        Object[] values = params.getValue();
        assertEquals(SERVICE_ID, values[0]);
        assertEquals(FUNCTION_INDEX, values[1]);
        assertEquals("进水温度", values[2]);
        assertEquals("ON", values[3]);       // 当时的原始值：定义漂移还是应答不符，靠它分辨
        assertEquals(">", values[4]);
        assertEquals(80.0, values[5]);
    }

    @Test
    void rememberedSkipsAreBounded() {
        // 记满就清空重来：坏定义比上限还多时，多报几轮是应该的；不清则会一直涨
        Set<String> reported = new HashSet<>();
        assertTrue(ModbusAlarmService.firstTime(reported, "a", 2));
        assertTrue(ModbusAlarmService.firstTime(reported, "b", 2));
        // 满了：清空重来，于是 a 又算第一次
        assertTrue(ModbusAlarmService.firstTime(reported, "a", 2));
        assertTrue(reported.contains("a"));
    }

    // ===== 失败：外传（记日志、咽下去是调用方的决定） =====

    @Test
    void repositoryFailureIsPropagated() {
        when(alarmRepo.listOpenFields(SERVICE_ID, FUNCTION_INDEX))
                .thenReturn(Uni.createFrom().failure(new IllegalStateException("mongo is down")));

        RuntimeException error = assertThrows(RuntimeException.class, () -> service
                .evaluate(modbusService(), function(field("进水温度", alarm(">", 80.0))),
                        sample("进水温度", 99.0))
                .await().indefinitely());
        assertEquals("mongo is down", error.getMessage());
    }

    @Test
    void writeFailureIsPropagated() {
        // 插入失败必须外传：调用方要记日志，而**下一次采集会重试**（那条边沿对库幂等）
        when(alarmRepo.listOpenFields(SERVICE_ID, FUNCTION_INDEX)).thenReturn(Uni.createFrom().item(List.of()));
        when(alarmRepo.insertOpen(any()))
                .thenReturn(Uni.createFrom().failure(new IllegalStateException("write concern error")));

        assertThrows(RuntimeException.class, () -> service
                .evaluate(modbusService(), function(field("进水温度", alarm(">", 80.0))),
                        sample("进水温度", 99.0))
                .await().indefinitely());
    }

    // ===== 夹具 =====

    private Object[] capturedParams() {
        return org.mockito.ArgumentMatchers.any(Object[].class);
    }

    private ModbusServiceAlarm insertedRow() {
        ArgumentCaptor<ModbusServiceAlarm> captor = ArgumentCaptor.forClass(ModbusServiceAlarm.class);
        verify(alarmRepo).insertOpen(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private Collection<String> closedFields() {
        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(alarmRepo).closeByKeys(anyString(), any(), captor.capture(), anyString(), any());
        return captor.getValue();
    }

    private String closedType() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(alarmRepo).closeByKeys(anyString(), any(), any(), captor.capture(), any());
        return captor.getValue();
    }

    private static ModbusService modbusService() {
        ModbusService service = new ModbusService();
        service.id = new ObjectId(SERVICE_ID);
        service.name = "1 号冷水机组";
        return service;
    }

    private static ModbusServiceFunction function(ModbusServiceField... fields) {
        ModbusServiceFunction function = new ModbusServiceFunction();
        function.index = FUNCTION_INDEX;
        function.name = "读进水温度";
        function.request = "010300000001840A";
        function.response = new ArrayList<>(List.of(fields));
        return function;
    }

    private static ModbusServiceField field(String name, ModbusServiceFieldAlarm alarm) {
        ModbusServiceField field = new ModbusServiceField();
        field.field = name;
        field.alarm = alarm;
        return field;
    }

    private static ModbusServiceField bits(String name, String bitName, ModbusServiceFieldAlarm alarm) {
        ModbusServiceFieldBit bit = new ModbusServiceFieldBit();
        bit.offset = 0;
        bit.field = bitName;
        bit.alarm = alarm;

        ModbusServiceField field = new ModbusServiceField();
        field.field = name;
        field.bitList = new ArrayList<>(List.of(bit));
        return field;
    }

    private static ModbusServiceFieldAlarm alarm(String compare, Double threshold) {
        ModbusServiceFieldAlarm alarm = new ModbusServiceFieldAlarm();
        alarm.enabled = true;
        alarm.compare = compare;
        alarm.threshold = threshold;
        alarm.level = "WARN";
        alarm.text = "温度过高";
        return alarm;
    }

    private static ModbusServiceFieldAlarm disabled() {
        ModbusServiceFieldAlarm alarm = alarm(">", 80.0);
        alarm.enabled = false;
        return alarm;
    }

    private static JsonObject sample(Object... pairs) {
        JsonObject values = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put((String) pairs[i], pairs[i + 1]);
        }
        return values;
    }
}
