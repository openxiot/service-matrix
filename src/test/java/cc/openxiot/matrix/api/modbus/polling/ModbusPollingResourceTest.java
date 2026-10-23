package cc.openxiot.matrix.api.modbus.polling;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.modbus.service.ModbusServiceService;
import cc.openxiot.matrix.api.space.SpaceService;
import cc.openxiot.matrix.db.modbus.polling.ModbusPolling;
import cc.openxiot.matrix.db.modbus.polling.ModbusPollingRepository;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.matrix.db.space.access.SpaceAccess;
import cc.openxiot.matrix.db.space.access.SpaceAccessRole;
import cc.openxiot.matrix.db.space.access.SpaceAccessType;
import cc.openxiot.matrix.service.orgcheck.OrgAccessService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.smallrye.mutiny.Uni;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

/**
 * 轮询任务查询接口：路径上的 {@code spaceId} 只要**空间成员**（与 {@code invoke} / 历史读同口径）。
 *
 * <p>响应里混着两份东西：{@code items} 是**库里的状态**（任何副本问都一样），而
 * {@code enabled} / {@code queueSize} / {@code inFlight} 是**本进程的内存读数** ——
 * 问到 api 副本时 {@code queueSize} 一定是 0（它不跑调度）。所以这里把 {@code scheduler} 整个 mock 掉。</p>
 *
 * <p>全部走 {@code @InjectMock}，不连 Mongo。</p>
 */
@QuarkusTest
class ModbusPollingResourceTest {

    private static final String SID = "6512f3a4b5c6d7e8f9a0b1c2";
    private static final String SPACE_ID = "6512f3a4b5c6d7e8f9a0b1c3";
    private static final String ORG = "org1";
    private static final String DID = "did-1";
    private static final long NOW = 1_700_000_000_000L;

    @InjectMock
    ModbusServiceService serviceService;

    @InjectMock
    ModbusPollingRepository pollRepo;

    @InjectMock
    ModbusPollScheduler scheduler;

    @InjectMock
    OrgAccessService orgAccessService;

    @InjectMock
    SpaceService spaceService;

    private SpaceEntity space() {
        SpaceEntity s = new SpaceEntity();
        s.id = new ObjectId(SPACE_ID);
        s.name = "测试项目";
        s.accesses = List.of(new SpaceAccess(ORG, SpaceAccessType.ORGANIZATION, SpaceAccessRole.ADMIN));
        return s;
    }

    private ModbusService brief() {
        ModbusService svc = new ModbusService();
        svc.id = new ObjectId(SID);
        svc.name = "1 号冷水机组";
        return svc;
    }

    private ModbusPolling poll() {
        ModbusPolling poll = new ModbusPolling();
        poll.serviceId = SID;
        poll.pollIndex = 1;
        poll.did = DID;
        poll.interval = 10;
        poll.nextRunAt = new Date(NOW + 10_000);
        poll.lastRunAt = new Date(NOW);
        poll.lastStatus = ModbusPollingRepository.STATUS_OK;
        poll.failCount = 0;
        return poll;
    }

    private void allowSpaceMember() {
        when(spaceService.getById(SPACE_ID)).thenReturn(Uni.createFrom().item(space()));
        when(orgAccessService.checkSpaceMember(any(), eq("tester")))
                .thenReturn(Uni.createFrom().voidItem());
    }

    private void denySpaceMember() {
        when(spaceService.getById(SPACE_ID)).thenReturn(Uni.createFrom().item(space()));
        when(orgAccessService.checkSpaceMember(any(), anyString()))
                .thenReturn(Uni.createFrom().failure(new IllegalArgumentException("Not a member of the space")));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void list_spaceMember_succeeds() {
        allowSpaceMember();
        when(serviceService.getBriefBySpaceId(SPACE_ID)).thenReturn(Uni.createFrom().item(List.of(brief())));
        when(pollRepo.listByServiceIds(List.of(SID))).thenReturn(Uni.createFrom().item(List.of(poll())));
        when(scheduler.isEnabled()).thenReturn(true);
        when(scheduler.instanceId()).thenReturn("instance-1");
        when(scheduler.queueSize()).thenReturn(3);
        when(scheduler.busyDeviceCount()).thenReturn(1);
        when(scheduler.inFlightCount()).thenReturn(1);
        when(scheduler.lastTickAt()).thenReturn(NOW);

        given()
                .when().get("/matrix/v1/modbus/polling/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.spaceId", equalTo(SPACE_ID))
                .body("data.enabled", equalTo(true))
                .body("data.instanceId", equalTo("instance-1"))
                .body("data.queueSize", equalTo(3))
                .body("data.busyDevices", equalTo(1))
                .body("data.inFlight", equalTo(1))
                .body("data.lastTickAt", equalTo(NOW))
                .body("data.items", hasSize(1))
                .body("data.items[0].pollIndex", equalTo(1))
                .body("data.items[0].did", equalTo(DID))
                .body("data.items[0].interval", equalTo(10))
                .body("data.items[0].nextRunAt", equalTo(NOW + 10_000))
                .body("data.items[0].lastRunAt", equalTo(NOW))
                .body("data.items[0].lastStatus", equalTo(ModbusPollingRepository.STATUS_OK))
                // 服务名一起带上：前端不必再查一遍定义才知道这条任务是谁的
                .body("data.items[0].serviceName", equalTo("1 号冷水机组"));
    }

    /** api 副本（不跑调度）问到的就是这个形态：{@code items} 照旧是对的，内存读数全是 0。 */
    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void list_onAnApiReplicaReportsEmptyRuntimeState() {
        allowSpaceMember();
        when(serviceService.getBriefBySpaceId(SPACE_ID)).thenReturn(Uni.createFrom().item(List.of(brief())));
        when(pollRepo.listByServiceIds(List.of(SID))).thenReturn(Uni.createFrom().item(List.of(poll())));

        given()
                .when().get("/matrix/v1/modbus/polling/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("data.enabled", equalTo(false))
                .body("data.queueSize", equalTo(0))
                .body("data.items", hasSize(1))
                // 心跳从没打过（进程刚起）：这个键干脆不出现，前端判「在不在」而不是判一个 0
                .body("data.lastTickAt", nullValue());
    }

    /** 没配周期的方法不该有任务：任务表里没有就什么都不返回，不是返回一条「很久没跑」。 */
    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void list_withoutTasksReturnsEmptyItems() {
        allowSpaceMember();
        when(serviceService.getBriefBySpaceId(SPACE_ID)).thenReturn(Uni.createFrom().item(List.of(brief())));
        when(pollRepo.listByServiceIds(List.of(SID))).thenReturn(Uni.createFrom().item(List.of()));

        given()
                .when().get("/matrix/v1/modbus/polling/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.items", hasSize(0));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void list_notMember_returnsError() {
        denySpaceMember();

        given()
                .when().get("/matrix/v1/modbus/polling/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(false));

        verify(serviceService, never()).getBriefBySpaceId(anyString());
        verify(pollRepo, never()).listByServiceIds(anyList());
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void list_spaceNotFound_returnsError() {
        when(spaceService.getById(SPACE_ID)).thenReturn(Uni.createFrom().nullItem());

        given()
                .when().get("/matrix/v1/modbus/polling/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(false));

        verify(pollRepo, never()).listByServiceIds(anyList());
    }
}
