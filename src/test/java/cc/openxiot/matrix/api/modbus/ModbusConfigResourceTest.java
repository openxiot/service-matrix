package cc.openxiot.matrix.api.modbus;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import cc.openxiot.matrix.api.modbus.config.ModbusConfigService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import cc.openxiot.common.person.Person;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.config.ModbusDeviceType;
import cc.openxiot.matrix.service.orgcheck.OrgAccessService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.smallrye.mutiny.Uni;
import org.bson.types.ObjectId;

@QuarkusTest
class ModbusConfigResourceTest {

    /** 落库主键（系统生成的 ObjectId）：JSON 里必须呈现为其十六进制串。 */
    private static final ObjectId CID = new ObjectId("6512f3a4b5c6d7e8f9a0b1c2");

    @InjectMock
    ModbusConfigService service;

    @InjectMock
    OrgAccessService orgAccessService;

    private ModbusConfig sample() {
        ModbusConfig cfg = new ModbusConfig();
        cfg.id = CID;
        cfg.orgId = "org1";
        cfg.slave.manufacturer = "开利";
        cfg.slave.model = "19XRV";
        cfg.slave.slaveId = 1;
        ModbusDeviceType deviceType = new ModbusDeviceType();
        deviceType.type = "urn:xiot-spec:device:chiller:0000A005";
        deviceType.specDescription = Map.of("zh-CN", "智能楼宇");
        deviceType.typeDescription = Map.of("zh-CN", "冷水机组", "en-US", "Chiller");
        cfg.slave.type = deviceType;
        cfg.visibility = "private";
        cfg.lifecycle = "development";
        cfg.creator = Person.of("acc1", "小明");
        cfg.updater = Person.of("acc1", "小明");
        return cfg;
    }

    private void allowMember() {
        when(orgAccessService.checkMember(anyString(), anyString())).thenReturn(Uni.createFrom().voidItem());
    }

