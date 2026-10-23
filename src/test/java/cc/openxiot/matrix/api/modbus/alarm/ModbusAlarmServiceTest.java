package cc.openxiot.matrix.api.modbus.alarm;

import cc.openxiot.common.person.Person;
import cc.openxiot.matrix.db.modbus.alarm.ModbusAlarm;
import cc.openxiot.matrix.db.modbus.alarm.ModbusAlarmRepository;
import cc.openxiot.matrix.db.modbus.alarm.OpenAlarmRule;
import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistryAlarm;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.poll.ModbusPoll;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jboss.logging.Logger;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
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
 *   <li>关闭带 {@code VALUE} / {@code DEFINITION} / {@code SUPERSEDED} 三种 closeType ——
 *       UI 靠它解释一条没有恢复样本的关闭。</li>
 *   <li>判不了的出值要说一声（**每条规则只说一次**），否则「配了却不工作」还是没人知道。</li>
 *   <li>同一个出值被另一条规则接手时**先关后插** —— 顺序错了不报错，只是那个出值一条开着的都没有。</li>
 *   <li>失败照常外传 —— 记日志、咽下去是调用方（{@code ModbusServiceService}）的决定，这里不替它做主。</li>
 *   <li>用户点「处理」写的是回执（{@code handled} / {@code handledBy}），返回**读回来的那一条**；
 *       CAS 改动为 0 时**再读一次**以分辨「别人先处理了」（算成功）与「行不在了」（not found）。</li>
 * </ul>
 */
class ModbusAlarmServiceTest {

    private static final String SERVICE_ID = "65f0c0ffee0123456789abcd";
    private static final int FUNCTION_INDEX = 1;

    private ModbusAlarmService service;
    private ModbusAlarmRepository alarmRepo;
    private Logger logger;

    @BeforeEach
    void setUp() {
        alarmRepo = mock(ModbusAlarmRepository.class);
        logger = mock(Logger.class);

        service = new ModbusAlarmService();
        service.alarmRepo = alarmRepo;
        service.logger = logger;
    }

    // ===== 两道闸门：零库操作 / 稳态零写 =====

    @Test
    void withoutConfigurationNothingIsTouchedAtAll() {
        // 第一道闸门是纯内存判断：没配告警的方法连「有哪些开着」都不查
        assertNull(service.evaluate(modbusService(field("进水温度")), function(),
                sample("进水温度", 99.0)).await().indefinitely());
        assertNull(service.evaluate(modbusService(field("进水温度", disabled())), function(),
                sample("进水温度", 99.0)).await().indefinitely());
        verifyNoInteractions(alarmRepo);
    }

    @Test
    void steadyStateWritesNothing() {
        // 值仍越限、而告警已经开着、而且**还是那条规则**：读一次「哪些开着」，然后一个写操作都没有 ——
        // 「一次越限一条」这条口径的落点，破了就是每天上千条重复告警
        ModbusRegistryAlarm rule = alarm(">", 80.0);
        when(alarmRepo.mapOpenRuleByField(SERVICE_ID, FUNCTION_INDEX))
                .thenReturn(Uni.createFrom().item(open("进水温度", rule)));

        service.evaluate(modbusService(field("进水温度", rule)), function(),
                sample("进水温度", 99.0)).await().indefinitely();

        verify(alarmRepo).mapOpenRuleByField(SERVICE_ID, FUNCTION_INDEX);
        verify(alarmRepo, never()).insertOpen(any());
        verify(alarmRepo, never()).closeByKeys(anyString(), any(), any(), anyString(), any());
    }

    // ===== 开 =====

    @Test
    void opensWithTheDefinitionSnapshot() {
        ModbusRegistryAlarm rule = alarm(">", 80.0);
        when(alarmRepo.mapOpenRuleByField(SERVICE_ID, FUNCTION_INDEX)).thenReturn(Uni.createFrom().item(open()));
        when(alarmRepo.insertOpen(any())).thenReturn(Uni.createFrom().item(true));

        ModbusRegistry field = field("进水温度", "℃", rule);
        service.evaluate(modbusService(field), function(), sample("进水温度", 85.5)).await().indefinitely();

        ModbusAlarm row = insertedRow();
        assertEquals(SERVICE_ID, row.serviceId);
        assertEquals(FUNCTION_INDEX, row.pollIndex.intValue());
        assertEquals("进水温度", row.field);
        // 这条行是哪条规则开的（内部标识，不下发）：下一轮据此判「胜者还是不是它」
        assertEquals(rule.id, row.ruleId);
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
        assertEquals(ModbusAlarm.openKeyOf(SERVICE_ID, FUNCTION_INDEX, "进水温度"), row.openKey);
        assertEquals(new Date().getTime() / 1000, row.at.getTime() / 1000, 60_000);
    }

