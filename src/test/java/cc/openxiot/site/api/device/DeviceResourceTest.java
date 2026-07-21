package cc.openxiot.site.api.device;

import cc.openxiot.site.api.orgcheck.OrgAccessService;
import cc.openxiot.site.api.space.SpaceService;
import cc.openxiot.site.db.device.DeviceEntity;
import cc.openxiot.site.db.device.DeviceSpace;
import cc.openxiot.site.db.space.SpaceEntity;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

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
        s.organizationId = orgId;
        return s;
    }

    @Test
    void hello() {
        given()
                .when().get("/v1/device")
                .then()
                .statusCode(200)
                .body("success", is(true));
    }

    @Test
    @TestSecurity(user = "test-user")
    void addDevices_missingOrgId_returnsError() {
        given()
                .contentType(ContentType.JSON)
                .body("[{\"did\":\"d1\",\"key\":\"k1\"}]")
                .when().post("/v1/device/many/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user")
    void addDevices_spaceNotFound_returnsError() {
        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().nullItem());

        given()
                .contentType(ContentType.JSON)
                .header("X-Org-Id", "org-1")
                .body("[{\"did\":\"d1\",\"key\":\"k1\"}]")
                .when().post("/v1/device/many/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user")
    void addDevices_succeeds() {
        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().item(spaceWithOrg("org-1")));
        when(orgAccessService.isAdmin("org-1", "test-user"))
                .thenReturn(Uni.createFrom().item(true));
        when(manipulationService.addDevices(anyString(), anyString()))
                .thenReturn(Uni.createFrom().item(new io.vertx.core.json.JsonObject()));
        when(deviceService.saveAll(anyList()))
                .thenReturn(Uni.createFrom().item(List.of()));

        given()
                .contentType(ContentType.JSON)
                .header("X-Org-Id", "org-1")
                .body("[{\"did\":\"d1\",\"key\":\"k1\",\"type\":\"sensor\"}]")
                .when().post("/v1/device/many/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(true));
    }

    @Test
    @TestSecurity(user = "test-user")
    void addDevices_invalidBody_returnsError() {
        given()
                .contentType(ContentType.JSON)
                .header("X-Org-Id", "org-1")
                .body("\"not-json\"")
                .when().post("/v1/device/many/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user")
    void getDevices_returnsList() {
        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().item(spaceWithOrg("org-1")));
        when(orgAccessService.isMember("org-1", "test-user"))
                .thenReturn(Uni.createFrom().item(true));

        DeviceEntity device = new DeviceEntity();
        device.did = "d1";
        DeviceSpace ds = new DeviceSpace();
        ds.spaceId = "sid";
        device.space = ds;

        when(deviceService.getBySpaceId("sid"))
                .thenReturn(Uni.createFrom().item(List.of(device)));

        given()
                .header("X-Org-Id", "org-1")
                .when().get("/v1/device/many/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(true))
                .body("data.size()", is(1));
    }

    @Test
    @TestSecurity(user = "test-user")
    void getDevices_missingOrgId_returnsError() {
        given()
                .when().get("/v1/device/many/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user")
    void removeDevices_succeeds() {
        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().item(spaceWithOrg("org-1")));
        when(orgAccessService.isAdmin("org-1", "test-user"))
                .thenReturn(Uni.createFrom().item(true));
        when(manipulationService.removeDevices(anyString(), anyList()))
                .thenReturn(Uni.createFrom().item(new io.vertx.core.json.JsonObject()));
        when(deviceService.deleteByDeviceIds(anyList()))
                .thenReturn(Uni.createFrom().item(1L));

        given()
                .header("X-Org-Id", "org-1")
                .queryParam("did", "d1", "d2")
                .when().delete("/v1/device/many/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(true));
    }

    @Test
    @TestSecurity(user = "test-user")
    void updateSpace_succeeds() {
        when(spaceService.getById("new-space")).thenReturn(Uni.createFrom().item(spaceWithOrg("org-1")));
        when(orgAccessService.isAdmin("org-1", "test-user"))
                .thenReturn(Uni.createFrom().item(true));
        when(deviceService.updateSpace(anyString(), anyString(), anyString()))
                .thenReturn(Uni.createFrom().voidItem());

        given()
                .contentType(ContentType.JSON)
                .header("X-Org-Id", "org-1")
                .body("{\"spaceId\":\"new-space\",\"rootSpaceId\":\"root-1\",\"dids\":[\"d1\",\"d2\"]}")
                .when().put("/v1/device/many/space")
                .then()
                .statusCode(200)
                .body("success", is(true));
    }

    @Test
    @TestSecurity(user = "test-user")
    void updateSpace_missingOrgId_returnsError() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"spaceId\":\"new-space\"}")
                .when().put("/v1/device/many/space")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user")
    void updateSpace_invalidBody_returnsError() {
        given()
                .contentType(ContentType.JSON)
                .body("\"not-json\"")
                .when().put("/v1/device/many/space")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user")
    void getDevices_noAccess_returnsError() {
        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().nullItem());

        given()
                .header("X-Org-Id", "org-1")
                .when().get("/v1/device/many/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user")
    void getShadows_returnsData() {
        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().item(spaceWithOrg("org-1")));
        when(orgAccessService.isMember("org-1", "test-user"))
                .thenReturn(Uni.createFrom().item(true));

        DeviceEntity device = new DeviceEntity();
        device.did = "d1";
        when(deviceService.getBySpaceId("sid"))
                .thenReturn(Uni.createFrom().item(List.of(device)));
        when(manipulationService.getShadows(anyString(), anyList()))
                .thenReturn(Uni.createFrom().item(new io.vertx.core.json.JsonObject()));

        given()
                .header("X-Org-Id", "org-1")
                .when().get("/v1/device/shadows/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(true));
    }
}
