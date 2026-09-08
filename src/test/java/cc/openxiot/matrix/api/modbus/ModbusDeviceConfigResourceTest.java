package cc.openxiot.matrix.api.modbus;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import cc.openxiot.common.person.Person;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.db.modbus.ModbusDeviceConfig;
import cc.openxiot.matrix.service.orgcheck.OrgAccessService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.smallrye.mutiny.Uni;

@QuarkusTest
class ModbusDeviceConfigResourceTest {

    @InjectMock
    ModbusDeviceConfigService service;

    @InjectMock
    OrgAccessService orgAccessService;

    private ModbusDeviceConfig sample() {
        ModbusDeviceConfig cfg = new ModbusDeviceConfig();
        cfg.id = "c1";
        cfg.orgId = "org1";
        cfg.manufacturer = "开利";
        cfg.model = "19XRV";
        cfg.slaveId = 1;
        cfg.visibility = "private";
        cfg.creator = Person.of("acc1", "小明");
        cfg.updater = Person.of("acc1", "小明");
        return cfg;
    }

    private void allowMember() {
        when(orgAccessService.checkMember(anyString(), anyString())).thenReturn(Uni.createFrom().voidItem());
    }

    @Test
    void helloIsPublic() {
        given().when().get("/matrix/v1/modbus")
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data", equalTo("Hello from ModbusDeviceConfigResource"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void missingOrgHeaderFails() {
        given().when().get("/matrix/v1/modbus/many")
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", containsString("X-Org-Id"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void createOkOverridesOrgFromHeader() {
        allowMember();
        when(service.create(any(ModbusDeviceConfig.class))).thenReturn(Uni.createFrom().item(sample()));

        given().header("X-Org-Id", "org1")
                .contentType(ContentType.JSON)
                .body("{\"manufacturer\":\"开利\",\"model\":\"19XRV\",\"slaveId\":1,\"visibility\":\"private\",\"commands\":[]}")
                .when().post("/matrix/v1/modbus/one")
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.manufacturer", equalTo("开利"))
                .body("data.model", equalTo("19XRV"))
                .body("data.slaveId", equalTo(1))
                .body("data.visibility", equalTo("private"))
                .body("data.id", equalTo("c1"));

        ArgumentCaptor<ModbusDeviceConfig> captor = ArgumentCaptor.forClass(ModbusDeviceConfig.class);
        verify(service).create(captor.capture());
        ModbusDeviceConfig sent = captor.getValue();
        // orgId 被请求头覆盖；creator 取当前登录人（TestSecurity 无 username claim → name 为 null）
        assertEquals("org1", sent.orgId);
        assertEquals("tester", sent.creator.id);
        assertNotNull(sent.creator.timestamp);
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void createValidationErrorIsHttp200() {
        allowMember();
        when(service.create(any(ModbusDeviceConfig.class)))
                .thenReturn(Uni.createFrom().failure(new IllegalArgumentException("manufacturer is required")));

        given().header("X-Org-Id", "org1")
                .contentType(ContentType.JSON)
                .body("{\"manufacturer\":\"\",\"commands\":[]}")
                .when().post("/matrix/v1/modbus/one")
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", equalTo("manufacturer is required"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void listOk() {
        allowMember();
        when(service.listByOrg(anyString())).thenReturn(Uni.createFrom().item(List.of(sample())));

        given().header("X-Org-Id", "org1")
                .when().get("/matrix/v1/modbus/many")
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data", hasSize(1))
                .body("data[0].manufacturer", equalTo("开利"))
                .body("data[0].model", equalTo("19XRV"))
                .body("data[0].slaveId", equalTo(1))
                .body("data[0].visibility", equalTo("private"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void visibleOk() {
        allowMember();
        when(service.listVisible("org1")).thenReturn(Uni.createFrom().item(List.of(sample())));

        given().header("X-Org-Id", "org1")
                .when().get("/matrix/v1/modbus/visible")
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data", hasSize(1))
                .body("data[0].visibility", equalTo("private"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void publicOk() {
        ModbusDeviceConfig pub = sample();
        pub.visibility = "public";
        when(service.listPublic()).thenReturn(Uni.createFrom().item(List.of(pub)));

        given().when().get("/matrix/v1/modbus/public")
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data", hasSize(1))
                .body("data[0].visibility", equalTo("public"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void getNotFound() {
        allowMember();
        when(service.get("org1", "nope"))
                .thenReturn(Uni.createFrom().failure(new IllegalArgumentException("modbus device config not found: nope")));

        given().header("X-Org-Id", "org1")
                .when().get("/matrix/v1/modbus/one/nope")
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", containsString("not found"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void getOneWithoutOrgReadsPublic() {
        ModbusDeviceConfig pub = sample();
        pub.visibility = "public";
        when(service.getPublic("c1")).thenReturn(Uni.createFrom().item(pub));

        // 未带 X-Org-Id（未选组织浏览）：仍可读公开配置详情，无需成员校验
        given().when().get("/matrix/v1/modbus/one/c1")
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.manufacturer", equalTo("开利"))
                .body("data.visibility", equalTo("public"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void getOneWithOrgReadsCrossOrgPublic() {
        allowMember();
        ModbusDeviceConfig pub = sample();
        pub.orgId = "org2"; // 他组织公开
        pub.visibility = "public";
        when(service.get("org1", "c1")).thenReturn(Uni.createFrom().item(pub));

        given().header("X-Org-Id", "org1")
                .when().get("/matrix/v1/modbus/one/c1")
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.orgId", equalTo("org2"))
                .body("data.visibility", equalTo("public"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void updateOk() {
        allowMember();
        when(service.update(anyString(), anyString(), any(ModbusDeviceConfig.class)))
                .thenReturn(Uni.createFrom().item(sample()));

        given().header("X-Org-Id", "org1")
                .contentType(ContentType.JSON)
                .body("{\"manufacturer\":\"开利\",\"model\":\"19XRV\",\"slaveId\":1,\"visibility\":\"private\",\"commands\":[]}")
                .when().put("/matrix/v1/modbus/one/c1")
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.manufacturer", equalTo("开利"))
                .body("data.slaveId", equalTo(1));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void deleteOk() {
        allowMember();
        when(service.delete("org1", "c1")).thenReturn(Uni.createFrom().item(1L));

        given().header("X-Org-Id", "org1")
                .when().delete("/matrix/v1/modbus/one/c1")
                .then().statusCode(200)
                .body("success", equalTo(true));
    }
}
