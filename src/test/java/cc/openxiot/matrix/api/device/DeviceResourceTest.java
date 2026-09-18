package cc.openxiot.matrix.api.device;

import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.device.manipulation.ManipulationService;
import cc.openxiot.matrix.api.device.request.DeviceAddRequest;
import cc.openxiot.matrix.api.modbus.service.ModbusServiceService;
import cc.openxiot.matrix.service.orgcheck.OrgAccessService;
import cc.openxiot.matrix.api.space.SpaceService;
import cc.openxiot.matrix.db.device.DeviceEntity;
import cc.openxiot.matrix.db.space.SpaceOn;
import cc.openxiot.matrix.typedef.model.ModelAnchor;
import cn.geekcity.xiot.spec.device.Device;
import cc.openxiot.matrix.db.space.access.SpaceAccessRole;
import cc.openxiot.matrix.db.space.access.SpaceAccessType;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.matrix.db.space.access.SpaceAccess;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.smallrye.mutiny.Uni;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.vertx.core.json.JsonArray;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class DeviceResourceTest {

    @InjectMock
    DeviceService deviceService;

    @InjectMock
    SpaceService spaceService;

    @InjectMock
    ManipulationService manipulationService;

    @InjectMock
    OrgAccessService orgAccessService;

    @InjectMock
    ModbusServiceService modbusServiceService;

    /** id 必须给：归属（manipulation 的 ownerId）由 {@link SpaceEntity#rootSpaceId()} 算，没 id 就算不出来。 */
    private static SpaceEntity spaceWithOrg(String orgId) {
        SpaceEntity s = new SpaceEntity();
        s.id = new ObjectId();
        s.accesses = List.of(new SpaceAccess(orgId, SpaceAccessType.ORGANIZATION, SpaceAccessRole.ADMIN));
        return s;
    }

    @Test
    void hello() {
        given()
                .when().get("/matrix/v1/device")
                .then()
                .statusCode(200)
                .body("success", is(true));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void addDevices_spaceCheckFails_returnsError() {
        // 判定不通过（不是本人不是该空间管理员）→ 200 + success:false，别漏成 500
        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().item(spaceWithOrg("org-1")));
        when(orgAccessService.checkSpaceAdmin(any(), eq("test-user")))
                .thenReturn(Uni.createFrom().failure(new IllegalArgumentException("Not an admin of the organization")));

        given()
                .contentType(ContentType.JSON)
                .body("[{\"did\":\"d1\",\"key\":\"k1\"}]")
                .when().post("/matrix/v1/device/many/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void addDevices_spaceNotFound_returnsError() {
        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().nullItem());

        given()
                .contentType(ContentType.JSON)
                .body("[{\"did\":\"d1\",\"key\":\"k1\"}]")
                .when().post("/matrix/v1/device/many/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void addDevices_succeeds() {
        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().item(spaceWithOrg("org-1")));
        when(orgAccessService.checkSpaceAdmin(any(), eq("test-user")))
                .thenReturn(Uni.createFrom().voidItem());
        when(manipulationService.addDevices(anyString(), anyList()))
                .thenReturn(Uni.createFrom().item(List.of()));
        when(deviceService.saveAll(anyList()))
                .thenReturn(Uni.createFrom().item(List.of()));

        given()
                .contentType(ContentType.JSON)
                .body("[{\"did\":\"d1\",\"key\":\"k1\",\"type\":\"sensor\"}]")
                .when().post("/matrix/v1/device/many/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(true));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void addDevices_malformedBody_returnsBadRequest() {
        // body 由 List<DeviceAddRequest> 参数反序列化，不是数组时由框架直接 400（不再走 OxResponse）
        given()
                .contentType(ContentType.JSON)
                .body("\"not-json\"")
                .when().post("/matrix/v1/device/many/{spaceId}", "sid")
                .then()
                .statusCode(400);
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void addDevices_emptyBody_returnsError() {
        given()
                .contentType(ContentType.JSON)
                .when().post("/matrix/v1/device/many/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void addOne_succeeds() {
        SpaceEntity space = spaceWithOrg("org-1");

        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().item(space));
        when(orgAccessService.checkSpaceAdmin(any(), eq("test-user")))
                .thenReturn(Uni.createFrom().voidItem());
        when(manipulationService.addDevice(anyString(), any(DeviceAddRequest.class)))
                .thenReturn(Uni.createFrom().item(new Device("abc")));
        DeviceEntity saved = new DeviceEntity();
        saved.did = "abc";
        when(deviceService.save(any(DeviceEntity.class)))
                .thenReturn(Uni.createFrom().item(saved));

        given()
                .contentType(ContentType.JSON)
                .body("{\"did\":\"abc\",\"key\":\"k1\"}")
                .when().post("/matrix/v1/device/one/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(true))
                .body("data.did", is("abc"));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void addOne_missingFields_returnsError() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"key\":\"k1\"}")
                .when().post("/matrix/v1/device/one/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void getDevices_returnsList() {
        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().item(spaceWithOrg("org-1")));
        when(orgAccessService.checkSpaceMember(any(), eq("test-user")))
                .thenReturn(Uni.createFrom().voidItem());

        DeviceEntity device = new DeviceEntity();
        device.did = "d1";
        SpaceOn ds = new SpaceOn();
        ds.spaceId = "sid";
        device.space = ds;

        when(deviceService.getBySpaceId("sid"))
                .thenReturn(Uni.createFrom().item(List.of(device)));

        given()
                .when().get("/matrix/v1/device/many/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(true))
                .body("data.size()", is(1));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void getDevices_spaceCheckFails_returnsError() {
        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().item(spaceWithOrg("org-1")));
        when(orgAccessService.checkSpaceMember(any(), eq("test-user")))
                .thenReturn(Uni.createFrom().failure(new IllegalArgumentException("Not a member of the organization")));

        given()
                .when().get("/matrix/v1/device/many/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void removeDevices_succeeds() {
        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().item(spaceWithOrg("org-1")));
        when(orgAccessService.checkSpaceAdmin(any(), eq("test-user")))
                .thenReturn(Uni.createFrom().voidItem());
        when(manipulationService.removeDevices(anyString(), anyList()))
                .thenReturn(Uni.createFrom().item(new io.vertx.core.json.JsonObject()));
        when(modbusServiceService.deleteByDeviceDids(anyList()))
                .thenReturn(Uni.createFrom().item(2L));
        when(deviceService.deleteByDeviceIds(anyList()))
                .thenReturn(Uni.createFrom().item(1L));

        given()
                .queryParam("did", "d1", "d2")
                .when().delete("/matrix/v1/device/many/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(true));

        // 二级联：这批设备下的 Modbus 服务一并删掉
        verify(modbusServiceService).deleteByDeviceDids(List.of("d1", "d2"));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void removeOne_cascadesModbusServices() {
        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().item(spaceWithOrg("org-1")));
        when(orgAccessService.checkSpaceAdmin(any(), eq("test-user")))
                .thenReturn(Uni.createFrom().voidItem());
        when(manipulationService.removeDevice(anyString(), anyString()))
                .thenReturn(Uni.createFrom().item(new io.vertx.core.json.JsonObject()));
        when(modbusServiceService.deleteByDeviceDids(anyList()))
                .thenReturn(Uni.createFrom().item(1L));
        when(deviceService.deleteByDeviceIds(anyList()))
                .thenReturn(Uni.createFrom().item(1L));

        given()
                .when().delete("/matrix/v1/device/one/{spaceId}/{did}", "sid", "d1")
                .then()
                .statusCode(200)
                .body("success", is(true));

        verify(modbusServiceService).deleteByDeviceDids(List.of("d1"));
        verify(deviceService).deleteByDeviceIds(List.of("d1"));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void updateSpace_succeeds() {
        when(spaceService.getById("new-space")).thenReturn(Uni.createFrom().item(spaceWithOrg("org-1")));
        when(orgAccessService.checkSpaceAdmin(any(), eq("test-user")))
                .thenReturn(Uni.createFrom().voidItem());
        when(deviceService.updateSpace(anyString(), anyString(), anyString()))
                .thenReturn(Uni.createFrom().voidItem());

        given()
                .contentType(ContentType.JSON)
                .body("{\"spaceId\":\"new-space\",\"dids\":[\"d1\",\"d2\"]}")
                .when().put("/matrix/v1/device/many/space")
                .then()
                .statusCode(200)
                .body("success", is(true));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void updateSpace_spaceCheckFails_returnsError() {
        // spaceId 在 body 里（不在路径上），判定的还是那个空间
        when(spaceService.getById("new-space")).thenReturn(Uni.createFrom().item(spaceWithOrg("org-1")));
        when(orgAccessService.checkSpaceAdmin(any(), eq("test-user")))
                .thenReturn(Uni.createFrom().failure(new IllegalArgumentException("Not an admin of the organization")));

        given()
                .contentType(ContentType.JSON)
                .body("{\"spaceId\":\"new-space\"}")
                .when().put("/matrix/v1/device/many/space")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void updateSpace_invalidBody_returnsError() {
        // 标量 body 反序列化成不了 DeviceUpdateSpaceRequest，参数为 null，走 spaceId 必填校验
        given()
                .contentType(ContentType.JSON)
                .body("\"not-json\"")
                .when().put("/matrix/v1/device/many/space")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void updateSpace_missingSpaceId_returnsError() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"dids\":[\"d1\"]}")
                .when().put("/matrix/v1/device/many/space")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void getDevices_noAccess_returnsError() {
        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().nullItem());

        given()
                .when().get("/matrix/v1/device/many/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void getShadows_returnsData() {
        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().item(spaceWithOrg("org-1")));
        when(orgAccessService.checkSpaceMember(any(), eq("test-user")))
                .thenReturn(Uni.createFrom().voidItem());

        DeviceEntity device = new DeviceEntity();
        device.did = "d1";
        when(deviceService.getBySpaceId("sid"))
                .thenReturn(Uni.createFrom().item(List.of(device)));
        when(manipulationService.getShadows(anyString(), anyList()))
                .thenReturn(Uni.createFrom().item(new JsonArray()));

        given()
                .when().get("/matrix/v1/device/shadows/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(true));
    }

    /* ------------------------------------------------------------------------------------------
     * 3D 锚点
     * ------------------------------------------------------------------------------------------*/

    private static DeviceEntity device(String did) {
        DeviceEntity d = new DeviceEntity();
        d.did = did;
        return d;
    }

    private void adminOk() {
        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().item(spaceWithOrg("org-1")));
        when(orgAccessService.checkSpaceAdmin(any(), eq("test-user")))
                .thenReturn(Uni.createFrom().voidItem());
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void setAnchor_succeeds() {
        adminOk();
        when(deviceService.getByDeviceId("d1")).thenReturn(Uni.createFrom().item(device("d1")));
        when(deviceService.setAnchor(eq("d1"), any(ModelAnchor.class)))
                .thenReturn(Uni.createFrom().voidItem());

        given()
                .contentType(ContentType.JSON)
                .body("{\"model\":\"001\",\"rev\":\"001.1\",\"x\":4.0,\"y\":0.5,\"z\":2.0,\"ry\":1.57}")
                .when().put("/matrix/v1/device/one/{spaceId}/{did}/anchor", "sid", "d1")
                .then()
                .statusCode(200)
                .body("success", is(true));

        ArgumentCaptor<ModelAnchor> captor = ArgumentCaptor.forClass(ModelAnchor.class);
        verify(deviceService).setAnchor(eq("d1"), captor.capture());
        ModelAnchor sent = captor.getValue();
        assertEquals("001", sent.model);
        assertEquals("001.1", sent.rev);
        assertEquals(4.0, sent.x);
        assertEquals(0.5, sent.y);
        assertEquals(2.0, sent.z);
        assertEquals(1.57, sent.ry);
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void setAnchor_missingModel_returnsError() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"rev\":\"001.1\",\"x\":1,\"y\":2,\"z\":3}")
                .when().put("/matrix/v1/device/one/{spaceId}/{did}/anchor", "sid", "d1")
                .then()
                .statusCode(200)
                .body("success", is(false));

        verify(deviceService, never()).setAnchor(anyString(), any(ModelAnchor.class));
    }

    /**
     * 设备不存在要报错，不能静默成功。
     *
     * <p>{@code $set} 匹配不到文档时 modifiedCount 就是 0，不报错的话前端会显示「已保存」，
     * 但刷新后标记消失 —— 这种 bug 最难查。</p>
     */
    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void setAnchor_deviceNotFound_returnsError() {
        adminOk();
        when(deviceService.getByDeviceId("d1")).thenReturn(Uni.createFrom().nullItem());

        given()
                .contentType(ContentType.JSON)
                .body("{\"model\":\"001\",\"rev\":\"001.1\",\"x\":1,\"y\":2,\"z\":3}")
                .when().put("/matrix/v1/device/one/{spaceId}/{did}/anchor", "sid", "d1")
                .then()
                .statusCode(200)
                .body("success", is(false));

        verify(deviceService, never()).setAnchor(anyString(), any(ModelAnchor.class));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void setAnchor_notAdmin_returnsError() {
        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().item(spaceWithOrg("org-1")));
        when(orgAccessService.checkSpaceAdmin(any(), eq("test-user")))
                .thenReturn(Uni.createFrom().failure(new IllegalArgumentException("No access")));

        given()
                .contentType(ContentType.JSON)
                .body("{\"model\":\"001\",\"rev\":\"001.1\",\"x\":1,\"y\":2,\"z\":3}")
                .when().put("/matrix/v1/device/one/{spaceId}/{did}/anchor", "sid", "d1")
                .then()
                .statusCode(200)
                .body("success", is(false));

        verify(deviceService, never()).setAnchor(anyString(), any(ModelAnchor.class));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void clearAnchor_succeeds() {
        adminOk();
        when(deviceService.getByDeviceId("d1")).thenReturn(Uni.createFrom().item(device("d1")));
        when(deviceService.clearAnchor("d1")).thenReturn(Uni.createFrom().voidItem());

        given()
                .when().delete("/matrix/v1/device/one/{spaceId}/{did}/anchor", "sid", "d1")
                .then()
                .statusCode(200)
                .body("success", is(true));

        verify(deviceService).clearAnchor("d1");
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void clearAnchor_deviceNotFound_returnsError() {
        adminOk();
        when(deviceService.getByDeviceId("d1")).thenReturn(Uni.createFrom().nullItem());

        given()
                .when().delete("/matrix/v1/device/one/{spaceId}/{did}/anchor", "sid", "d1")
                .then()
                .statusCode(200)
                .body("success", is(false));

        verify(deviceService, never()).clearAnchor(anyString());
    }
}
