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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import cc.openxiot.matrix.api.modbus.config.ModbusConfigService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import cc.openxiot.common.person.Person;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.config.ModbusConfigOwner;
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
        cfg.owner = ModbusConfigOwner.ofOrg("org1", "第一个组织");
        cfg.slave.manufacturer = "开利";
        cfg.slave.model = "19XRV";
        cfg.slave.slaveId = 1;
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
    void missingOrgIdFails() {
        given().when().get("/matrix/v1/modbus/config/many")
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", containsString("orgId is required"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void createOkWithOrgOwner() {
        allowMember();
        when(service.create(any(ModbusConfig.class))).thenReturn(Uni.createFrom().item(sample()));

        given().contentType(ContentType.JSON)
                .body("{\"owner\":{\"id\":\"org1\",\"type\":\"organization\"},"
                        + "\"slave\":{\"manufacturer\":\"开利\",\"model\":\"19XRV\",\"slaveId\":1},"
                        + "\"visibility\":\"private\",\"commands\":[]}")
                .when().post("/matrix/v1/modbus/config/one")
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.slave.manufacturer", equalTo("开利"))
                .body("data.slave.model", equalTo("19XRV"))
                .body("data.slave.slaveId", equalTo(1))
                .body("data.visibility", equalTo("private"))
                .body("data.lifecycle", equalTo("development"))
                // 主键落库是 ObjectId，对外（JSON）仍是十六进制字符串
                .body("data.id", equalTo(CID.toHexString()));

        ArgumentCaptor<ModbusConfig> captor = ArgumentCaptor.forClass(ModbusConfig.class);
        verify(service).create(captor.capture());
        ModbusConfig sent = captor.getValue();
        // owner 取自请求体（组织归属 → 校验成员）；creator 取当前登录人（TestSecurity 无 username claim → name 为 null）
        assertEquals("org1", sent.owner.id);
        assertEquals("organization", sent.owner.type);
        assertEquals("开利", sent.slave.manufacturer);
        assertEquals("tester", sent.creator.id);
        assertNotNull(sent.creator.timestamp);
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void createWithUserOwnerNotAnotherUser() {
        // user 归属：body 的 owner.id 必须是当前登录人，替他人建个人点表被拒
        when(service.create(any(ModbusConfig.class))).thenReturn(Uni.createFrom().item(sample()));

        given().contentType(ContentType.JSON)
                .body("{\"owner\":{\"id\":\"someone-else\",\"type\":\"user\"},"
                        + "\"slave\":{\"manufacturer\":\"开利\",\"model\":\"19XRV\",\"slaveId\":1},"
                        + "\"visibility\":\"private\",\"commands\":[]}")
                .when().post("/matrix/v1/modbus/config/one")
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", containsString("cannot create personal config for another user"));
        verify(service, never()).create(any(ModbusConfig.class));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void createWithOrgOwnerRequiresMembership() {
        // 组织归属：当前账号必须是该组织成员（以 body 的 owner.id 为锚），否则拒
        when(orgAccessService.checkMember(eq("other-org"), anyString()))
                .thenReturn(Uni.createFrom().failure(new IllegalArgumentException("Not a member of the organization")));

        given().contentType(ContentType.JSON)
                .body("{\"owner\":{\"id\":\"other-org\",\"type\":\"organization\"},"
                        + "\"slave\":{\"manufacturer\":\"开利\",\"model\":\"19XRV\",\"slaveId\":1},"
                        + "\"visibility\":\"private\",\"commands\":[]}")
                .when().post("/matrix/v1/modbus/config/one")
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", containsString("Not a member"));
        verify(service, never()).create(any(ModbusConfig.class));
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

        given().queryParam("orgId", "org1")
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
        when(service.listVisible(anyString(), eq("org1"))).thenReturn(Uni.createFrom().item(List.of(sample())));

        given().queryParam("orgId", "org1")
                .when().get("/matrix/v1/modbus/config/visible")
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data", hasSize(1))
                .body("data[0].visibility", equalTo("private"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void visibleWithoutOrgNeedsNoMembership() {
        // 未带组织参数：不校验组织成员，直接回「公开 + 本人」（设备映射页在没有组织时也要读得到点表）
        ModbusConfig pub = sample();
        pub.visibility = "public";
        when(service.listVisible(anyString(), isNull())).thenReturn(Uni.createFrom().item(List.of(pub)));

        given().when().get("/matrix/v1/modbus/config/visible")
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data", hasSize(1))
                .body("data[0].visibility", equalTo("public"));

        verify(orgAccessService, never()).checkMember(anyString(), anyString());
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void listByUserOk() {
        // /many/user：本人私有，不查组织、不校验成员
        when(service.listByUser(anyString())).thenReturn(Uni.createFrom().item(List.of(sample())));

        given().when().get("/matrix/v1/modbus/config/many/user")
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data", hasSize(1))
                .body("data[0].visibility", equalTo("private"));

        verify(service).listByUser(anyString());
        verify(orgAccessService, never()).checkMember(anyString(), anyString());
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void getVisibleByUserOk() {
        // /visible/user：公开 + 本人，无组织个人访问，不校验成员
        ModbusConfig pub = sample();
        pub.visibility = "public";
        when(service.listVisible(anyString(), isNull())).thenReturn(Uni.createFrom().item(List.of(pub)));

        given().when().get("/matrix/v1/modbus/config/visible/user")
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data", hasSize(1))
                .body("data[0].visibility", equalTo("public"));

        verify(orgAccessService, never()).checkMember(anyString(), anyString());
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void getVisibleByOrgOk() {
        // /visible/org：公开 + 该组织私有（精确口径，不含本人），须成员校验
        when(orgAccessService.checkMember(eq("org1"), anyString())).thenReturn(Uni.createFrom().voidItem());
        when(service.listVisibleByOrg(eq("org1"))).thenReturn(Uni.createFrom().item(List.of(sample())));

        given().queryParam("orgId", "org1")
                .when().get("/matrix/v1/modbus/config/visible/org")
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data", hasSize(1))
                .body("data[0].visibility", equalTo("private"));

        verify(orgAccessService).checkMember(eq("org1"), anyString());
        verify(service).listVisibleByOrg(eq("org1"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void getVisibleByOrgMissingOrgIdFails() {
        given().when().get("/matrix/v1/modbus/config/visible/org")
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", containsString("orgId is required"));
        verify(service, never()).listVisibleByOrg(anyString());
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
        when(service.get(anyString(), eq("nope")))
                .thenReturn(Uni.createFrom().failure(new IllegalArgumentException("modbus device config not found: nope")));

        given().when().get("/matrix/v1/modbus/config/one/nope")
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", containsString("not found"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void getOneWithoutOrgReadsPublic() {
        ModbusConfig pub = sample();
        pub.visibility = "public";
        // 无组织浏览：归属鉴权在 service 按 owner 判（公开 → 直接可读），Resource 不强求 X-Org-Id
        when(service.get(anyString(), eq("c1"))).thenReturn(Uni.createFrom().item(pub));

        given().when().get("/matrix/v1/modbus/config/one/c1")
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.slave.manufacturer", equalTo("开利"))
                .body("data.visibility", equalTo("public"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void getOneWithOrgReadsCrossOrgPublic() {
        ModbusConfig pub = sample();
        pub.owner = ModbusConfigOwner.ofOrg("org2", "第二个组织"); // 他组织公开
        pub.visibility = "public";
        when(service.get(anyString(), eq("c1"))).thenReturn(Uni.createFrom().item(pub));

        given().when().get("/matrix/v1/modbus/config/one/c1")
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.owner.id", equalTo("org2"))
                .body("data.visibility", equalTo("public"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void updateOk() {
        when(service.update(anyString(), anyString(), any(ModbusConfig.class)))
                .thenReturn(Uni.createFrom().item(sample()));

        given().contentType(ContentType.JSON)
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
        when(service.delete(anyString(), eq("c1"))).thenReturn(Uni.createFrom().item(1L));

        given().when().delete("/matrix/v1/modbus/config/one/c1")
                .then().statusCode(200)
                .body("success", equalTo(true));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void changeLifecycleOk() {
        ModbusConfig preview = sample();
        preview.lifecycle = "preview";
        when(service.changeLifecycle(anyString(), eq("c1"), eq("preview"), any(Person.class)))
                .thenReturn(Uni.createFrom().item(preview));

        given().when().put("/matrix/v1/modbus/config/one/c1/lifecycle/preview")
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.lifecycle", equalTo("preview"));
        verify(service).changeLifecycle(anyString(), eq("c1"), eq("preview"), any(Person.class));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void changeLifecycleInvalidValueIsHttp200() {
        when(service.changeLifecycle(anyString(), anyString(), anyString(), any(Person.class)))
                .thenReturn(Uni.createFrom().failure(new IllegalArgumentException("invalid lifecycle: archive")));

        given().when().put("/matrix/v1/modbus/config/one/c1/lifecycle/archive")
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", containsString("invalid lifecycle"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void changeLifecycleWithoutOrgAllowed() {
        // 无组织个人访问：流转归 owner 判权（service 内部按 owner），Resource 不强求 X-Org-Id
        ModbusConfig preview = sample();
        preview.lifecycle = "preview";
        when(service.changeLifecycle(anyString(), eq("c1"), eq("preview"), any(Person.class)))
                .thenReturn(Uni.createFrom().item(preview));

        given().when().put("/matrix/v1/modbus/config/one/c1/lifecycle/preview")
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.lifecycle", equalTo("preview"));
    }
}
