package cc.openxiot.matrix.api.device;

import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.device.manipulation.ManipulationService;
import cc.openxiot.matrix.api.device.request.DeviceAddRequest;
import cc.openxiot.matrix.service.orgcheck.OrgAccessService;
import cc.openxiot.matrix.api.space.SpaceService;
import cc.openxiot.matrix.db.device.DeviceEntity;
import cc.openxiot.matrix.db.device.DeviceSpace;
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
import org.junit.jupiter.api.Test;

import io.vertx.core.json.JsonArray;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.mockito.ArgumentMatchers.*;
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

    private static SpaceEntity spaceWithOrg(String orgId) {
        SpaceEntity s = new SpaceEntity();
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
    void addDevices_missingOrgId_returnsError() {
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
                .header("X-Org-Id", "org-1")
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
        when(orgAccessService.checkSpaceAdmin(any(), eq("org-1"), eq("test-user")))
                .thenReturn(Uni.createFrom().voidItem());
        when(manipulationService.addDevices(anyString(), anyList()))
                .thenReturn(Uni.createFrom().item(List.of()));
        when(deviceService.saveAll(anyList()))
                .thenReturn(Uni.createFrom().item(List.of()));

        given()
                .contentType(ContentType.JSON)
                .header("X-Org-Id", "org-1")
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
                .header("X-Org-Id", "org-1")
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
                .header("X-Org-Id", "org-1")
                .when().post("/matrix/v1/device/many/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void addOne_succeeds() {
        SpaceEntity space = spaceWithOrg("org-1");
        space.id = new org.bson.types.ObjectId();

        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().item(space));
        when(orgAccessService.checkSpaceAdmin(any(), eq("org-1"), eq("test-user")))
                .thenReturn(Uni.createFrom().voidItem());
        when(manipulationService.addDevice(anyString(), any(DeviceAddRequest.class)))
                .thenReturn(Uni.createFrom().item(new Device("abc")));
        DeviceEntity saved = new DeviceEntity();
        saved.did = "abc";
        when(deviceService.save(any(DeviceEntity.class)))
                .thenReturn(Uni.createFrom().item(saved));

        given()
                .contentType(ContentType.JSON)
                .header("X-Org-Id", "org-1")
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
                .header("X-Org-Id", "org-1")
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
        when(orgAccessService.checkSpaceMember(any(), eq("org-1"), eq("test-user")))
                .thenReturn(Uni.createFrom().voidItem());

        DeviceEntity device = new DeviceEntity();
        device.did = "d1";
        DeviceSpace ds = new DeviceSpace();
        ds.spaceId = "sid";
        device.space = ds;

        when(deviceService.getBySpaceId("sid"))
                .thenReturn(Uni.createFrom().item(List.of(device)));

        given()
                .header("X-Org-Id", "org-1")
                .when().get("/matrix/v1/device/many/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(true))
                .body("data.size()", is(1));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void getDevices_missingOrgId_returnsError() {
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
        when(orgAccessService.checkSpaceAdmin(any(), eq("org-1"), eq("test-user")))
                .thenReturn(Uni.createFrom().voidItem());
        when(manipulationService.removeDevices(anyString(), anyList()))
                .thenReturn(Uni.createFrom().item(new io.vertx.core.json.JsonObject()));
        when(deviceService.deleteByDeviceIds(anyList()))
                .thenReturn(Uni.createFrom().item(1L));

        given()
                .header("X-Org-Id", "org-1")
                .queryParam("did", "d1", "d2")
                .when().delete("/matrix/v1/device/many/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(true));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void updateSpace_succeeds() {
        when(spaceService.getById("new-space")).thenReturn(Uni.createFrom().item(spaceWithOrg("org-1")));
        when(orgAccessService.checkSpaceAdmin(any(), eq("org-1"), eq("test-user")))
                .thenReturn(Uni.createFrom().voidItem());
        when(deviceService.updateSpace(anyString(), anyString(), anyString()))
                .thenReturn(Uni.createFrom().voidItem());

        given()
                .contentType(ContentType.JSON)
                .header("X-Org-Id", "org-1")
                .body("{\"spaceId\":\"new-space\",\"rootSpaceId\":\"root-1\",\"dids\":[\"d1\",\"d2\"]}")
                .when().put("/matrix/v1/device/many/space")
                .then()
                .statusCode(200)
                .body("success", is(true));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void updateSpace_missingOrgId_returnsError() {
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
                .body("{\"rootSpaceId\":\"root-1\",\"dids\":[\"d1\"]}")
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
                .header("X-Org-Id", "org-1")
                .when().get("/matrix/v1/device/many/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void getShadows_returnsData() {
        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().item(spaceWithOrg("org-1")));
        when(orgAccessService.checkSpaceMember(any(), eq("org-1"), eq("test-user")))
                .thenReturn(Uni.createFrom().voidItem());

        DeviceEntity device = new DeviceEntity();
        device.did = "d1";
        when(deviceService.getBySpaceId("sid"))
                .thenReturn(Uni.createFrom().item(List.of(device)));
        when(manipulationService.getShadows(anyString(), anyList()))
                .thenReturn(Uni.createFrom().item(new JsonArray()));

        given()
                .header("X-Org-Id", "org-1")
                .when().get("/matrix/v1/device/shadows/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(true));
    }
}
