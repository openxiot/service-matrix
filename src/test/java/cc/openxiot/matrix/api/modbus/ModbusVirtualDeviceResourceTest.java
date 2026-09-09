package cc.openxiot.matrix.api.modbus;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import cc.openxiot.matrix.api.modbus.config.ModbusConfigService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import cc.openxiot.common.person.Person;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.db.modbus.config.ModbusCommand;
import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.config.ModbusDeviceType;
import cc.openxiot.matrix.db.modbus.virtual.ModbusVirtualDevice;
import cc.openxiot.matrix.db.modbus.virtual.ModbusVirtualDeviceRepository;
import cc.openxiot.matrix.service.orgcheck.OrgAccessService;
import cn.geekcity.xiot.spec.codec.vertx.instance.DeviceInstanceCodec;
import cn.geekcity.xiot.spec.definition.urn.DeviceType;
import cn.geekcity.xiot.spec.instance.DeviceInstance;
import cn.geekcity.xiot.spec.instance.Service;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;

@QuarkusTest
class ModbusVirtualDeviceResourceTest {

    @InjectMock
    ModbusConfigService configService;

    @InjectMock
    ModbusVirtualDeviceRepository repo;

    @InjectMock
    OrgAccessService orgAccessService;

    /** 一条带 type 的点表配置：1 条读(03/float32) + 1 条写(06)。 */
    private ModbusConfig sample() {
        ModbusConfig cfg = new ModbusConfig();
        cfg.id = "c1";
        cfg.orgId = "org1";
        cfg.slave.manufacturer = "开利";
        cfg.slave.model = "19XRV";
        cfg.slave.slaveId = 1;
        cfg.visibility = "private";
        ModbusDeviceType deviceType = new ModbusDeviceType();
        deviceType.type = "urn:xiot-spec:device:chiller:0000A005";
        cfg.slave.type = deviceType;
        cfg.slave.description = "某冷水机组点表";

        ModbusCommand read = new ModbusCommand();
        read.name = "读取回水温度";
        read.index = 1;
        read.fc = "03";
        read.start = 0;
        read.quantity = 2;
        read.dataType = "float32";
        read.byteOrder = "ABCD";
        read.unit = "℃";

        ModbusCommand write = new ModbusCommand();
        write.name = "启停";
        write.index = 3; // 故意留 1 号空洞：action iid 必须等于 index，而非按出现顺序
        write.fc = "06";
        write.start = 0;
        write.registerValue = 1L;

        cfg.commands = List.of(read, write);
        return cfg;
    }

    private void allowMember() {
        when(orgAccessService.checkMember(anyString(), anyString())).thenReturn(Uni.createFrom().voidItem());
    }

    private void allowConfig(ModbusConfig cfg) {
        when(configService.get(anyString(), anyString())).thenReturn(Uni.createFrom().item(cfg));
    }

