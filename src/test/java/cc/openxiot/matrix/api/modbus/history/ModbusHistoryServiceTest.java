package cc.openxiot.matrix.api.modbus.history;

import cc.openxiot.matrix.db.modbus.service.polling.ModbusPollingFrom;
import cc.openxiot.matrix.db.modbus.service.polling.ModbusPolling;
import cc.openxiot.matrix.typedef.modbus.failure.ModbusFailureType;
import cc.openxiot.matrix.typedef.modbus.failure.ModbusInvokeException;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceFailure;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceFailureRepository;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceHistory;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceHistoryRepository;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceShadow;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceShadowRepository;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.polling.request.ModbusPollingRequests;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ModbusHistoryService} 的写入行为：不连数据库，用 mock 钉住几条不能破的约定
 * （Modbus-History.md 第二节、第四节）。
 *
 * <ul>
 *   <li>值没变时**一个数据库操作都不做** —— 这条是整套成本模型的落点，破了就等于没优化。</li>
 *   <li>**先写明细（历史 / 失败记录）、再更新影子** —— 反过来会让失败的那次变化永久丢失。</li>
 *   <li>明细写失败时**影子必须停在旧态** —— 下次采集才能把同一次变化重判并重试。</li>
 *   <li>同一条故障持续存在时**不落重复记录** —— DTU 离线三天不是三万条记录。</li>
 *   <li>故障恢复后**第一次成功必须落全字段** —— 否则曲线上分不清「已恢复」和「仍在故障」。</li>
 *   <li>失败**只做分类透传**，异常上没带分类的一律 <code>UNKNOWN</code> —— 分类定在抛出点，
 *       这里猜一个只会把排查方向带偏。</li>
 * </ul>
 */
class ModbusHistoryServiceTest {

    private static final String SERVICE_ID = "65f0c0ffee0123456789abcd";
    private static final int FUNCTION_INDEX = 1;
    private static final String TIMEOUT = "parent device error, status=408";
    private static final String CRC_ERROR = "modbus response crc mismatch: 0103020064B9AE";

    private ModbusHistoryService service;
    private ModbusServiceShadowRepository shadowRepo;
    private ModbusServiceHistoryRepository historyRepo;
    private ModbusServiceFailureRepository failureRepo;

    @BeforeEach
    void setUp() {
        shadowRepo = mock(ModbusServiceShadowRepository.class);
        historyRepo = mock(ModbusServiceHistoryRepository.class);
        failureRepo = mock(ModbusServiceFailureRepository.class);

        service = new ModbusHistoryService();
        service.shadowRepo = shadowRepo;
        service.historyRepo = historyRepo;
        service.failureRepo = failureRepo;
        service.deadband = 0;
        service.keepAliveSeconds = 600;
    }

    @Test
    void changedValueAppendsHistoryThenUpdatesShadow() {
        ModbusServiceShadow existing = shadow(new ObjectId(), sample("进水温度", 61.5), new Date());
        when(shadowRepo.find(SERVICE_ID, FUNCTION_INDEX)).thenReturn(Uni.createFrom().item(existing));
        when(historyRepo.insertAll(anyList())).thenReturn(Uni.createFrom().voidItem());
        when(shadowRepo.replace(any())).thenReturn(Uni.createFrom().voidItem());

        service.record(modbusService(), function(), new JsonObject().put("进水温度", 62.5))
                .await().indefinitely();

        // 顺序：先历史、后影子（反了会让失败的那次变化永久丢失）
        InOrder inOrder = inOrder(historyRepo, shadowRepo);
        inOrder.verify(historyRepo).insertAll(anyList());
        inOrder.verify(shadowRepo).replace(any());

        ModbusServiceHistory row = rowsOf().getFirst();
        assertEquals(SERVICE_ID, row.serviceId);
        assertEquals(FUNCTION_INDEX, row.functionIndex.intValue());
        assertEquals("进水温度", row.field);
        assertEquals(62.5, row.value);
        assertEquals(Boolean.FALSE, row.keepalive);   // 值变化落的正常样本，不是保活打点
        assertNotNull(row.at);

        // 影子换成新值：带上原 _id（替换文档改了 _id 会被 Mongo 拒绝），时间一起前进
        ModbusServiceShadow saved = savedShadow();
        assertEquals(existing.id, saved.id);
        assertEquals(Map.of("进水温度", 62.5), saved.fields);
        assertNotNull(saved.recordedAt);
    }

