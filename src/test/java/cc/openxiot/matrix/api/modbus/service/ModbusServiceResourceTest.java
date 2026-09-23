package cc.openxiot.matrix.api.modbus.service;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.space.SpaceService;
import cc.openxiot.matrix.db.modbus.service.DeviceOn;
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
import io.vertx.core.json.JsonObject;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Modbus 服务接口的空间口径：路径上的 {@code spaceId} 是鉴权锚点 ——
 * 增删改要**空间管理员**、查询与 {@code invoke} 要**空间成员**（与 {@code DeviceResource} 同口径），
 * 通过后一律按 id 操作，不再要求「组织管理员 / 组织成员」。
 *
 * <p>全部走 {@code @InjectMock}，不连 Mongo。</p>
 */
@QuarkusTest
class ModbusServiceResourceTest {

    private static final String SID = "6512f3a4b5c6d7e8f9a0b1c2";
    private static final String SPACE_ID = "6512f3a4b5c6d7e8f9a0b1c3";
    private static final String ORG = "org1";
    private static final String DID = "did-1";

    @InjectMock
    ModbusServiceService service;

    @InjectMock
    OrgAccessService orgAccessService;

    @InjectMock
    SpaceService spaceService;

    /** 空间：带一个组织条目（X-Org-Id 命中它时，组织管理员 / 成员同样通过）。 */
    private SpaceEntity space() {
        SpaceEntity s = new SpaceEntity();
        s.id = new ObjectId(SPACE_ID);
        s.name = "测试项目";
        s.accesses = List.of(new SpaceAccess(ORG, SpaceAccessType.ORGANIZATION, SpaceAccessRole.ADMIN));
        return s;
    }

    private ModbusService sample() {
        ModbusService svc = new ModbusService();
        svc.id = new ObjectId(SID);
        svc.name = "1 号冷水机组";
        svc.device = new DeviceOn();
        svc.device.did = DID;
        return svc;
    }

    /** 创建 / 修改的请求体（Jackson 直接绑到 ModbusService 的公开字段）。 */
    private String requestBody() {
        return new JsonObject()
                .put("name", "1 号冷水机组")
                .put("version", 1)
                .put("device", new JsonObject()
                        .put("did", DID).put("siid", 1).put("aiid", 2).put("argument", 2))
                .encode();
    }

    private void allowSpaceAdmin() {
        when(spaceService.getById(SPACE_ID)).thenReturn(Uni.createFrom().item(space()));
        when(orgAccessService.checkSpaceAdmin(any(), eq("tester")))
                .thenReturn(Uni.createFrom().voidItem());
    }

    private void denySpaceAdmin() {
        when(spaceService.getById(SPACE_ID)).thenReturn(Uni.createFrom().item(space()));
        when(orgAccessService.checkSpaceAdmin(any(), anyString()))
                .thenReturn(Uni.createFrom().failure(new IllegalArgumentException("Not an admin of the space")));
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

    // ===== 增删改：空间管理员 =====

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void createOne_spaceAdmin_succeeds() {
        allowSpaceAdmin();
        // 明确转型：否则 Uni.createFrom().item(Object) 会命中 item(Supplier) 那个重载
        when(service.create(any())).thenAnswer(invocation ->
                Uni.createFrom().item((ModbusService) invocation.getArgument(0)));

        given().contentType("application/json").body(requestBody())
                .when().post("/matrix/v1/modbus/service/one/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.name", equalTo("1 号冷水机组"));

        // 服务本身不再记组织：创建请求体里没有的字段不该被塞进去（X-Org-Id 只用于空间鉴权）
        ArgumentCaptor<ModbusService> captor = ArgumentCaptor.forClass(ModbusService.class);
        verify(service).create(captor.capture());
        assertEquals("1 号冷水机组", captor.getValue().name);
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void createOne_notAdmin_returnsError() {
        denySpaceAdmin();

        given().contentType("application/json").body(requestBody())
                .when().post("/matrix/v1/modbus/service/one/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(false));

        verify(service, never()).create(any());
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void updateOne_spaceAdmin_succeeds() {
        allowSpaceAdmin();
        when(service.update(eq(SID), any())).thenReturn(Uni.createFrom().item(sample()));

        given().contentType("application/json").body(requestBody())
                .when().put("/matrix/v1/modbus/service/one/{spaceId}/{id}", SPACE_ID, SID)
                .then().statusCode(200)
                .body("success", equalTo(true));

        verify(service).update(eq(SID), any());
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void deleteOne_spaceAdmin_succeeds() {
        allowSpaceAdmin();
        when(service.deleteById(SID)).thenReturn(Uni.createFrom().item(1L));

        given()
                .when().delete("/matrix/v1/modbus/service/one/{spaceId}/{id}", SPACE_ID, SID)
                .then().statusCode(200)
                .body("success", equalTo(true));

        verify(service).deleteById(SID);
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void deleteOne_notAdmin_returnsError() {
        denySpaceAdmin();

        given()
                .when().delete("/matrix/v1/modbus/service/one/{spaceId}/{id}", SPACE_ID, SID)
                .then().statusCode(200)
                .body("success", equalTo(false));

        verify(service, never()).deleteById(anyString());
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void deleteOne_serviceNotFound_returnsError() {
        allowSpaceAdmin();
        when(service.deleteById(SID)).thenReturn(Uni.createFrom()
                .failure(new IllegalArgumentException("modbus service not found: " + SID)));

        given()
                .when().delete("/matrix/v1/modbus/service/one/{spaceId}/{id}", SPACE_ID, SID)
                .then().statusCode(200)
                .body("success", equalTo(false));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void deleteOne_spaceNotFound_returnsError() {
        when(spaceService.getById(SPACE_ID)).thenReturn(Uni.createFrom().nullItem());

        given()
                .when().delete("/matrix/v1/modbus/service/one/{spaceId}/{id}", SPACE_ID, SID)
                .then().statusCode(200)
                .body("success", equalTo(false));

        verify(service, never()).deleteById(anyString());
    }

    // ===== 查询与 invoke：空间成员 =====

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void listByParent_spaceMember_succeeds() {
        allowSpaceMember();
        when(service.listByParent(DID)).thenReturn(Uni.createFrom().item(List.of(sample())));

        given()
                .when().get("/matrix/v1/modbus/service/parent/{spaceId}/{did}", SPACE_ID, DID)
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data[0].name", equalTo("1 号冷水机组"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void invoke_spaceMember_succeeds() {
        allowSpaceMember();
        // 归属传的是根空间 id：SPACE_ID 是根空间，根就是它自己
        when(service.invoke(eq(SPACE_ID), eq(SID), eq(1), isNull()))
                .thenReturn(Uni.createFrom().item(new JsonObject().put("进水温度", 61.5)));

        given().contentType("application/json")
                .body(new JsonObject().put("service", SID).put("function", 1).encode())
                .when().post("/matrix/v1/modbus/service/invoke/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.进水温度", equalTo(61.5f));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void invoke_notMember_returnsError() {
        denySpaceMember();

        given().contentType("application/json")
                .body(new JsonObject().put("service", SID).put("function", 1).encode())
                .when().post("/matrix/v1/modbus/service/invoke/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(false));

        verify(service, never()).invoke(anyString(), anyString(), any(), any());
    }
}