    @Test
    void aBitOpensWithoutAUnit() {
        // 位没有自己的单位：父字段那个（比如 ℃）贴到 0/1 的位上只会让列表里出现「1 ℃」
        when(alarmRepo.mapOpenRuleByField(SERVICE_ID, FUNCTION_INDEX)).thenReturn(Uni.createFrom().item(open()));
        when(alarmRepo.insertOpen(any())).thenReturn(Uni.createFrom().item(true));

        ModbusRegistry bits = bits("进水阀位", "进水阀", alarm("=", 1.0));
        service.evaluate(modbusService(bits), function(), sample("进水阀", 1)).await().indefinitely();

        ModbusAlarm row = insertedRow();
        assertEquals("进水阀", row.field);
        assertNull(row.unit);
        assertEquals(1, row.sample);
    }

    // ===== 关 =====

    @Test
    void closesByValueWhenTheValueComesBack() {
        ModbusRegistryAlarm rule = alarm(">", 80.0);
        when(alarmRepo.mapOpenRuleByField(SERVICE_ID, FUNCTION_INDEX))
                .thenReturn(Uni.createFrom().item(open("进水温度", rule)));
        when(alarmRepo.closeByKeys(anyString(), any(), any(), anyString(), any()))
                .thenReturn(Uni.createFrom().item(1L));

        service.evaluate(modbusService(field("进水温度", rule)), function(),
                sample("进水温度", 20.0)).await().indefinitely();

        assertEquals(ModbusAlarm.CLOSE_VALUE, closedType());
        assertEquals(List.of("进水温度"), closedFields());
        verify(alarmRepo, never()).insertOpen(any());
    }

    @Test
    void closesByDefinitionWhenTheNewDefinitionDroppedTheAlarm() {
        // 字段还在、告警被关掉（或字段被删）：采样里有这个出值、而新定义不再覆盖它 ⇒ 按定义关。
        // 不关的话这条再也判定不到、会永远挂在那里。
        // 注意这个方法的**另一个字段仍配着告警** —— 这正是这条路径能走到的形状（局部修改），
        // 而「最后一个告警也被关掉」那种整体回退走的是下面那条：闸门在这里就返回了
        ModbusRegistryAlarm rule = alarm(">", 80.0);
        when(alarmRepo.mapOpenRuleByField(SERVICE_ID, FUNCTION_INDEX))
                .thenReturn(Uni.createFrom().item(open("进水温度", rule)));
        when(alarmRepo.closeByKeys(anyString(), any(), any(), anyString(), any()))
                .thenReturn(Uni.createFrom().item(1L));

        service.evaluate(modbusService(
                        field("进水温度"),
                        field("回水温度", rule)), function(),
                sample("进水温度", 20.0).put("回水温度", 20.0)).await().indefinitely();

        assertEquals(ModbusAlarm.CLOSE_DEFINITION, closedType());
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
        service.evaluate(modbusService(field("进水温度")), function(),
                sample("进水温度", 20.0)).await().indefinitely();

        verifyNoInteractions(alarmRepo);
    }

    @Test
    void oneSampleCanOpenAndCloseAtOnce() {
        ModbusRegistryAlarm low = alarm("<", 5.0);
        when(alarmRepo.mapOpenRuleByField(SERVICE_ID, FUNCTION_INDEX))
                .thenReturn(Uni.createFrom().item(open("回水温度", low)));
        when(alarmRepo.insertOpen(any())).thenReturn(Uni.createFrom().item(true));
        when(alarmRepo.closeByKeys(anyString(), any(), any(), anyString(), any()))
                .thenReturn(Uni.createFrom().item(1L));

        service.evaluate(modbusService(
                        field("进水温度", alarm(">", 80.0)),
                        field("回水温度", low)), function(),
                sample("进水温度", 85.0).put("回水温度", 9.0)).await().indefinitely();

        assertEquals("进水温度", insertedRow().field);
        assertEquals(List.of("回水温度"), closedFields());
    }