    @Test
    void firstSampleInsertsShadow() {
        when(shadowRepo.find(SERVICE_ID, FUNCTION_INDEX)).thenReturn(Uni.createFrom().nullItem());
        when(historyRepo.insertAll(anyList())).thenReturn(Uni.createFrom().voidItem());
        when(shadowRepo.insert(any())).thenReturn(Uni.createFrom().voidItem());

        service.record(modbusService(), function(), new JsonObject().put("进水温度", 61.5))
                .await().indefinitely();

        verify(shadowRepo).insert(any());
        verify(shadowRepo, never()).replace(any());
        assertEquals(List.of("进水温度"), fieldsOf());
    }

    @Test
    void unchangedValueTouchesNothing() {
        // 实测里绝大多数采集走这条：只读一次影子，之后一个写操作都没有
        when(shadowRepo.find(SERVICE_ID, FUNCTION_INDEX))
                .thenReturn(Uni.createFrom().item(shadow(new ObjectId(), sample("进水温度", 61.5), new Date())));

        service.record(modbusService(), function(), new JsonObject().put("进水温度", 61.5))
                .await().indefinitely();

        verifyNoInteractions(historyRepo);
        verify(shadowRepo, never()).insert(any());
        verify(shadowRepo, never()).replace(any());
    }

    @Test
    void historyFailureLeavesShadowUntouched() {
        // 历史写失败时影子必须停在旧值：下次采集会把同一次变化再判一遍并重试（自愈）
        when(shadowRepo.find(SERVICE_ID, FUNCTION_INDEX))
                .thenReturn(Uni.createFrom().item(shadow(new ObjectId(), sample("进水温度", 61.5), new Date())));
        when(historyRepo.insertAll(anyList()))
                .thenReturn(Uni.createFrom().failure(new IllegalStateException("db down")));

        assertThrows(IllegalStateException.class,
                () -> service.record(modbusService(), function(), new JsonObject().put("进水温度", 62.5))
                        .await().indefinitely());

        verify(shadowRepo, never()).insert(any());
        verify(shadowRepo, never()).replace(any());
    }

    @Test
    void emptyValuesWritesNothing() {
        // 写方法（response 为空）解出来就是空对象：没有读值可落。
        // 影子仍要读这一次 —— 上一次失败留下的故障态正是靠这次成功清掉的
        // （见 successOnWriteMethodClearsErrorState）；代价是写方法多一次点读，而写方法是低频的人工操作。
        when(shadowRepo.find(SERVICE_ID, FUNCTION_INDEX)).thenReturn(Uni.createFrom().nullItem());

        service.record(modbusService(), function(), new JsonObject()).await().indefinitely();

        verifyNoInteractions(historyRepo);
        verify(shadowRepo, never()).insert(any());
        verify(shadowRepo, never()).replace(any());
    }

    // ===== 失败记录 =====