    @Test
    void helloIsPublic() {
        given().when().get("/matrix/v1/modbus/config")
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data", equalTo("Hello from ModbusConfigResource"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void missingOrgHeaderFails() {
        given().when().get("/matrix/v1/modbus/config/many")
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", containsString("X-Org-Id"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void createOkOverridesOrgFromHeader() {
        allowMember();
        when(service.create(any(ModbusConfig.class))).thenReturn(Uni.createFrom().item(sample()));

        given().header("X-Org-Id", "org1")
                .contentType(ContentType.JSON)
                .body("{\"slave\":{\"manufacturer\":\"开利\",\"model\":\"19XRV\",\"slaveId\":1,"
                        + "\"type\":{\"type\":\"urn:xiot-spec:device:chiller:0000A005\","
                        + "\"specDescription\":{\"zh-CN\":\"智能楼宇\"},"
                        + "\"typeDescription\":{\"zh-CN\":\"冷水机组\",\"en-US\":\"Chiller\"}}},"
                        + "\"visibility\":\"private\",\"commands\":[]}")
                .when().post("/matrix/v1/modbus/config/one")
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.slave.manufacturer", equalTo("开利"))
                .body("data.slave.model", equalTo("19XRV"))
                .body("data.slave.slaveId", equalTo(1))
                .body("data.visibility", equalTo("private"))
                .body("data.slave.type.type", equalTo("urn:xiot-spec:device:chiller:0000A005"))
                .body("data.slave.type.typeDescription.zh-CN", equalTo("冷水机组"))
                .body("data.lifecycle", equalTo("development"))
                // 主键落库是 ObjectId，对外（JSON）仍是十六进制字符串
                .body("data.id", equalTo(CID.toHexString()));

        ArgumentCaptor<ModbusConfig> captor = ArgumentCaptor.forClass(ModbusConfig.class);
        verify(service).create(captor.capture());
        ModbusConfig sent = captor.getValue();
        // orgId 被请求头覆盖；creator 取当前登录人（TestSecurity 无 username claim → name 为 null）
        assertEquals("org1", sent.orgId);
        assertNotNull(sent.slave.type);
        assertEquals("urn:xiot-spec:device:chiller:0000A005", sent.slave.type.type);
        assertEquals("冷水机组", sent.slave.type.typeDescription.get("zh-CN"));
        assertEquals("开利", sent.slave.manufacturer);
        assertEquals("tester", sent.creator.id);
        assertNotNull(sent.creator.timestamp);
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void createValidationErrorIsHttp200() {
        allowMember();
        when(service.create(any(ModbusConfig.class)))
                .thenReturn(Uni.createFrom().failure(new IllegalArgumentException("manufacturer is required")));

        given().header("X-Org-Id", "org1")
                .contentType(ContentType.JSON)
                .body("{\"slave\":{\"manufacturer\":\"\"},\"commands\":[]}")
                .when().post("/matrix/v1/modbus/config/one")
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
                .when().get("/matrix/v1/modbus/config/many")
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data", hasSize(1))
                .body("data[0].slave.manufacturer", equalTo("开利"))
                .body("data[0].slave.model", equalTo("19XRV"))
                .body("data[0].slave.slaveId", equalTo(1))
                .body("data[0].visibility", equalTo("private"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void visibleOk() {
        allowMember();
        when(service.listVisible("org1")).thenReturn(Uni.createFrom().item(List.of(sample())));

        given().header("X-Org-Id", "org1")
                .when().get("/matrix/v1/modbus/config/visible")
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data", hasSize(1))
                .body("data[0].visibility", equalTo("private"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void publicOk() {
        ModbusConfig pub = sample();
        pub.visibility = "public";
        when(service.listPublic()).thenReturn(Uni.createFrom().item(List.of(pub)));

        given().when().get("/matrix/v1/modbus/config/public")
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
                .when().get("/matrix/v1/modbus/config/one/nope")
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", containsString("not found"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void getOneWithoutOrgReadsPublic() {
        ModbusConfig pub = sample();
        pub.visibility = "public";
        when(service.getPublic("c1")).thenReturn(Uni.createFrom().item(pub));

        // 未带 X-Org-Id（未选组织浏览）：仍可读公开配置详情，无需成员校验
        given().when().get("/matrix/v1/modbus/config/one/c1")
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.slave.manufacturer", equalTo("开利"))
                .body("data.visibility", equalTo("public"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void getOneWithOrgReadsCrossOrgPublic() {
        allowMember();
        ModbusConfig pub = sample();
        pub.orgId = "org2"; // 他组织公开
        pub.visibility = "public";
        when(service.get("org1", "c1")).thenReturn(Uni.createFrom().item(pub));

        given().header("X-Org-Id", "org1")
                .when().get("/matrix/v1/modbus/config/one/c1")
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.orgId", equalTo("org2"))
                .body("data.visibility", equalTo("public"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void updateOk() {
        allowMember();
        when(service.update(anyString(), anyString(), any(ModbusConfig.class)))
                .thenReturn(Uni.createFrom().item(sample()));

        given().header("X-Org-Id", "org1")
                .contentType(ContentType.JSON)
                .body("{\"slave\":{\"manufacturer\":\"开利\",\"model\":\"19XRV\",\"slaveId\":1},\"visibility\":\"private\",\"commands\":[]}")
                .when().put("/matrix/v1/modbus/config/one/c1")
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.slave.manufacturer", equalTo("开利"))
                .body("data.slave.slaveId", equalTo(1));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void deleteOk() {
        allowMember();
        when(service.delete("org1", "c1")).thenReturn(Uni.createFrom().item(1L));

        given().header("X-Org-Id", "org1")
                .when().delete("/matrix/v1/modbus/config/one/c1")
                .then().statusCode(200)
                .body("success", equalTo(true));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void changeLifecycleOk() {
        allowMember();
        ModbusConfig preview = sample();
        preview.lifecycle = "preview";
        when(service.changeLifecycle(eq("org1"), eq("c1"), eq("preview"), any(Person.class)))
                .thenReturn(Uni.createFrom().item(preview));

        given().header("X-Org-Id", "org1")
                .when().put("/matrix/v1/modbus/config/one/c1/lifecycle/preview")
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.lifecycle", equalTo("preview"));
        verify(service).changeLifecycle(eq("org1"), eq("c1"), eq("preview"), any(Person.class));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void changeLifecycleInvalidValueIsHttp200() {
        allowMember();
        when(service.changeLifecycle(anyString(), anyString(), anyString(), any(Person.class)))
                .thenThrow(new IllegalArgumentException("invalid lifecycle: archive"));

        given().header("X-Org-Id", "org1")
                .when().put("/matrix/v1/modbus/config/one/c1/lifecycle/archive")
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", containsString("invalid lifecycle"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void changeLifecycleMissingOrgFails() {
        given().when().put("/matrix/v1/modbus/config/one/c1/lifecycle/preview")
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", containsString("X-Org-Id"));
    }
}