    // ===== 取代：一个出值既关又开 =====

    @Test
    void theRowOpenedByAnotherRuleIsSupersededNotRecovered() {
        // 同一出值的一组规则里换了一条生效：那条开着的要按 SUPERSEDED 关 ——
        // 降级时值并没有回到正常，记成 VALUE 是谎话
        ModbusRegistryAlarm warn = level(alarm(">", 26.0), "WARN");
        ModbusRegistryAlarm critical = level(alarm(">", 30.0), "CRITICAL");
        when(alarmRepo.mapOpenRuleByField(SERVICE_ID, FUNCTION_INDEX))
                .thenReturn(Uni.createFrom().item(open("进水温度", critical)));
        when(alarmRepo.insertOpen(any())).thenReturn(Uni.createFrom().item(true));
        when(alarmRepo.closeByKeys(anyString(), any(), any(), anyString(), any()))
                .thenReturn(Uni.createFrom().item(1L));

        service.evaluate(modbusService(field("进水温度", warn, critical)), function(),
                sample("进水温度", 27.0)).await().indefinitely();

        assertEquals(ModbusAlarm.CLOSE_SUPERSEDED, closedType());
        assertEquals(List.of("进水温度"), closedFields());
        assertEquals(warn.id, insertedRow().ruleId);
    }

    @Test
    void supersededRowIsClosedBeforeTheWinnerIsInserted() {
        // **写序在这里是语义的一部分**：同一个出值既关又开时，插入若抢在关闭之前，会撞上
        // {openKey} 那条唯一部分索引、被 insertOpen 当成「别的副本先开了」静默吞掉（它本来就该吞
        // 11000）—— 于是这个出值一条开着的都没有。下一轮采样会自愈（没有开行 ⇒ 重新开那个胜者），
        // 但那个窗口里告警是消失的，而轮询一停就永久丢。而 Uni.combine().all() 是**并发**的，
        // 所以顺序只能由两段 chain 保证，不能靠 ops 列表的先后。
        // 与 ModbusServiceServiceTest 里那条 InOrder 同一性质：顺序写错了不报错，只是悄悄丢告警。
        ModbusRegistryAlarm warn = level(alarm(">", 26.0), "WARN");
        ModbusRegistryAlarm critical = level(alarm(">", 30.0), "CRITICAL");
        when(alarmRepo.mapOpenRuleByField(SERVICE_ID, FUNCTION_INDEX))
                .thenReturn(Uni.createFrom().item(open("进水温度", warn)));
        when(alarmRepo.insertOpen(any())).thenReturn(Uni.createFrom().item(true));
        when(alarmRepo.closeByKeys(anyString(), any(), any(), anyString(), any()))
                .thenReturn(Uni.createFrom().item(1L));

        service.evaluate(modbusService(field("进水温度", warn, critical)), function(),
                sample("进水温度", 31.0)).await().indefinitely();

        InOrder order = inOrder(alarmRepo);
        order.verify(alarmRepo).closeByKeys(eq(SERVICE_ID), eq(FUNCTION_INDEX), eq(List.of("进水温度")),
                eq(ModbusAlarm.CLOSE_SUPERSEDED), any());
        order.verify(alarmRepo).insertOpen(any());
    }

    @Test
    void handledRowIsClosedBeforeTheNewOneIsInserted() {
        // 与上一条同一性质、同一理由（先关再插，否则插入撞唯一索引被静默吞掉），而这里的代价
        // 更容易被当成 bug：用户刚点完「处理」，下一秒那条就整个不见了
        ModbusRegistryAlarm rule = alarm(">", 80.0);
        when(alarmRepo.mapOpenRuleByField(SERVICE_ID, FUNCTION_INDEX))
                .thenReturn(Uni.createFrom().item(openHandled("进水温度", rule)));
        when(alarmRepo.insertOpen(any())).thenReturn(Uni.createFrom().item(true));
        when(alarmRepo.closeByKeys(anyString(), any(), any(), anyString(), any()))
                .thenReturn(Uni.createFrom().item(1L));

        service.evaluate(modbusService(field("进水温度", rule)), function(),
                sample("进水温度", 99.0)).await().indefinitely();

        InOrder order = inOrder(alarmRepo);
        order.verify(alarmRepo).closeByKeys(eq(SERVICE_ID), eq(FUNCTION_INDEX), eq(List.of("进水温度")),
                eq(ModbusAlarm.CLOSE_HANDLED), any());
        order.verify(alarmRepo).insertOpen(any());

        // 新开的那条**显式**是未处理（不是留空）：要再点一次「处理」才算认领 —— 这正是这条改动的意思，
        // 也让「未处理」的两种表示（null 与 false）在库里不会混着出现
        ModbusAlarm row = insertedRow();
        assertEquals(Boolean.FALSE, row.handled);
        assertEquals(rule.id, row.ruleId);
    }