    @Test
    void firstFailureWritesRowAndMarksShadow() {
        Date lastSample = new Date();
        ModbusServiceShadow existing = shadow(new ObjectId(), sample("进水温度", 61.5), lastSample);
        when(shadowRepo.find(SERVICE_ID, FUNCTION_INDEX)).thenReturn(Uni.createFrom().item(existing));
        when(failureRepo.insert(any())).thenReturn(Uni.createFrom().voidItem());
        when(shadowRepo.replace(any())).thenReturn(Uni.createFrom().voidItem());

        service.recordFailure(modbusService(), function(), parentTimeout()).await().indefinitely();

        // 顺序同历史：先失败记录、后影子（反了会让这条故障永久丢失）
        InOrder inOrder = inOrder(failureRepo, shadowRepo);
        inOrder.verify(failureRepo).insert(any());
        inOrder.verify(shadowRepo).replace(any());

        // 分类与远端原始码都落下来：前端按 type 聚合，再按 remoteCode 下钻
        ModbusServiceFailure row = savedFailure();
        assertEquals(SERVICE_ID, row.serviceId);
        assertEquals(FUNCTION_INDEX, row.functionIndex.intValue());
        assertEquals(ModbusFailureType.DEVICE_ERROR, ModbusFailureType.valueOf(row.type));
        assertEquals(408, row.remoteCode);
        assertEquals(TIMEOUT, row.message);
        assertNotNull(row.at);

        // 影子记住故障态，但**上一次成功的值与时刻原样保留** —— 前端要靠它显示最后已知值有多旧
        ModbusServiceShadow saved = savedShadow();
        assertEquals(existing.id, saved.id);
        assertEquals(Map.of("进水温度", 61.5), saved.fields);
        assertEquals(lastSample, saved.recordedAt);
        assertEquals(TIMEOUT, saved.lastError);
        assertNotNull(saved.errorAt);
    }

    @Test
    void repeatedFailureTouchesNothing() {
        // DTU 离线三天的形态：同一条错误反复出现，只该有第一条记录
        when(shadowRepo.find(SERVICE_ID, FUNCTION_INDEX)).thenReturn(Uni.createFrom().item(
                shadow(new ObjectId(), sample("进水温度", 61.5), new Date(), TIMEOUT, new Date())));

        service.recordFailure(modbusService(), function(), parentTimeout()).await().indefinitely();

        verifyNoInteractions(failureRepo);
        verify(shadowRepo, never()).insert(any());
        verify(shadowRepo, never()).replace(any());
    }

    @Test
    void differentFailureWritesNewRow() {
        // 故障换了（父设备超时 → CRC 不符）：说明设备状态变了，得留一条
        when(shadowRepo.find(SERVICE_ID, FUNCTION_INDEX)).thenReturn(Uni.createFrom().item(
                shadow(new ObjectId(), sample("进水温度", 61.5), new Date(), TIMEOUT, new Date())));
        when(failureRepo.insert(any())).thenReturn(Uni.createFrom().voidItem());
        when(shadowRepo.replace(any())).thenReturn(Uni.createFrom().voidItem());

        service.recordFailure(modbusService(), function(), crcMismatch()).await().indefinitely();

        ModbusServiceFailure row = savedFailure();
        assertEquals(ModbusFailureType.CRC_MISMATCH, ModbusFailureType.valueOf(row.type));
        assertEquals(CRC_ERROR, row.message);
        assertNull(row.remoteCode);   // CRC 不符没有远端码 —— 有码的是从站异常与父设备报错
    }

    @Test
    void firstCallFailingInsertsShadow() {
        // 这个 function 从没成功过：影子上只有故障态，没有值
        when(shadowRepo.find(SERVICE_ID, FUNCTION_INDEX)).thenReturn(Uni.createFrom().nullItem());
        when(failureRepo.insert(any())).thenReturn(Uni.createFrom().voidItem());
        when(shadowRepo.insert(any())).thenReturn(Uni.createFrom().voidItem());

        service.recordFailure(modbusService(), function(), parentTimeout()).await().indefinitely();

        ModbusServiceShadow saved = savedInsertedShadow();
        assertEquals(TIMEOUT, saved.lastError);
        assertNull(saved.fields);
        verify(shadowRepo, never()).replace(any());
    }

    @Test
    void failureRowFailureLeavesShadowUntouched() {
        // 失败记录没写进去，影子就必须停在旧态：下次同样的故障仍会被重判并重试落库（自愈）
        when(shadowRepo.find(SERVICE_ID, FUNCTION_INDEX)).thenReturn(Uni.createFrom().item(
                shadow(new ObjectId(), sample("进水温度", 61.5), new Date())));
        when(failureRepo.insert(any()))
                .thenReturn(Uni.createFrom().failure(new IllegalStateException("db down")));

        assertThrows(IllegalStateException.class, () -> service
                .recordFailure(modbusService(), function(), parentTimeout())
                .await().indefinitely());

        verify(shadowRepo, never()).replace(any());
    }