    @Test
    void helloIsPublic() {
        given().when().get("/matrix/v1/modbus/virtual")
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data", equalTo("Hello from ModbusVirtualDeviceResource"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void missingOrgHeaderFails() {
        given().contentType(ContentType.JSON)
                .body("{\"configId\":\"c1\",\"did\":\"p1\",\"siid\":1,\"aiid\":1}")
                .when().post("/matrix/v1/modbus/virtual/one")
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", containsString("X-Org-Id"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void createValidationErrorIsHttp200() {
        allowMember();
        given().header("X-Org-Id", "org1")
                .contentType(ContentType.JSON)
                .body("{\"configId\":\"c1\",\"did\":\"p1\",\"siid\":0,\"aiid\":2}")
                .when().post("/matrix/v1/modbus/virtual/one")
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", containsString("siid"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void configNotFoundFails() {
        allowMember();
        when(configService.get(anyString(), anyString()))
                .thenReturn(Uni.createFrom().failure(new IllegalArgumentException("modbus device config not found: nope")));

        given().header("X-Org-Id", "org1")
                .contentType(ContentType.JSON)
                .body("{\"configId\":\"nope\",\"did\":\"p1\",\"siid\":1,\"aiid\":1}")
                .when().post("/matrix/v1/modbus/virtual/one")
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", containsString("not found"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void configTypeMissingFails() {
        allowMember();
        ModbusConfig cfg = sample();
        cfg.slave.type = null;
        allowConfig(cfg);

        given().header("X-Org-Id", "org1")
                .contentType(ContentType.JSON)
                .body("{\"configId\":\"c1\",\"did\":\"p1\",\"siid\":1,\"aiid\":1}")
                .when().post("/matrix/v1/modbus/virtual/one")
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", containsString("select a device type"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void createOkGeneratesDefinitionAndPersists() {
        allowMember();
        allowConfig(sample());
        when(repo.insert(any(ModbusVirtualDevice.class))).thenReturn(Uni.createFrom().voidItem());

        String virtualDeviceId = given().header("X-Org-Id", "org1")
                .contentType(ContentType.JSON)
                .body("{\"configId\":\"c1\",\"did\":\"parent-device-1\",\"siid\":3,\"aiid\":2}")
                .when().post("/matrix/v1/modbus/virtual/one")
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.virtualDeviceId", notNullValue())
                // 派生类型：继承点表 type，追加 vendor(父 did) + model(配置 id) + version=1，全小写
                .body("data.deviceSummary.type",
                        equalTo("urn:xiot-spec:device:chiller:0000a005:parent-device-1:c1:1"))
                .body("data.deviceSummary.did", notNullValue())
                .body("data.deviceSummary.online", equalTo(false))
                .body("data.deviceSummary.protocol", equalTo("modbus"))
                .body("data.deviceSummary.rootId", equalTo("parent-device-1"))
                .body("data.deviceSummary.parentId", equalTo("parent-device-1"))
                .body("data.mapping.configId", equalTo("c1"))
                .body("data.mapping.did", equalTo("parent-device-1"))
                .body("data.mapping.siid", equalTo(3))
                .body("data.mapping.aiid", equalTo(2))
                .body("data.definition.services[0].properties", notNullValue())
                .extract().path("data.virtualDeviceId");

        assertNotNull(virtualDeviceId);

        // 落库实体校验
        ArgumentCaptor<ModbusVirtualDevice> captor = ArgumentCaptor.forClass(ModbusVirtualDevice.class);
        verify(repo).insert(captor.capture());
        ModbusVirtualDevice saved = captor.getValue();
        assertEquals(virtualDeviceId, saved.did);
        assertEquals("org1", saved.orgId);
        assertNotNull(saved.mapping);
        assertEquals("c1", saved.mapping.configId);
        assertEquals("parent-device-1", saved.mapping.did);
        assertEquals(3, saved.mapping.siid);
        assertEquals(2, saved.mapping.aiid);
        assertNotNull(saved.instance);
        assertEquals("某冷水机组点表", saved.instance.description);
        assertEquals("urn:xiot-spec:device:chiller:0000a005:parent-device-1:c1:1", saved.instance.type);
        assertNotNull(saved.instance.definition);
        assertEquals("tester", saved.creator.id);
        assertNotNull(saved.creator.timestamp);

        // 定义可经 DeviceInstanceCodec 回环解码
        DeviceInstance decoded = DeviceInstanceCodec.decode(new JsonObject(saved.instance.definition));
        assertEquals(DeviceType.parse(saved.instance.type), decoded.type());
        assertEquals(1, decoded.services().size());
        Service svc = decoded.services().values().iterator().next();
        // 1 条读命令 → 1 个只读 backing property
        assertEquals(1, svc.properties().size());
        assertEquals(1, svc.properties().get(1).iid());
        // 2 条命令 → 2 个 action；action iid 必须 = command.index（1 与 3，含空洞），而非数组顺序 1/2
        assertEquals(2, svc.actions().size());
        assertEquals(Set.of(1, 3), svc.actions().keySet());
        assertTrue(svc.actions().values().stream().anyMatch(a -> a.out().containsKey(1)),
                "read command action should reference backing property via out");
    }
}