    // ===== 判不了：说一声，但只说一次 =====

    @Test
    void notComparableIsWarnedOnceNotEveryCycle() {
        // 判不了意味着这条配置当前不工作 —— 而「配了却不工作、还没有声音」正是要消灭的失效模式。
        // 但它往往是持续状态（老数据 / 固件与点表不符）：5 秒一条会刷成一万七千行/天，
        // 所以同一条规则只报一次
        when(alarmRepo.mapOpenRuleByField(SERVICE_ID, FUNCTION_INDEX)).thenReturn(Uni.createFrom().item(open()));
        ModbusService svc = modbusService(field("进水温度", alarm(">", 80.0)));
        ModbusPoll function = function();

        // 定义比数、运行期给的是字符串（这一帧拿不出可比的值）
        service.evaluate(svc, function, sample("进水温度", "ON")).await().indefinitely();
        service.evaluate(svc, function, sample("进水温度", "ON")).await().indefinitely();
        // 另一帧里干脆没有这个出值（截断的应答）：同一个出值，还是那条
        service.evaluate(svc, function, sample("别的字段", 1)).await().indefinitely();

        verify(logger, times(1)).warnv(anyString(), capturedParams());
        // 判不了既不开也不关：读了一次「哪些开着」，写操作一次都没有
        verify(alarmRepo, times(3)).mapOpenRuleByField(SERVICE_ID, FUNCTION_INDEX);
        verify(alarmRepo, never()).insertOpen(any());
        verify(alarmRepo, never()).closeByKeys(anyString(), any(), any(), anyString(), any());
    }

    @Test
    void everyBrokenRuleIsWarnedOnItsOwn() {
        // 一个出值可以配一组规则，所以「哪一条判不了」要分别报出来 ——
        // 去重键细到规则，否则同一出值的第二条坏规则永远不会被报出来
        ModbusRegistryAlarm first = alarm("??", 80.0);
        ModbusRegistryAlarm second = alarm("??", 90.0);
        when(alarmRepo.mapOpenRuleByField(SERVICE_ID, FUNCTION_INDEX)).thenReturn(Uni.createFrom().item(open()));

        service.evaluate(modbusService(field("进水温度", first, second)), function(),
                sample("进水温度", "ON")).await().indefinitely();

        verify(logger, times(2)).warnv(anyString(), capturedParams());
    }