    @Test
    void foreignFailureIsClassifiedUnknown() {
        // 非本模块抛的异常（编解码、上游 SDK）：宁可标 UNKNOWN，也不猜一个会把人带偏的分类
        // —— 把编解码异常猜成「设备离线」，排查就从现场跑到代码上去了
        when(shadowRepo.find(SERVICE_ID, FUNCTION_INDEX)).thenReturn(Uni.createFrom().item(
                shadow(new ObjectId(), sample("进水温度", 61.5), new Date(), TIMEOUT, new Date())));
        when(failureRepo.insert(any())).thenReturn(Uni.createFrom().voidItem());
        when(shadowRepo.replace(any())).thenReturn(Uni.createFrom().voidItem());

        service.recordFailure(modbusService(), function(), new IllegalStateException("connection reset"))
                .await().indefinitely();

        ModbusServiceFailure row = savedFailure();
        assertEquals(ModbusFailureType.UNKNOWN.name(), row.type);
        assertNull(row.remoteCode);
        assertEquals("connection reset", row.message);   // 明细照旧保留，分类不确定不等于信息丢失
    }

    @Test
    void failureWithoutMessageFallsBackToClassName() {
        // NPE 之类没有 message：落一串 null 在库里什么也说明不了
        when(shadowRepo.find(SERVICE_ID, FUNCTION_INDEX)).thenReturn(Uni.createFrom().item(
                shadow(new ObjectId(), sample("进水温度", 61.5), new Date(), TIMEOUT, new Date())));
        when(failureRepo.insert(any())).thenReturn(Uni.createFrom().voidItem());
        when(shadowRepo.replace(any())).thenReturn(Uni.createFrom().voidItem());

        service.recordFailure(modbusService(), function(), new NullPointerException())
                .await().indefinitely();

        assertEquals("NullPointerException", savedFailure().message);
    }

    @Test
    void longFailureMessageIsTruncated() {
        // 上游描述可能很长，越长越容易让「同一条故障」被判成不同的两条，重复记录就压不住了
        when(shadowRepo.find(SERVICE_ID, FUNCTION_INDEX)).thenReturn(Uni.createFrom().item(
                shadow(new ObjectId(), sample("进水温度", 61.5), new Date(), TIMEOUT, new Date())));
        when(failureRepo.insert(any())).thenReturn(Uni.createFrom().voidItem());
        when(shadowRepo.replace(any())).thenReturn(Uni.createFrom().voidItem());

        service.recordFailure(modbusService(), function(),
                        ModbusInvokeException.of(ModbusFailureType.NO_RESPONSE, "x".repeat(5000)))
                .await().indefinitely();

        assertEquals(501, savedFailure().message.length());
    }

    // ===== 故障恢复 =====

    @Test
    void recoveryAfterFailureWritesEveryField() {
        // 值恰好没变也要落全字段：否则曲线上「服务恢复了」和「仍在故障」长得一模一样
        ModbusServiceShadow broken = shadow(new ObjectId(), sample("进水温度", 61.5), new Date(),
                TIMEOUT, new Date());
        when(shadowRepo.find(SERVICE_ID, FUNCTION_INDEX)).thenReturn(Uni.createFrom().item(broken));
        when(historyRepo.insertAll(anyList())).thenReturn(Uni.createFrom().voidItem());
        when(shadowRepo.replace(any())).thenReturn(Uni.createFrom().voidItem());

        service.record(modbusService(), function(), new JsonObject().put("进水温度", 61.5))
                .await().indefinitely();

        assertEquals(List.of("进水温度"), fieldsOf());
        ModbusServiceShadow saved = savedShadow();
        assertNull(saved.lastError);      // 恢复：故障态清零
        assertNull(saved.errorAt);
        assertEquals(Map.of("进水温度", 61.5), saved.fields);
    }

