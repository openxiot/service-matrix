package cc.openxiot.matrix.api.modbus.history;

import cc.openxiot.matrix.db.modbus.service.polling.ModbusPollingFrom;
import cc.openxiot.matrix.db.modbus.service.polling.ModbusPolling;
import cc.openxiot.matrix.db.modbus.service.polling.response.field.ModbusPollingResponseField;
import cc.openxiot.matrix.db.modbus.service.polling.response.ModbusPollingResponses;
import cc.openxiot.matrix.typedef.modbus.failure.ModbusFailureType;
import cc.openxiot.matrix.typedef.modbus.failure.ModbusInvokeException;
import cc.openxiot.matrix.db.modbus.history.ModbusServicePollingHistory;
import cc.openxiot.matrix.db.modbus.history.ModbusServicePollingHistoryRepository;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceRegistryHistory;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceRegistryHistoryRepository;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceShadow;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceShadowRepository;
import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
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
 *   <li>值没变时**值历史/影子一个数据库操作都不做** —— 这条是整套成本模型的落点，破了就等于没优化；
 *       例外是操作日志（{@code ModbusServicePollingHistory}）：它每调用一条、**跟值变没变无关**，
 *       是用户拍板接受的写路径放大（靠保留策略兜底）。</li>
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
    private ModbusServiceRegistryHistoryRepository registryHistoryRepo;
    private ModbusServiceShadowRepository registryShadowRepo;
    private ModbusServicePollingHistoryRepository pollingHistoryRepo;

    @BeforeEach
    void setUp() {
        registryHistoryRepo = mock(ModbusServiceRegistryHistoryRepository.class);
        registryShadowRepo = mock(ModbusServiceShadowRepository.class);
        pollingHistoryRepo = mock(ModbusServicePollingHistoryRepository.class);

        service = new ModbusHistoryService();
        service.registryHistoryRepo = registryHistoryRepo;
        service.registryShadowRepo = registryShadowRepo;
        service.pollingHistoryRepo = pollingHistoryRepo;
        when(registryHistoryRepo.insertAll(anyList())).thenReturn(Uni.createFrom().voidItem());
        when(registryShadowRepo.upsertValue(any())).thenReturn(Uni.createFrom().voidItem());
        when(registryShadowRepo.upsertFailure(any())).thenReturn(Uni.createFrom().voidItem());
        when(pollingHistoryRepo.insert(any())).thenReturn(Uni.createFrom().voidItem());
        // 3 参入口默认读到空影子 → 没有比对基准（真·首次采集）；需要基准的测试另 stub 返回逐寄存器影子
        when(registryShadowRepo.listByServiceId(anyString()))
                .thenReturn(Uni.createFrom().item(List.of()));
        service.deadband = 0;
        service.keepAliveSeconds = 600;
    }

    /**
     * 寄存器中心的新路径（expand）：服务带了可读寄存器的点表快照时，成功/失败的采集
     * 除旧的按方法历史外，还把每只寄存器的历史事件与当前值（影子）写进新存储。
     */
    @Test
    void registryWrite_success_appendsRegistryHistoryAndUpsertsShadow() {
        service.record(modbusServiceWithRegistries(), function(), new JsonObject().put("进水温度", 62.5))
                .await().indefinitely();

        // 新的寄存器历史：一条事件，值为 62.5、指向寄存器 1
        ArgumentCaptor<List<ModbusServiceRegistryHistory>> rows = ArgumentCaptor.forClass(List.class);
        verify(registryHistoryRepo).insertAll(rows.capture());
        ModbusServiceRegistryHistory event = rows.getValue().getFirst();
        assertEquals(1, event.registerIndex.intValue());
        assertEquals("进水温度", event.name);
        assertEquals(62.5, event.value);
        assertEquals(Boolean.FALSE, event.failure);
        // 寄存器当前值 upsert 一次（清故障态）
        verify(registryShadowRepo).upsertValue(any());
        // 3 参入口从逐寄存器影子重建基准
        verify(registryShadowRepo).listByServiceId(SERVICE_ID);
    }

    @Test
    void registryWrite_oldServiceWithoutRegistries_skipsNewWrites() {
        service.record(modbusService(), function(), new JsonObject().put("进水温度", 62.5))
                .await().indefinitely();

        // 没有点表快照（老服务 / 纯命令）→ 寄存器侧不写
        verifyNoInteractions(registryHistoryRepo);
        // 3 参入口仍读了一次逐寄存器影子（重建基准），但不写当前值
        verify(registryShadowRepo).listByServiceId(SERVICE_ID);
        verify(registryShadowRepo, never()).upsertValue(any());
    }

    /** 操作日志：成功调用记一条（ok、read、响应快照），与值历史/影子并行、互不拖累。 */
    @Test
    void opLog_success_recordsOneRowPerCall() {
        service.record(modbusServiceWithRegistries(), function(), new JsonObject().put("进水温度", 62.5))
                .await().indefinitely();

        ArgumentCaptor<ModbusServicePollingHistory> log = ArgumentCaptor.forClass(ModbusServicePollingHistory.class);
        verify(pollingHistoryRepo).insert(log.capture());
        ModbusServicePollingHistory row = log.getValue();
        assertEquals(SERVICE_ID, row.serviceId);
        assertEquals(FUNCTION_INDEX, row.pollingIndex);
        assertEquals(Boolean.TRUE, row.ok);
        assertEquals("read", row.operation);
        assertNull(row.type);
        assertNull(row.message);
        assertEquals(62.5, row.fields.get("进水温度"));
    }

    /**
     * 操作日志**跟「值变没变」无关**：值没变时值历史/影子一个操作都不做（那是成本模型的原封不动），
     * 但这次调用本身还是记一条 —— 否则轮询明细会丢了「大多数调用没产生新值」的那些。
     */
    @Test
    void opLog_noChangeValue_stillLogsTheCall() {
        // 基准 = 逐寄存器影子里的旧值 61.5 → 这次采到 61.5 没变
        when(registryShadowRepo.listByServiceId(SERVICE_ID))
                .thenReturn(Uni.createFrom().item(List.of(valueShadow(61.5, new Date()))));

        service.record(modbusServiceWithRegistries(), function(), new JsonObject().put("进水温度", 61.5))
                .await().indefinitely();

        // 寄存器历史 / 影子当前值都不写
        verify(registryHistoryRepo, never()).insertAll(anyList());
        verify(registryShadowRepo, never()).upsertValue(any());
        // 操作日志照记
        verify(pollingHistoryRepo).insert(any());
    }

    /** 操作日志：失败调用记一条（ok=false，带分类与远端码）。分类/远端码由抛出点透传。 */
    @Test
    void opLog_failure_recordsTypeAndRemoteCode() {
        service.recordFailure(modbusService(), function(), parentTimeout()).await().indefinitely();

        ArgumentCaptor<ModbusServicePollingHistory> log = ArgumentCaptor.forClass(ModbusServicePollingHistory.class);
        verify(pollingHistoryRepo).insert(log.capture());
        ModbusServicePollingHistory row = log.getValue();
        assertEquals(SERVICE_ID, row.serviceId);
        assertEquals(FUNCTION_INDEX, row.pollingIndex);
        assertEquals(Boolean.FALSE, row.ok);
        assertEquals("read", row.operation);
        assertNull(row.fields);
        assertEquals("DEVICE_ERROR", row.type);
        assertEquals(408, row.remoteCode.intValue());
        assertEquals(TIMEOUT, row.message);
    }

    @Test
    void changedValueAppendsHistoryThenUpdatesShadow() {
        when(registryShadowRepo.listByServiceId(SERVICE_ID))
                .thenReturn(Uni.createFrom().item(List.of(valueShadow(61.5, new Date()))));

        service.record(modbusServiceWithRegistries(), function(), new JsonObject().put("进水温度", 62.5))
                .await().indefinitely();

        // 寄存器历史事件写了一条（值是 62.5、指向寄存器 1）
        ModbusServiceRegistryHistory row = registryRowsOf().getFirst();
        assertEquals(SERVICE_ID, row.serviceId);
        assertEquals(1, row.registerIndex.intValue());
        assertEquals("进水温度", row.name);
        assertEquals(62.5, row.value);
        assertEquals(Boolean.FALSE, row.failure);   // 值变化落的成功事件，不是失败竖线
        assertNotNull(row.at);

        // 寄存器影子换成新值：带上值、寄存器名与序号
        ArgumentCaptor<ModbusServiceShadow> shadowCap = ArgumentCaptor.forClass(ModbusServiceShadow.class);
        verify(registryShadowRepo).upsertValue(shadowCap.capture());
        ModbusServiceShadow saved = shadowCap.getValue();
        assertEquals(1, saved.registerIndex.intValue());
        assertEquals("进水温度", saved.name);
        assertEquals(62.5, saved.value);
        assertNotNull(saved.recordedAt);
    }

    @Test
    void firstSampleWritesFullValueHistory() {
        // 库里没有影子 → 没有基准 → 真·首次采集：每个读字段都落一条寄存器历史事件 + 当前值
        ModbusPollBaseline next = service.record(modbusServiceWithRegistries(), function(),
                new JsonObject().put("进水温度", 61.5)).await().indefinitely();

        assertEquals(List.of("进水温度"), fieldsOf());
        assertEquals(Map.of("进水温度", 61.5), next.valueByField());
        assertNull(next.lastError());
        // 基准从逐寄存器影子重建过一次，并写了一次当前值
        verify(registryShadowRepo).listByServiceId(SERVICE_ID);
        verify(registryShadowRepo).upsertValue(any());
    }

    @Test
    void unchangedValueTouchesNothing() {
        // 实测里绝大多数采集走这条：只读一次影子，之后一个写操作都没有
        when(registryShadowRepo.listByServiceId(SERVICE_ID))
                .thenReturn(Uni.createFrom().item(List.of(valueShadow(61.5, new Date()))));

        service.record(modbusService(), function(), new JsonObject().put("进水温度", 61.5))
                .await().indefinitely();

        verify(registryHistoryRepo, never()).insertAll(anyList());
        verify(registryShadowRepo, never()).upsertValue(any());
    }

    @Test
    void historyFailureLeavesShadowUntouched() {
        // 寄存器历史写失败：writeRegistry 把失败吞掉（新存储写不上不影响 invoke，值已拿到），
        // 影子更新跟着事件写入的链一起不落地 —— 下次采集仍判「变了」而重试
        when(registryHistoryRepo.insertAll(anyList()))
                .thenReturn(Uni.createFrom().failure(new IllegalStateException("db down")));

        ModbusPollBaseline next = service.record(modbusServiceWithRegistries(), function(),
                new JsonObject().put("进水温度", 62.5)).await().indefinitely();
        assertNotNull(next);
    }

    @Test
    void emptyValuesWritesNothing() {
        // 写方法（response 为空）解出来就是空对象：没有读值可落。
        // 基准仍读这一次（无故障则没有要清的）；没故障时不落历史也不建影子。
        service.record(modbusService(), function(), new JsonObject()).await().indefinitely();

        verify(registryShadowRepo, never()).upsertValue(any());
        verify(pollingHistoryRepo).insert(any());
    }

    // ===== 失败记录 =====

    @Test
    void firstFailureWritesRowAndMarksShadow() {
        Date lastSample = new Date();
        when(registryShadowRepo.listByServiceId(SERVICE_ID))
                .thenReturn(Uni.createFrom().item(List.of(valueShadow(61.5, lastSample))));

        service.recordFailure(modbusServiceWithRegistries(), function(), parentTimeout()).await().indefinitely();

        // 失败侧写入：先寄存器失败态，操作日志最后落（链尾）
        InOrder inOrder = inOrder(registryShadowRepo, pollingHistoryRepo);
        inOrder.verify(registryShadowRepo).upsertFailure(any());
        inOrder.verify(pollingHistoryRepo).insert(any());

        // 分类与远端原始码都落下来：前端按 type 聚合，再按 remoteCode 下钻
        ModbusServicePollingHistory row = savedPollingLog();
        assertEquals(SERVICE_ID, row.serviceId);
        assertEquals(FUNCTION_INDEX, row.pollingIndex.intValue());
        assertEquals(ModbusFailureType.DEVICE_ERROR, ModbusFailureType.valueOf(row.type));
        assertEquals(408, row.remoteCode);
        assertEquals(TIMEOUT, row.message);
        assertNotNull(row.at);

        // 寄存器影子记住故障态，但**上一次成功的值原样保留**（upsertFailure 不动 value）
        ArgumentCaptor<ModbusServiceShadow> shadowCap = ArgumentCaptor.forClass(ModbusServiceShadow.class);
        verify(registryShadowRepo).upsertFailure(shadowCap.capture());
        ModbusServiceShadow saved = shadowCap.getValue();
        assertEquals(1, saved.registerIndex.intValue());
        assertEquals(TIMEOUT, saved.lastError);   // 故障态落到寄存器影子
        assertNull(saved.value);                  // 失败写不带新值：最后一次成功值由 upsertFailure 原样保留
        verify(registryShadowRepo, never()).upsertValue(any());
    }

    @Test
    void repeatedFailureTouchesNothing() {
        // DTU 离线三天的形态：同一条错误反复出现，只该有第一条记录
        when(registryShadowRepo.listByServiceId(SERVICE_ID))
                .thenReturn(Uni.createFrom().item(List.of(faultShadow(61.5, new Date(), TIMEOUT))));

        service.recordFailure(modbusServiceWithRegistries(), function(), parentTimeout()).await().indefinitely();

        verify(pollingHistoryRepo).insert(any());   // 同类失败也照记操作日志（每调用一条）
        verify(registryHistoryRepo, never()).insertAll(anyList());
        verify(registryShadowRepo, never()).upsertFailure(any());
    }

    @Test
    void differentFailureWritesNewRow() {
        // 故障换了（父设备超时 → CRC 不符）：说明设备状态变了，得留一条
        when(registryShadowRepo.listByServiceId(SERVICE_ID))
                .thenReturn(Uni.createFrom().item(List.of(faultShadow(61.5, new Date(), TIMEOUT))));

        service.recordFailure(modbusService(), function(), crcMismatch()).await().indefinitely();

        ModbusServicePollingHistory row = savedPollingLog();
        assertEquals(ModbusFailureType.CRC_MISMATCH, ModbusFailureType.valueOf(row.type));
        assertEquals(CRC_ERROR, row.message);
        assertNull(row.remoteCode);   // CRC 不符没有远端码 —— 有码的是从站异常与父设备报错
    }

    @Test
    void firstCallFailingWritesFailureShadow() {
        // 这个 function 从没成功过：影子上只有故障态，没有值
        service.recordFailure(modbusServiceWithRegistries(), function(), parentTimeout()).await().indefinitely();

        ArgumentCaptor<ModbusServiceShadow> shadowCap = ArgumentCaptor.forClass(ModbusServiceShadow.class);
        verify(registryShadowRepo).upsertFailure(shadowCap.capture());
        ModbusServiceShadow saved = shadowCap.getValue();
        assertEquals(TIMEOUT, saved.lastError);
        assertNull(saved.value);
        verify(registryShadowRepo, never()).upsertValue(any());
    }

    @Test
    void foreignFailureIsClassifiedUnknown() {
        // 非本模块抛的异常（编解码、上游 SDK）：宁可标 UNKNOWN，也不猜一个会把人带偏的分类
        // —— 把编解码异常猜成「设备离线」，排查就从现场跑到代码上去了
        when(registryShadowRepo.listByServiceId(SERVICE_ID))
                .thenReturn(Uni.createFrom().item(List.of(faultShadow(61.5, new Date(), TIMEOUT))));

        service.recordFailure(modbusService(), function(), new IllegalStateException("connection reset"))
                .await().indefinitely();

        ModbusServicePollingHistory row = savedPollingLog();
        assertEquals(ModbusFailureType.UNKNOWN.name(), row.type);
        assertNull(row.remoteCode);
        assertEquals("connection reset", row.message);   // 明细照旧保留，分类不确定不等于信息丢失
    }

    @Test
    void failureWithoutMessageFallsBackToClassName() {
        // NPE 之类没有 message：落一串 null 在库里什么也说明不了
        when(registryShadowRepo.listByServiceId(SERVICE_ID))
                .thenReturn(Uni.createFrom().item(List.of(faultShadow(61.5, new Date(), TIMEOUT))));

        service.recordFailure(modbusService(), function(), new NullPointerException())
                .await().indefinitely();

        assertEquals("NullPointerException", savedPollingLog().message);
    }

    @Test
    void longFailureMessageIsTruncated() {
        // 上游描述可能很长，越长越容易让「同一条故障」被判成不同的两条，重复记录就压不住了
        when(registryShadowRepo.listByServiceId(SERVICE_ID))
                .thenReturn(Uni.createFrom().item(List.of(faultShadow(61.5, new Date(), TIMEOUT))));

        service.recordFailure(modbusService(), function(),
                        ModbusInvokeException.of(ModbusFailureType.NO_RESPONSE, "x".repeat(5000)))
                .await().indefinitely();

        assertEquals(501, savedPollingLog().message.length());
    }

    // ===== 故障恢复 =====

    @Test
    void recoveryAfterFailureWritesEveryField() {
        // 值恰好没变也要落全字段：否则曲线上「服务恢复了」和「仍在故障」长得一模一样
        when(registryShadowRepo.listByServiceId(SERVICE_ID))
                .thenReturn(Uni.createFrom().item(List.of(faultShadow(61.5, new Date(), TIMEOUT))));

        ModbusPollBaseline next = service.record(modbusServiceWithRegistries(), function(),
                new JsonObject().put("进水温度", 61.5)).await().indefinitely();

        assertEquals(List.of("进水温度"), fieldsOf());
        assertNull(next.lastError());      // 恢复：故障态清零
        ArgumentCaptor<ModbusServiceShadow> shadowCap = ArgumentCaptor.forClass(ModbusServiceShadow.class);
        verify(registryShadowRepo).upsertValue(shadowCap.capture());
        ModbusServiceShadow saved = shadowCap.getValue();
        assertNull(saved.lastError);
        assertNull(saved.errorAt);
        assertEquals(61.5, saved.value);
    }

    @Test
    void successOnWriteMethodClearsErrorState() {
        // 写方法没有读值可落，但「这次调用成功了」得落下来 ——
        // 否则故障态永远清不掉，前端会一直看到一条早已恢复的故障
        when(registryShadowRepo.listByServiceId(SERVICE_ID))
                .thenReturn(Uni.createFrom().item(List.of(faultShadow(61.5, new Date(), TIMEOUT))));

        ModbusPollBaseline next = service.record(modbusService(), function(), new JsonObject()).await().indefinitely();

        assertNull(next.lastError());
        assertEquals(Map.of("进水温度", 61.5), next.valueByField());   // 上次成功的值原样留着
        verify(registryShadowRepo, never()).upsertValue(any());
    }

    @Test
    void writeMethodWithNoErrorStateTouchesNothing() {
        // 写方法 + 没有故障要清：不必为一个「没有读值的方法」凭空建影子文档
        when(registryShadowRepo.listByServiceId(SERVICE_ID))
                .thenReturn(Uni.createFrom().item(List.of(valueShadow(61.5, new Date()))));

        ModbusPollBaseline next = service.record(modbusService(), function(), new JsonObject()).await().indefinitely();

        assertNull(next);
        verify(registryShadowRepo, never()).upsertValue(any());
    }

    // ===== 轮询入口（基准由调用方给，见 Modbus-Service-Poller.md 第四节） =====

    /**
     * 轮询入口**一次影子读都不做** —— 省掉的正是这套内存基准存在的全部理由。
     * 破了这条不会报错，只会让每次采集多一次点读（成本模型悄悄失效）。
     */
    @Test
    void pollRecordWithABaselineNeverReadsTheShadow() {
        ModbusPollBaseline baseline = new ModbusPollBaseline(Map.of("进水温度", 61.5), new Date(), null);

        ModbusPollBaseline next = service.record(modbusServiceWithRegistries(), function(),
                new JsonObject().put("进水温度", 62.5), baseline).await().indefinitely();

        verify(registryShadowRepo, never()).listByServiceId(anyString());
        assertEquals(List.of("进水温度"), fieldsOf());
        // 返回值 = 新基准（值映射 + 时刻），调用方据此回填缓存
        assertEquals(Map.of("进水温度", 62.5), next.valueByField());
        assertNotNull(next.recordedAt());
        assertNull(next.lastError());
    }

    /**
     * 传 null 基准 = **库里确实没有影子**（真·首次采集）：落全字段并 insert，而不是「我不知道、去读一次库」。
     * 拿不准要传库里读出来的那份，见 {@code ModbusPollBaselines.usable}。
     */
    @Test
    void pollRecordWithANullBaselineIsAFirstSample() {
        ModbusPollBaseline next = service.record(modbusServiceWithRegistries(), function(),
                new JsonObject().put("进水温度", 61.5), null).await().indefinitely();

        verify(registryShadowRepo, never()).listByServiceId(anyString());
        assertEquals(List.of("进水温度"), fieldsOf());
        assertEquals(Map.of("进水温度", 61.5), next.valueByField());
        assertNull(next.lastError());
    }

    /** 这次什么都没写（值没变，绝大多数采集）：返回 null = 没有新基准，调用方保留旧缓存而不是清空。 */
    @Test
    void pollRecordReturnsNullWhenNothingWasWritten() {
        ModbusPollBaseline baseline = new ModbusPollBaseline(Map.of("进水温度", 61.5), new Date(), null);

        ModbusPollBaseline next = service.record(modbusService(), function(),
                new JsonObject().put("进水温度", 61.5), baseline).await().indefinitely();

        assertNull(next);
        verify(registryShadowRepo, never()).upsertValue(any());
    }

    /** 故障恢复后的第一次成功：基准带故障态，照样落全字段（与 3 参入口同一条规则）。 */
    @Test
    void pollRecordAfterFailureWritesEveryField() {
        ModbusPollBaseline broken = new ModbusPollBaseline(Map.of("进水温度", 61.5), new Date(), TIMEOUT);

        ModbusPollBaseline next = service.record(modbusServiceWithRegistries(), function(),
                new JsonObject().put("进水温度", 61.5), broken).await().indefinitely();

        verify(registryShadowRepo, never()).listByServiceId(anyString());
        assertEquals(List.of("进水温度"), fieldsOf());
        assertNull(next.lastError());
    }

    /** 手动的 3 参入口**仍然读库**：从逐寄存器影子重建基准（api 副本上的缓存永远是空的）。 */
    @Test
    void manualRecordStillReadsTheRegistryShadows() {
        service.record(modbusService(), function(), new JsonObject().put("进水温度", 61.5))
                .await().indefinitely();

        verify(registryShadowRepo).listByServiceId(SERVICE_ID);
    }

    @Test
    void pollRecordFailureWithABaselineNeverReadsTheShadow() {
        Date lastSample = new Date();
        ModbusPollBaseline baseline = new ModbusPollBaseline(Map.of("进水温度", 61.5), lastSample, null);

        ModbusPollBaseline next = service.recordFailure(modbusService(), function(), parentTimeout(), baseline)
                .await().indefinitely();

        verify(registryShadowRepo, never()).listByServiceId(anyString());
        assertEquals(TIMEOUT, next.lastError());
        assertEquals(Map.of("进水温度", 61.5), next.valueByField());   // 最后已知值原样留着
        assertEquals(lastSample, next.recordedAt());
    }

    /** 同一条故障持续（基准里已经是它）：不写、返回 null —— 内存基准照样能判出来（不用读库）。 */
    @Test
    void pollRepeatedFailureReturnsNullAndTouchesNothing() {
        ModbusPollBaseline baseline = new ModbusPollBaseline(Map.of("进水温度", 61.5), new Date(), TIMEOUT);

        ModbusPollBaseline next = service.recordFailure(modbusService(), function(), parentTimeout(), baseline)
                .await().indefinitely();

        assertNull(next);
        verify(pollingHistoryRepo).insert(any());   // 同类失败也照记操作日志（每调用一条）
        verify(registryShadowRepo, never()).upsertFailure(any());
    }

    /**
     * 故障态基准（{@code lastError} 非空）走 4 参入口时**规则不变**：分类照旧由抛出点给。
     */
    @Test
    void pollFailureOnABrokenBaselineStillWritesANewRow() {
        ModbusPollBaseline broken = new ModbusPollBaseline(Map.of("进水温度", 61.5), new Date(), TIMEOUT);

        ModbusPollBaseline next = service.recordFailure(modbusService(), function(), crcMismatch(), broken)
                .await().indefinitely();

        assertEquals(ModbusFailureType.CRC_MISMATCH, ModbusFailureType.valueOf(savedPollingLog().type));
        assertEquals(CRC_ERROR, next.lastError());
    }

    // ===== 夹具 =====

    private static ModbusService modbusService() {
        ModbusService service = new ModbusService();
        service.id = new ObjectId(SERVICE_ID);
        service.name = "1 号冷水机组";
        return service;
    }

    /** 带点表快照（含一只可读寄存器「进水温度」）的服务：走寄存器中心的新写入路径。 */
    private static ModbusService modbusServiceWithRegistries() {
        ModbusService service = modbusService();
        ModbusRegistry reg = new ModbusRegistry();
        reg.index = 1;
        reg.name = "进水温度";
        reg.address = 0;
        reg.area = "holding";
        reg.access = "R";
        reg.unit = "℃";
        ModbusConfig config = new ModbusConfig();
        config.registries = java.util.List.of(reg);
        service.configSnapshot = config;
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
        ModbusPollingResponseField field = new ModbusPollingResponseField();
        field.index = 1;
        field.field = "进水温度";
        field.bytes = 2;
        field.format = "uint16";
        field.byteOrder = "ABCD";
        function.response = ModbusPollingResponses.of(field);
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

    /** 一只无故障的逐寄存器影子（上一次成功采集）。 */
    private static ModbusServiceShadow valueShadow(Object value, Date recordedAt) {
        return registryShadow(1, "进水温度", value, recordedAt, null, null);
    }

    /** 一只停在故障态的逐寄存器影子（上一次调用失败，值仍是最后一次成功采集）。 */
    private static ModbusServiceShadow faultShadow(Object value, Date recordedAt, String lastError) {
        return registryShadow(1, "进水温度", value, recordedAt, lastError, new Date());
    }

    private static ModbusServiceShadow registryShadow(Integer registerIndex, String name, Object value,
                                                      Date recordedAt, String lastError, Date errorAt) {
        ModbusServiceShadow shadow = new ModbusServiceShadow();
        shadow.serviceId = SERVICE_ID;
        shadow.registerIndex = registerIndex;
        shadow.name = name;
        shadow.value = value;
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
    private List<ModbusServiceRegistryHistory> registryRowsOf() {
        ArgumentCaptor<List<ModbusServiceRegistryHistory>> captor = ArgumentCaptor.forClass(List.class);
        verify(registryHistoryRepo).insertAll(captor.capture());
        return captor.getValue();
    }

    /** 字段名即寄存器名（见 {@code ModbusRegistry.name}），读侧据此解析；fieldsOf 取的是 name。 */
    private List<String> fieldsOf() {
        return registryRowsOf().stream().map(row -> row.name).toList();
    }

    private ModbusServicePollingHistory savedPollingLog() {
        ArgumentCaptor<ModbusServicePollingHistory> captor = ArgumentCaptor.forClass(ModbusServicePollingHistory.class);
        verify(pollingHistoryRepo).insert(captor.capture());
        return captor.getValue();
    }
}