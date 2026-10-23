package cc.openxiot.matrix.api.modbus.alarm;

import cc.openxiot.matrix.db.modbus.alarm.ModbusAlarm;
import cc.openxiot.matrix.db.modbus.alarm.ModbusAlarmRepository;
import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistryAlarm;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceRepository;
import io.smallrye.mutiny.Uni;
import org.bson.types.ObjectId;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ModbusAlarmReconciler} 的两条路径（定义变更 / 启动扫）与它们的失败口径，全部走 mock，不连 Mongo。
 *
 * <p>判据本身（什么算陈旧）在 {@link ModbusAlarmPlanTest}，这里只管「读什么、写什么、失败怎么办」。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModbusAlarmReconcilerTest {

    private static final String SID = "65f0c0ffee0123456789abcd";
    private static final String OTHER_SID = "65f0c0ffee0123456789abce";

    /** 夹具里那条规则的身份：定义里的规则与行上的 {@code ruleId} 都用它，即「还是这条规则」。 */
    private static final String RULE_ID = "r1";

    @Mock
    ModbusServiceRepository serviceRepo;

    @Mock
    ModbusAlarmRepository alarmRepo;

    @Mock
    Logger logger;

    @InjectMocks
    ModbusAlarmReconciler reconciler;

    // ===== 定义变更：写什么 =====

    @Test
    void stillCoveredRowsWriteNothing() {
        // 稳态：字段还配着告警，一条都不该被关 —— 「改了定义」绝大多数时候什么也没改到告警上
        givenOpen(openRow(SID, 1, "进水温度"));
        reconcile(service(SID, function(field("进水温度", true))));

        verify(alarmRepo, never()).closeByKeys(anyString(), any(), any(), anyString(), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void staleRowsAreClosedAsDefinition() {
        // 定义不再覆盖它 ⇒ 按 DEFINITION 关。这一条是「改了定义后这个方法再也不采」的兜底：
        // 采集链路只看得见**新定义里还配着告警的键**，这个键连候选都进不去，只能靠对账
        givenOpen(openRow(SID, 1, "进水温度"));
        reconcile(service(SID, function(field("回水温度", true))));

        ArgumentCaptor<Collection<String>> fields = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<String> closeType = ArgumentCaptor.forClass(String.class);
        verify(alarmRepo).closeByKeys(anyString(), any(), fields.capture(), closeType.capture(), any());
        assertEquals(List.of("进水温度"), List.copyOf(fields.getValue()));
        assertEquals(ModbusAlarm.CLOSE_DEFINITION, closeType.getValue(),
                "对账关掉的原因只有一个：定义不再覆盖它（VALUE 是值回来时采集链路自己关的）");
    }

    @Test
    void closeCarriesTheServiceAndFunctionOfTheRow() {
        givenOpen(openRow(SID, 2, "进水温度"));
        reconcile(service(SID, function(2, field("回水温度", true))));

        ArgumentCaptor<String> serviceId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> transactionIndex = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Date> at = ArgumentCaptor.forClass(Date.class);
        verify(alarmRepo).closeByKeys(serviceId.capture(), transactionIndex.capture(), any(),
                anyString(), at.capture());

        assertEquals(SID, serviceId.getValue());
        assertEquals(2, transactionIndex.getValue().intValue());
        assertNotNull(at.getValue(), "关闭时刻必须写上：一条没有 recoveredAt 的关闭等于没关");
    }

    @SuppressWarnings("unchecked")
    @Test
    void oneStatementPerFunctionWithAllItsStaleFields() {
        // 关闭是按整个开行标识 updateMany 的：同一个方法上的多个出值合成一条语句，
        // 不是一条语句一个出值（后者会让一轮对账的写放大成出值数倍）
        givenOpen(openRow(SID, 1, "进水温度"), openRow(SID, 1, "回水温度"));
        reconcile(service(SID, function(field("出水温度", true))));

        ArgumentCaptor<Collection<String>> fields = ArgumentCaptor.forClass(Collection.class);
        verify(alarmRepo).closeByKeys(anyString(), any(), fields.capture(), anyString(), any());
        assertEquals(List.of("回水温度", "进水温度"), List.copyOf(fields.getValue()));
    }

    @Test
    void onlyThisServicesOpenRowsAreRead() {
        // 写入路径只该问这一个服务：全表扫是启动那一次的事
        givenOpen();
        reconcile(service(SID, function(field("进水温度", true))));

        verify(alarmRepo).listOpenByServiceId(SID);
        verify(alarmRepo, never()).listAllOpen();
    }

    // ===== 定义变更：不读不写的情形 =====

    @Test
    void serviceWithoutIdTouchesTheDatabaseNotAtAll() {
        // 定义还没落库（没有 _id）时没有「它上面的开行」可言 —— 连查都不查
        reconciler.reconcile(serviceWithoutId()).await().indefinitely();
        reconciler.reconcile(null).await().indefinitely();

        verifyNoInteractions(alarmRepo);
    }

    // ===== 失败口径 =====

    @Test
    void repositoryFailureIsPropagated() {
        // 读失败照常外传：调用方（ModbusServiceService 的 reconcileDerived）只记日志，
        // 不让一次定义维护跟着失败 —— 咽在本类里会让那条日志失去「哪个服务」的上下文
        when(alarmRepo.listOpenByServiceId(anyString()))
                .thenReturn(Uni.createFrom().failure(new IllegalStateException("alarm table is down")));

        assertThrows(IllegalStateException.class,
                () -> reconcile(service(SID, function(field("进水温度", true)))));
    }

    @Test
    void closeFailureIsPropagated() {
        givenOpen(openRow(SID, 1, "进水温度"));
        when(alarmRepo.closeByKeys(anyString(), any(), any(), anyString(), any()))
                .thenReturn(Uni.createFrom().failure(new IllegalStateException("write failed")));

        assertThrows(IllegalStateException.class,
                () -> reconcile(service(SID, function(field("回水温度", true)))));
    }

    // ===== 启动扫 =====

    @Test
    void startupSweepClosesRowsWhoseDefinitionNoLongerCoversThem() {
        // 服务还在、字段还在，但告警被直接改库改掉了（运维脚本、迁移）—— 采集链路看不见这种残留
        when(serviceRepo.listAll()).thenReturn(Uni.createFrom()
                .item(List.of(service(SID, function(field("进水温度", false))))));
        when(alarmRepo.listAllOpen()).thenReturn(Uni.createFrom().item(List.of(openRow(SID, 1, "进水温度"))));
        when(alarmRepo.closeByKeys(anyString(), any(), any(), anyString(), any()))
                .thenReturn(Uni.createFrom().item(1L));

        reconciler.init(null);

        ArgumentCaptor<String> closeType = ArgumentCaptor.forClass(String.class);
        verify(alarmRepo).closeByKeys(anyString(), any(), any(), closeType.capture(), any());
        assertEquals(ModbusAlarm.CLOSE_DEFINITION, closeType.getValue());
    }

    @Test
    void startupSweepLeavesRowsOfAServiceThatNoLongerExistsAlone() {
        // 边界（有意为之，不是漏掉）：判据对**不认识的服务**一律不动，所以「服务已被删除、
        // 级联删除又崩在中途」留下的开行，这一趟不会去关。不为此松开那条保守口径 ——
        // 它的正面价值是「写入路径只传一个服务，判宽了会把别的服务的告警误关一片」，
        // 而那比这里兜住的局面严重得多。
        // 服务级联删除归 ModbusServiceService → ModbusHistoryService.purge；那边失败留下的行
        // 与影子 / 历史 / 故障记录是同一个失败模式（只记日志、不重试），且页面按空间子树查、看不见它。
        // 这条测试钉的是「对账在后、级联在前」这个分工。
        when(serviceRepo.listAll()).thenReturn(Uni.createFrom()
                .item(List.of(service(SID, function(field("进水温度", true))))));
        when(alarmRepo.listAllOpen()).thenReturn(Uni.createFrom().item(List.of(openRow(OTHER_SID, 1, "回水温度"))));

        reconciler.init(null);

        verify(alarmRepo, never()).closeByKeys(anyString(), any(), any(), anyString(), any());
    }

    @Test
    void startupSweepWritesNothingInSteadyState() {
        when(serviceRepo.listAll()).thenReturn(Uni.createFrom()
                .item(List.of(service(SID, function(field("进水温度", true))))));
        when(alarmRepo.listAllOpen()).thenReturn(Uni.createFrom().item(List.of(openRow(SID, 1, "进水温度"))));

        reconciler.init(null);

        verify(alarmRepo, never()).closeByKeys(anyString(), any(), any(), anyString(), any());
    }

    @Test
    void startupSweepSwallowsFailures() {
        // 扫不掉不阻断启动（与建索引同口径）：最坏后果是那几条告警多挂一会儿。
        // 这里直接调 init（它是包级可见的 @Observes 方法）—— 真起一次容器才叫得出 StartupEvent
        when(serviceRepo.listAll())
                .thenReturn(Uni.createFrom().failure(new IllegalStateException("mongo is not up yet")));

        reconciler.init(null);   // 不抛

        // 单参数的 warnv 绑到的是 jboss-logging 的 warnv(String, Object) 而不是 varargs 那个重载，
        // 所以用捕获参数断言，顺带钉住「日志里带上了底层原因」
        ArgumentCaptor<Object> reason = ArgumentCaptor.forClass(Object.class);
        verify(logger).warnv(anyString(), reason.capture());
        assertEquals("mongo is not up yet", reason.getValue());
        verify(alarmRepo, never()).listAllOpen();
        verify(alarmRepo, never()).closeByKeys(anyString(), any(), any(), anyString(), any());
    }

    @Test
    void startupSweepSwallowsCloseFailures() {
        when(serviceRepo.listAll()).thenReturn(Uni.createFrom()
                .item(List.of(service(SID, function(field("进水温度", false))))));
        when(alarmRepo.listAllOpen()).thenReturn(Uni.createFrom().item(List.of(openRow(SID, 1, "进水温度"))));
        when(alarmRepo.closeByKeys(anyString(), any(), any(), anyString(), any()))
                .thenReturn(Uni.createFrom().failure(new IllegalStateException("write failed")));

        reconciler.init(null);   // 不抛

        verify(logger).warnv(anyString(), any(Object.class));
    }

    @Test
    void startupSweepWithNoOpenRowsReadsButWritesNothing() {
        when(serviceRepo.listAll()).thenReturn(Uni.createFrom().item(List.of(service(SID))));
        when(alarmRepo.listAllOpen()).thenReturn(Uni.createFrom().item(List.of()));

        reconciler.init(null);

        verify(alarmRepo, never()).closeByKeys(anyString(), any(), any(), anyString(), any());
    }

    // ===== 夹具 =====

    private void reconcile(ModbusService service) {
        reconciler.reconcile(service).await().indefinitely();
    }

    private void givenOpen(ModbusAlarm... rows) {
        when(alarmRepo.listOpenByServiceId(anyString())).thenReturn(Uni.createFrom().item(List.of(rows)));
    }

    private static ModbusAlarm openRow(String serviceId, Integer transactionIndex, String field) {
        ModbusAlarm row = new ModbusAlarm();
        row.serviceId = serviceId;
        row.transactionIndex = transactionIndex;
        row.field = field;
        // 开它的就是夹具定义里那条规则（id 同 RULE_ID）：判据是规则级，没有它这条行会被判成「不认识」
        row.ruleId = RULE_ID;
        row.openKey = ModbusAlarm.openKeyOf(serviceId, transactionIndex, field);
        return row;
    }

    private static ModbusService serviceWithoutId() {
        ModbusService service = new ModbusService();
        ModbusConfig snapshot = new ModbusConfig();
        snapshot.registries = List.of(function(field("进水温度", true)));
        service.configSnapshot = snapshot;
        return service;
    }

    /** 服务夹具：告警现属 configSnapshot.registries[]（寄存器中心），不再属 function 应答字段。 */
    private static ModbusService service(String serviceId, ModbusRegistry... registries) {
        ModbusService service = new ModbusService();
        service.id = new ObjectId(serviceId);
        ModbusConfig snapshot = new ModbusConfig();
        snapshot.registries = new ArrayList<>(List.of(registries));
        service.configSnapshot = snapshot;
        return service;
    }

    /** 历史包袱保留的 `function(...)` 调用外形：现在对应点表里的一个寄存器（告警属寄存器）。 */
    private static ModbusRegistry function(ModbusRegistry... regs) {
        return regs == null || regs.length == 0 ? null : regs[0];
    }

    private static ModbusRegistry function(int index, ModbusRegistry... regs) {
        return function(regs);
    }

    private static ModbusRegistry field(String name, boolean alarmEnabled) {
        ModbusRegistry reg = new ModbusRegistry();
        reg.name = name;
        if (alarmEnabled) {
            ModbusRegistryAlarm alarm = new ModbusRegistryAlarm();
            alarm.id = RULE_ID;
            alarm.enabled = true;
            alarm.compare = ">";
            alarm.threshold = 80.0;
            alarm.level = "WARN";
            alarm.text = "温度过高";
            reg.alarms = new ArrayList<>(List.of(alarm));
        }
        return reg;
    }
}