    @Test
    void successOnWriteMethodClearsErrorState() {
        // 写方法没有读值可落，但「这次调用成功了」得落下来 ——
        // 否则故障态永远清不掉，前端会一直看到一条早已恢复的故障
        ModbusServiceShadow broken = shadow(new ObjectId(), sample("进水温度", 61.5), new Date(),
                TIMEOUT, new Date());
        when(shadowRepo.find(SERVICE_ID, FUNCTION_INDEX)).thenReturn(Uni.createFrom().item(broken));
        when(shadowRepo.replace(any())).thenReturn(Uni.createFrom().voidItem());

        service.record(modbusService(), function(), new JsonObject()).await().indefinitely();

        verifyNoInteractions(historyRepo);
        ModbusServiceShadow saved = savedShadow();
        assertNull(saved.lastError);
        assertEquals(Map.of("进水温度", 61.5), saved.fields);   // 上次成功的值原样留着
    }

    @Test
    void writeMethodWithNoErrorStateTouchesNothing() {
        // 写方法 + 没有故障要清：不必为一个「没有读值的方法」凭空建影子文档
        when(shadowRepo.find(SERVICE_ID, FUNCTION_INDEX)).thenReturn(Uni.createFrom().item(
                shadow(new ObjectId(), sample("进水温度", 61.5), new Date())));

        service.record(modbusService(), function(), new JsonObject()).await().indefinitely();

        verifyNoInteractions(historyRepo);
        verify(shadowRepo, never()).insert(any());
        verify(shadowRepo, never()).replace(any());
    }

    // ===== 轮询入口（基准由调用方给，见 Modbus-Service-Poller.md 第四节） =====

    /**
     * 轮询入口**一次影子读都不做** —— 省掉的正是这套内存基准存在的全部理由。
     * 破了这条不会报错，只会让每次采集多一次点读（成本模型悄悄失效）。
     */
    @Test
    void pollRecordWithABaselineNeverReadsTheShadow() {
        ModbusServiceShadow baseline = shadow(new ObjectId(), sample("进水温度", 61.5), new Date());
        when(historyRepo.insertAll(anyList())).thenReturn(Uni.createFrom().voidItem());
        when(shadowRepo.replace(any())).thenReturn(Uni.createFrom().voidItem());

        ModbusServiceShadow next = service.record(modbusService(), function(),
                new JsonObject().put("进水温度", 62.5), baseline).await().indefinitely();

        verify(shadowRepo, never()).find(anyString(), anyInt());
        assertEquals(List.of("进水温度"), fieldsOf());
        ModbusServiceShadow saved = savedShadow();
        assertEquals(baseline.id, saved.id);                      // 替换文档必须带原 _id
        assertEquals(Map.of("进水温度", 62.5), saved.fields);
        // 返回值 = 写后的影子本身（它就是下一次的基准），调用方据此回填缓存
        assertEquals(saved, next);
    }

    /**
     * 传 null 基准 = **库里确实没有影子**（真·首次采集）：落全字段并 insert，而不是「我不知道、去读一次库」。
     * 拿不准要传库里读出来的那份，见 {@code ModbusPollBaselines.usable}。
     */
    @Test
    void pollRecordWithANullBaselineIsAFirstSample() {
        when(historyRepo.insertAll(anyList())).thenReturn(Uni.createFrom().voidItem());
        when(shadowRepo.insert(any())).thenReturn(Uni.createFrom().voidItem());

        ModbusServiceShadow next = service.record(modbusService(), function(),
                new JsonObject().put("进水温度", 61.5), null).await().indefinitely();

        verify(shadowRepo, never()).find(anyString(), anyInt());
        verify(shadowRepo).insert(any());
        verify(shadowRepo, never()).replace(any());
        assertEquals(List.of("进水温度"), fieldsOf());
        assertEquals(Map.of("进水温度", 61.5), savedInsertedShadow().fields);
        assertEquals(savedInsertedShadow(), next);
    }