    @Test
    void warningNamesTheFieldAndWhatWasConfigured() {
        when(alarmRepo.mapOpenRuleByField(SERVICE_ID, FUNCTION_INDEX)).thenReturn(Uni.createFrom().item(open()));

        service.evaluate(modbusService(field("进水温度", alarm(">", 80.0))), function(),
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
        when(alarmRepo.mapOpenRuleByField(SERVICE_ID, FUNCTION_INDEX))
                .thenReturn(Uni.createFrom().failure(new IllegalStateException("mongo is down")));

        RuntimeException error = assertThrows(RuntimeException.class, () -> service
                .evaluate(modbusService(field("进水温度", alarm(">", 80.0))), function(),
                        sample("进水温度", 99.0))
                .await().indefinitely());
        assertEquals("mongo is down", error.getMessage());
    }

    @Test
    void writeFailureIsPropagated() {
        // 插入失败必须外传：调用方要记日志，而**下一次采集会重试**（那条边沿对库幂等）
        when(alarmRepo.mapOpenRuleByField(SERVICE_ID, FUNCTION_INDEX)).thenReturn(Uni.createFrom().item(open()));
        when(alarmRepo.insertOpen(any()))
                .thenReturn(Uni.createFrom().failure(new IllegalStateException("write concern error")));

        assertThrows(RuntimeException.class, () -> service
                .evaluate(modbusService(field("进水温度", alarm(">", 80.0))), function(),
                        sample("进水温度", 99.0))
                .await().indefinitely());
    }

    // ===== 处理：用户回执（写侧唯一的用户动作） =====

    @Test
    void handlingWritesTheReceiptAndReturnsTheUpdatedRow() {
        ObjectId id = new ObjectId();
        Person who = who();
        ModbusAlarm updated = alarmRow(id);
        updated.handled = true;
        updated.handledBy = who;
        when(alarmRepo.handle(id, who)).thenReturn(Uni.createFrom().item(1L));
        when(alarmRepo.findById(id)).thenReturn(Uni.createFrom().item(updated));

        ModbusAlarm result = service.handle(id, who).await().indefinitely();

        // 返回的是**读回来的那一条**而不是拿入参拼的：页面就地替换这一行，字段一个都不能少
        assertSame(updated, result);
        verifyNoInteractions(logger);
    }

    @Test
    void handlingItTwiceIsStillASuccess() {
        // CAS 改动 0 有两种解释，「已经有人处理过了」是其中**成功**的那一种：
        // 用户要的结果（这条被处理了）已经成立，报错只会为一件恰好如愿的事弹一条红字
        ObjectId id = new ObjectId();
        Person who = who();
        ModbusAlarm handled = alarmRow(id);
        handled.handled = true;
        when(alarmRepo.handle(id, who)).thenReturn(Uni.createFrom().item(0L));
        when(alarmRepo.findById(id)).thenReturn(Uni.createFrom().item(handled));

        ModbusAlarm result = service.handle(id, who).await().indefinitely();

        assertSame(handled, result);
        // 但要说一声：不然「我点了怎么没反应」与「别人先处理了」在日志里也分不出来
        verify(logger).infov(anyString(), any(Object.class));
    }

    @Test
    void handlingAnAlarmThatIsGoneIsNotFound() {
        // CAS 改动 0 的另一种解释：行不在了（保存时字段已删、或服务被级联删掉）。
        // 读那一次就是为了把这两种分开 —— 「处理成功」与「这条已经没了」不是一件事
        ObjectId id = new ObjectId();
        Person who = who();
        when(alarmRepo.handle(id, who)).thenReturn(Uni.createFrom().item(0L));
        when(alarmRepo.findById(id)).thenReturn(Uni.createFrom().nullItem());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.handle(id, who).await().indefinitely());

        assertEquals("modbus alarm not found: " + id.toHexString(), error.getMessage());
    }

    @Test
    void handleRejectsMissingArguments() {
        assertEquals("alarm id is required", assertThrows(IllegalArgumentException.class,
                () -> service.handle(null, who()).await().indefinitely()).getMessage());
        assertEquals("handler is required", assertThrows(IllegalArgumentException.class,
                () -> service.handle(new ObjectId(), null).await().indefinitely()).getMessage());
        verifyNoInteractions(alarmRepo);
    }

    @Test
    void handleFailureIsPropagated() {
        ObjectId id = new ObjectId();
        Person who = who();
        when(alarmRepo.handle(id, who))
                .thenReturn(Uni.createFrom().failure(new IllegalStateException("mongo is down")));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.handle(id, who).await().indefinitely());

