package cc.openxiot.site.api.device;

import cc.openxiot.site.api.space.SpaceService;
import cc.openxiot.site.db.device.DeviceEntity;
import cc.openxiot.site.db.device.DeviceSpace;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
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
    void addDevices_withoutAccess_returnsError() {
        when(spaceService.noAccess(anyString(), anyString())).thenReturn(true);

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
    void addDevices_succeeds() {
        when(spaceService.noAccess(anyString(), anyString())).thenReturn(false);

        given()
                .contentType(ContentType.JSON)
                .body("[{\"did\":\"d1\",\"key\":\"k1\",\"type\":\"sensor\"}]")
                .when().post("/v1/device/many/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(true));
    }

    @Test
    @TestSecurity(user = "test-user")
    void addDevices_invalidBody_returnsError() {
        when(spaceService.noAccess(anyString(), anyString())).thenReturn(false);

        given()
                .contentType(ContentType.JSON)
                .body("\"not-json\"")
                .when().post("/v1/device/many/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user")
    void getDevices_returnsList() {
        when(spaceService.noAccess(anyString(), anyString())).thenReturn(false);

        DeviceEntity device = new DeviceEntity();
        device.did = "d1";
        DeviceSpace ds = new DeviceSpace();
        ds.spaceId = "sid";
        device.space = ds;

        when(deviceService.getBySpaceId("sid")).thenReturn(List.of(device));

        given()
                .when().get("/v1/device/many/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(true))
                .body("data.size()", is(1));
    }

    @Test
    @TestSecurity(user = "test-user")
    void removeDevices_succeeds() {
        when(spaceService.noAccess(anyString(), anyString())).thenReturn(false);

        given()
                .queryParam("did", "d1", "d2")
                .when().delete("/v1/device/many/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(true));
    }

    @Test
    @TestSecurity(user = "test-user")
    void updateSpace_succeeds() {
        when(spaceService.noAccess(anyString(), anyString())).thenReturn(false);

        given()
                .contentType(ContentType.JSON)
                .body("{\"spaceId\":\"new-space\",\"rootSpaceId\":\"root-1\",\"dids\":[\"d1\",\"d2\"]}")
                .when().put("/v1/device/many/space")
                .then()
                .statusCode(200)
                .body("success", is(true));
    }

    @Test
    @TestSecurity(user = "test-user")
    void updateSpace_invalidBody_returnsError() {
        when(spaceService.noAccess(anyString(), anyString())).thenReturn(false);

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
        when(spaceService.noAccess(anyString(), anyString())).thenReturn(true);

        given()
                .when().get("/v1/device/many/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user")
    void getShadows_returnsData() {
        when(spaceService.noAccess(anyString(), anyString())).thenReturn(false);

        DeviceEntity device = new DeviceEntity();
        device.did = "d1";
        when(deviceService.getBySpaceId("sid")).thenReturn(List.of(device));

        given()
                .when().get("/v1/device/shadows/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(true));
    }
}