    /** 这次什么都没写（值没变，绝大多数采集）：返回 null = 没有新基准，调用方保留旧缓存而不是清空。 */
    @Test
    void pollRecordReturnsNullWhenNothingWasWritten() {
        ModbusServiceShadow baseline = shadow(new ObjectId(), sample("进水温度", 61.5), new Date());

        ModbusServiceShadow next = service.record(modbusService(), function(),
                new JsonObject().put("进水温度", 61.5), baseline).await().indefinitely();

        assertNull(next);
        verifyNoInteractions(historyRepo);
        verify(shadowRepo, never()).insert(any());
        verify(shadowRepo, never()).replace(any());
    }

    /** 故障恢复后的第一次成功：基准带故障态，照样落全字段（与 3 参入口同一条规则）。 */
    @Test
    void pollRecordAfterFailureWritesEveryField() {
        ModbusServiceShadow broken = shadow(new ObjectId(), sample("进水温度", 61.5), new Date(),
                TIMEOUT, new Date());
        when(historyRepo.insertAll(anyList())).thenReturn(Uni.createFrom().voidItem());
        when(shadowRepo.replace(any())).thenReturn(Uni.createFrom().voidItem());

        ModbusServiceShadow next = service.record(modbusService(), function(),
                new JsonObject().put("进水温度", 61.5), broken).await().indefinitely();

        verify(shadowRepo, never()).find(anyString(), anyInt());
        assertEquals(List.of("进水温度"), fieldsOf());
        assertNull(next.lastError);
        assertNull(next.errorAt);
    }

    /** 手动的 3 参入口**仍然读库**：api 副本上缓存永远是空的（见 ModbusPollBaselines 的类注释）。 */
    @Test
    void manualRecordStillReadsTheShadow() {
        when(shadowRepo.find(SERVICE_ID, FUNCTION_INDEX)).thenReturn(Uni.createFrom().item(
                shadow(new ObjectId(), sample("进水温度", 61.5), new Date())));

        service.record(modbusService(), function(), new JsonObject().put("进水温度", 61.5))
                .await().indefinitely();

        verify(shadowRepo).find(SERVICE_ID, FUNCTION_INDEX);
    }

    @Test
    void pollRecordFailureWithABaselineNeverReadsTheShadow() {
        Date lastSample = new Date();
        ModbusServiceShadow baseline = shadow(new ObjectId(), sample("进水温度", 61.5), lastSample);
        when(failureRepo.insert(any())).thenReturn(Uni.createFrom().voidItem());
        when(shadowRepo.replace(any())).thenReturn(Uni.createFrom().voidItem());

        ModbusServiceShadow next = service.recordFailure(modbusService(), function(), parentTimeout(), baseline)
                .await().indefinitely();

        verify(shadowRepo, never()).find(anyString(), anyInt());
        assertEquals(TIMEOUT, next.lastError);
        assertEquals(Map.of("进水温度", 61.5), next.fields);   // 最后已知值原样留着
        assertEquals(lastSample, next.recordedAt);
        assertEquals(savedShadow(), next);
    }

    /** 同一条故障持续（基准里已经是它）：不写、返回 null —— 内存基准照样能判出来（不用读库）。 */
    @Test
    void pollRepeatedFailureReturnsNullAndTouchesNothing() {
        ModbusServiceShadow baseline = shadow(new ObjectId(), sample("进水温度", 61.5), new Date(),
                TIMEOUT, new Date());

        ModbusServiceShadow next = service.recordFailure(modbusService(), function(), parentTimeout(), baseline)
                .await().indefinitely();

        assertNull(next);
        verifyNoInteractions(failureRepo);
        verify(shadowRepo, never()).replace(any());
    }