        assertEquals("mongo is down", error.getMessage());
    }

    @Test
    void handleReadFailureIsPropagated() {
        // CAS 已经写进去了、读回执那一步库挂了：**外传，不能当成「已经处理过了」咽下去** ——
        // 咽下去就得凭空拼一条返回给页面，而那正是「显示一行我并没有真的读到的数据」
        ObjectId id = new ObjectId();
        Person who = who();
        when(alarmRepo.handle(id, who)).thenReturn(Uni.createFrom().item(1L));
        when(alarmRepo.findById(id))
                .thenReturn(Uni.createFrom().failure(new IllegalStateException("mongo is down")));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.handle(id, who).await().indefinitely());

        assertEquals("mongo is down", error.getMessage());
    }

    // ===== 夹具 =====

    private Object[] capturedParams() {
        return org.mockito.ArgumentMatchers.any(Object[].class);
    }

    private ModbusAlarm insertedRow() {
        ArgumentCaptor<ModbusAlarm> captor = ArgumentCaptor.forClass(ModbusAlarm.class);
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

    /** 一条已恢复的告警行 —— 处理动作与「开着 / 恢复」无关，所以夹具不带 openKey。 */
    private static ModbusAlarm alarmRow(ObjectId id) {
        ModbusAlarm row = new ModbusAlarm();
        row.id = id;
        row.serviceId = SERVICE_ID;
        row.pollIndex = FUNCTION_INDEX;
        row.field = "进水温度";
        row.level = "WARN";
        row.text = "温度过高";
        row.at = new Date();
        row.handled = false;
        return row;
    }

    private static Person who() {
        Person who = new Person();
        who.id = "u-1";
        who.name = "张三";
        who.timestamp = new Date();
        return who;
    }

    /**
 * 服务夹具 — 告警现在属 configSnapshot.registries[]（寄存器中心），不再属 function 应答字段。
 * 故 {@code modbusService(...)} 不开 params 时也带空点表快照，闸门「注册表里没有启用告警」据此跳过。
 */
private static ModbusService modbusService(ModbusRegistry... registries) {
    ModbusService service = new ModbusService();
    service.id = new ObjectId(SERVICE_ID);
    service.name = "1 号冷水机组";
    ModbusConfig snapshot = new ModbusConfig();
    snapshot.registries = new ArrayList<>(List.of(registries));
    service.configSnapshot = snapshot;
    return service;
}

/** 方法只承担方法序号（告警行按读块维度）：出值规则已迁到服务点表快照。 */
private static ModbusPoll function(ModbusRegistry... ignored) {
    ModbusPoll function = new ModbusPoll();
    function.index = FUNCTION_INDEX;
    return function;
}

private static ModbusRegistry field(String name, ModbusRegistryAlarm... alarms) {
    return field(name, null, alarms);
}

private static ModbusRegistry field(String name, String unit, ModbusRegistryAlarm... alarms) {
    ModbusRegistry reg = new ModbusRegistry();
    reg.name = name;
    reg.unit = unit;
    reg.alarms = new ArrayList<>(List.of(alarms));
    return reg;
}

/** 位（coil/discrete）寄存器：位是独立的结果键，规则挂在这条位上、无单位。 */
private static ModbusRegistry bits(String name, String bitName, ModbusRegistryAlarm... alarms) {
    ModbusRegistry reg = new ModbusRegistry();
    reg.name = bitName;
    reg.alarms = new ArrayList<>(List.of(alarms));
    return reg;
}

/**
 * 一条规则。<b>标识由内容生成</b>（与 {@code ModbusAlarmPolicyTest} 的夹具同口径）：
 * 参数相同的两条规则算同一条 —— 「这条开着的行还是那条规则开的」因此不必到处手写 id。
 */
private static ModbusRegistryAlarm alarm(String compare, Double threshold) {
    ModbusRegistryAlarm alarm = new ModbusRegistryAlarm();
    alarm.id = compare + threshold;
    alarm.enabled = true;
    alarm.compare = compare;
    alarm.threshold = threshold;
    alarm.level = "WARN";
    alarm.text = "温度过高";
    return alarm;
}

/** 改级别（选举看它）。 */
private static ModbusRegistryAlarm level(ModbusRegistryAlarm alarm, String level) {
    alarm.level = level;
    return alarm;
}

private static ModbusRegistryAlarm disabled() {
    ModbusRegistryAlarm alarm = alarm(">", 80.0);
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

    /** 开着的行（**未处理**）：{@code 出值名, 那条规则} 成对给；不给就是「一条开着的都没有」。 */
    private static Map<String, OpenAlarmRule> open(Object... pairs) {
        return openWith(false, pairs);
    }

    /** 开着的行、且**已经被处理过** —— 重报那一条分支要用到这个状态。 */
    private static Map<String, OpenAlarmRule> openHandled(Object... pairs) {
        return openWith(true, pairs);
    }

    private static Map<String, OpenAlarmRule> openWith(boolean handled, Object... pairs) {
        Map<String, OpenAlarmRule> open = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            ModbusRegistryAlarm rule = (ModbusRegistryAlarm) pairs[i + 1];
            open.put((String) pairs[i], new OpenAlarmRule(rule.id, handled));
        }
        return open;
    }
}