    /**
     * 故障态基准（{@code lastError} 非空）走 4 参入口时**规则不变**：分类照旧由抛出点给。
     *
     * <p>轮询器会把故障态基准放进缓存，但 {@code ModbusPollBaselines.usable} 判它不可用 ——
     * 于是下一次采集回退到读库。这里只钉住「真传进来了也不会算出别的结果」。</p>
     */
    @Test
    void pollFailureOnABrokenBaselineStillWritesANewRow() {
        ModbusServiceShadow broken = shadow(new ObjectId(), sample("进水温度", 61.5), new Date(),
                TIMEOUT, new Date());
        when(failureRepo.insert(any())).thenReturn(Uni.createFrom().voidItem());
        when(shadowRepo.replace(any())).thenReturn(Uni.createFrom().voidItem());

        ModbusServiceShadow next = service.recordFailure(modbusService(), function(), crcMismatch(), broken)
                .await().indefinitely();

        assertEquals(ModbusFailureType.CRC_MISMATCH, ModbusFailureType.valueOf(savedFailure().type));
        assertEquals(CRC_ERROR, next.lastError);
    }

    // ===== 夹具 =====

    private static ModbusService modbusService() {
        ModbusService service = new ModbusService();
        service.id = new ObjectId(SERVICE_ID);
        service.name = "1 号冷水机组";
        return service;
    }

    private static ModbusPolling function() {
        ModbusPolling function = new ModbusPolling();
                    function.from = new ModbusPollingFrom();
                    function.from.type = "command";
                    function.from.index = FUNCTION_INDEX;
                    function.index = FUNCTION_INDEX;
        function.name = "读进水温度";
        function.request = ModbusPollingRequests.holding();
        return function;
    }

    /** 一次典型的设备侧失败：父设备报错，带状态码 —— 分类与码号都由抛出点决定，这里只管透传。 */
    private static ModbusInvokeException parentTimeout() {
        return ModbusInvokeException.remote(ModbusFailureType.DEVICE_ERROR, 408, TIMEOUT);
    }

    /** 另一类失败：设备回了，但报文不可信（没有远端码）。 */
    private static ModbusInvokeException crcMismatch() {
        return ModbusInvokeException.of(ModbusFailureType.CRC_MISMATCH, CRC_ERROR);
    }

    private static ModbusServiceShadow shadow(ObjectId id, Map<String, Object> fields, Date recordedAt) {
        return shadow(id, fields, recordedAt, null, null);
    }

    private static ModbusServiceShadow shadow(ObjectId id, Map<String, Object> fields, Date recordedAt,
                                              String lastError, Date errorAt) {
        ModbusServiceShadow shadow = new ModbusServiceShadow();
        shadow.id = id;
        shadow.serviceId = SERVICE_ID;
        shadow.functionIndex = FUNCTION_INDEX;
        shadow.fields = fields;
        shadow.recordedAt = recordedAt;
        shadow.lastError = lastError;
        shadow.errorAt = errorAt;
        return shadow;
    }

    private static Map<String, Object> sample(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private List<ModbusServiceHistory> rowsOf() {
        ArgumentCaptor<List<ModbusServiceHistory>> captor = ArgumentCaptor.forClass(List.class);
        verify(historyRepo).insertAll(captor.capture());
        return captor.getValue();
    }

    private List<String> fieldsOf() {
        return rowsOf().stream().map(row -> row.field).toList();
    }

    private ModbusServiceShadow savedShadow() {
        ArgumentCaptor<ModbusServiceShadow> captor = ArgumentCaptor.forClass(ModbusServiceShadow.class);
        verify(shadowRepo).replace(captor.capture());
        return captor.getValue();
    }

    private ModbusServiceShadow savedInsertedShadow() {
        ArgumentCaptor<ModbusServiceShadow> captor = ArgumentCaptor.forClass(ModbusServiceShadow.class);
        verify(shadowRepo).insert(captor.capture());
        return captor.getValue();
    }

    private ModbusServiceFailure savedFailure() {
        ArgumentCaptor<ModbusServiceFailure> captor = ArgumentCaptor.forClass(ModbusServiceFailure.class);
        verify(failureRepo).insert(captor.capture());
        return captor.getValue();
    }
}
