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
import cc.openxiot.matrix.api.device.DeviceService;
import cc.openxiot.matrix.db.device.DeviceEntity;
import cc.openxiot.matrix.db.device.DeviceSpace;
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
import org.bson.types.ObjectId;

@QuarkusTest
class ModbusVirtualDeviceResourceTest {

    /** 点表配置的落库主键（系统生成的 ObjectId）。 */
    private static final ObjectId CID = new ObjectId("6512f3a4b5c6d7e8f9a0b1c2");

    @InjectMock
    ModbusConfigService configService;

    @InjectMock
    ModbusVirtualDeviceRepository repo;

    @InjectMock
    DeviceService deviceService;

    @InjectMock
    OrgAccessService orgAccessService;

    /** 一条带 type 的点表配置：1 条读(03/float32) + 1 条写(06)。 */
    private ModbusConfig sample() {
        ModbusConfig cfg = new ModbusConfig();
        // 点表主键由系统生成（ObjectId）；派生类型的 model 段取映射请求里的 configId（见各用例请求体）
        cfg.id = CID;
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

    /** createOne / deleteOne 现走 {@link OrgAccessService#checkAdmin}（组织管理员）门槛。 */
    private void allowAdmin() {
        when(orgAccessService.checkAdmin(anyString(), anyString())).thenReturn(Uni.createFrom().voidItem());
    }

    /** 一条可解码回环的虚拟设备定义文档（instance.type 精确可查），供 getInstance 返回。 */
    private ModbusVirtualDevice instanceDoc(String type) {
        DeviceInstance di = new DeviceInstance(DeviceType.parse(type));
        ModbusVirtualDevice v = new ModbusVirtualDevice();
        v.did = "virt-1";
        v.instance.type = type;
        v.instance.definition = DeviceInstanceCodec.encode(di).toString();
        return v;
    }

    private void allowConfig(ModbusConfig cfg) {
        when(configService.get(anyString(), anyString())).thenReturn(Uni.createFrom().item(cfg));
    }

    /** 父设备（matrix DeviceEntity）：作为树根（rootId 空）+ 所在空间，供虚拟子设备挂载 / 复制 space。 */
    private void allowParentDevice() {
        allowParentDevice(false);
    }

    /** 带父设备 online 状态重载：虚拟子设备 online 起步即镜像父设备。 */
    private void allowParentDevice(boolean parentOnline) {
        DeviceEntity parent = new DeviceEntity();
        parent.did = "parent-device-1";
        parent.type = "urn:xiot-spec:device:gateway:0000A001";
        parent.online = parentOnline;
        DeviceSpace space = new DeviceSpace();
        space.spaceId = "space-1";
        space.rootId = "root-1";
        parent.space = space;
        when(deviceService.getByDeviceId("parent-device-1")).thenReturn(Uni.createFrom().item(parent));
    }

    /** save 回写同一个 DeviceEntity 实例（persistOrUpdate 语义）。 */
    private void allowSave() {
        when(deviceService.save(any(DeviceEntity.class)))
                .thenAnswer(invocation -> Uni.createFrom().item((DeviceEntity) invocation.getArgument(0)));
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
        allowAdmin();
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
        allowAdmin();
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
        allowAdmin();
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
    void createOkRegistersChildDeviceAndPersistsDefinition() {
        allowAdmin();
        allowConfig(sample());
        when(repo.insert(any(ModbusVirtualDevice.class))).thenReturn(Uni.createFrom().voidItem());
        allowParentDevice();
        allowSave();

        String did = given().header("X-Org-Id", "org1")
                .contentType(ContentType.JSON)
                .body("{\"configId\":\"c1\",\"did\":\"parent-device-1\",\"siid\":3,\"aiid\":2}")
                .when().post("/matrix/v1/modbus/virtual/one")
                .then().statusCode(200)
                .body("success", equalTo(true))
                // createOne 现在直接返回创建的矩阵 DeviceEntity
                .body("data.did", notNullValue())
                // 派生类型：继承点表 type，追加 vendor(父 did) + model(配置 id) + version=1，全小写
                .body("data.type", equalTo("urn:xiot-spec:device:chiller:0000a005:parent-device-1:c1:1"))
                .body("data.online", equalTo(false))
                .body("data.protocol", equalTo("modbus"))
                // 挂在父设备下；父为树根 → rootId = 父 did
                .body("data.parentId", equalTo("parent-device-1"))
                .body("data.rootId", equalTo("parent-device-1"))
                .body("data.space.spaceId", equalTo("space-1"))
                .body("data.space.rootId", equalTo("root-1"))
                .extract().path("data.did");

        assertNotNull(did);

        // 虚拟设备定义文档仍按原样落库（映射信息 + 派生类型 + 编码定义）
        ArgumentCaptor<ModbusVirtualDevice> vCaptor = ArgumentCaptor.forClass(ModbusVirtualDevice.class);
        verify(repo).insert(vCaptor.capture());
        ModbusVirtualDevice saved = vCaptor.getValue();
        assertEquals(did, saved.did);
        assertEquals("org1", saved.orgId);
        assertNotNull(saved.mapping);
        assertEquals("c1", saved.mapping.configId);
        assertEquals("parent-device-1", saved.mapping.did);
        assertEquals(3, saved.mapping.siid);
        assertEquals(2, saved.mapping.aiid);
        assertEquals("某冷水机组点表", saved.instance.description);
        assertEquals("urn:xiot-spec:device:chiller:0000a005:parent-device-1:c1:1", saved.instance.type);
        assertNotNull(saved.instance.definition);
        assertEquals("tester", saved.creator.id);
        assertNotNull(saved.creator.timestamp);

        // 矩阵 DeviceEntity 子设备：复制父设备所在空间，挂在父 did 下
        ArgumentCaptor<DeviceEntity> eCaptor = ArgumentCaptor.forClass(DeviceEntity.class);
        verify(deviceService).save(eCaptor.capture());
        DeviceEntity child = eCaptor.getValue();
        assertEquals(did, child.did);
        assertEquals("urn:xiot-spec:device:chiller:0000a005:parent-device-1:c1:1", child.type);
        assertEquals(false, child.online);
        assertEquals("modbus", child.protocol);
        assertEquals("parent-device-1", child.parentId);
        assertEquals("parent-device-1", child.rootId);
        assertNotNull(child.space);
        assertEquals("space-1", child.space.spaceId);
        assertEquals("root-1", child.space.rootId);

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

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void createChildOnlineMirrorsParentOnline() {
        allowAdmin();
        allowConfig(sample());
        when(repo.insert(any(ModbusVirtualDevice.class))).thenReturn(Uni.createFrom().voidItem());
        allowParentDevice(true); // 父设备在线 → 新建虚拟子设备在线，而非固定离线
        allowSave();

        given().header("X-Org-Id", "org1")
                .contentType(ContentType.JSON)
                .body("{\"configId\":\"c1\",\"did\":\"parent-device-1\",\"siid\":1,\"aiid\":1}")
                .when().post("/matrix/v1/modbus/virtual/one")
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.online", equalTo(true))
                .body("data.protocol", equalTo("modbus"))
                .body("data.parentId", equalTo("parent-device-1"));

        ArgumentCaptor<DeviceEntity> eCaptor = ArgumentCaptor.forClass(DeviceEntity.class);
        verify(deviceService).save(eCaptor.capture());
        assertEquals(true, eCaptor.getValue().online);
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void createParentDeviceMissingFails() {
        allowAdmin();
        allowConfig(sample());
        when(deviceService.getByDeviceId("parent-device-1")).thenReturn(Uni.createFrom().nullItem());

        given().header("X-Org-Id", "org1")
                .contentType(ContentType.JSON)
                .body("{\"configId\":\"c1\",\"did\":\"parent-device-1\",\"siid\":1,\"aiid\":1}")
                .when().post("/matrix/v1/modbus/virtual/one")
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", containsString("parent device not found"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void deleteRequiresOrgHeaderFails() {
        given().queryParam("did", "virt-1")
                .when().delete("/matrix/v1/modbus/virtual/one")
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", containsString("X-Org-Id"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void deleteNotFoundFails() {
        allowAdmin();
        when(repo.findByIdAndOrg("virt-1", "org1")).thenReturn(Uni.createFrom().nullItem());

        given().header("X-Org-Id", "org1")
                .queryParam("did", "virt-1")
                .when().delete("/matrix/v1/modbus/virtual/one")
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", containsString("not found"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void deleteOkRemovesDefinitionDocAndMatrixChild() {
        allowAdmin();
        ModbusVirtualDevice v = new ModbusVirtualDevice();
        v.did = "virt-1";
        v.orgId = "org1";
        when(repo.findByIdAndOrg("virt-1", "org1")).thenReturn(Uni.createFrom().item(v));
        // 矩阵子设备可能已被先行删除（条数 0），不应视为失败：定义文档仍须删除
        when(deviceService.deleteByDeviceIds(List.of("virt-1"))).thenReturn(Uni.createFrom().item(0L));
        when(repo.deleteByIdAndOrg("virt-1", "org1")).thenReturn(Uni.createFrom().item(1L));

        given().header("X-Org-Id", "org1")
                .queryParam("did", "virt-1")
                .when().delete("/matrix/v1/modbus/virtual/one")
                .then().statusCode(200)
                .body("success", equalTo(true));

        // 镜像 createOne：同 did 的矩阵 DeviceEntity 子设备 + devices 定义文档都删
        verify(deviceService).deleteByDeviceIds(List.of("virt-1"));
        verify(repo).deleteByIdAndOrg("virt-1", "org1");
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void deleteCrossOrgIsRejected() {
        allowAdmin();
        // 定义文档归属 org2；以 org1 身份删除 → 查不到（org 约束防跨组织删除）
        when(repo.findByIdAndOrg("virt-1", "org1")).thenReturn(Uni.createFrom().nullItem());

        given().header("X-Org-Id", "org1")
                .queryParam("did", "virt-1")
                .when().delete("/matrix/v1/modbus/virtual/one")
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", containsString("not found"));
    }

    // ===== getInstance：公开只读，按派生 DeviceType 取实例定义 =====

    @Test
    void getInstanceNotFoundFails() {
        String type = "urn:xiot-spec:device:chiller:0000a005:parent-device-1:c1:1";
        when(repo.findFirstByInstanceType(type)).thenReturn(Uni.createFrom().nullItem());

        given().when().get("/matrix/v1/modbus/virtual/instance/" + type)
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", containsString("not found"));
    }

    @Test
    void getInstanceOkReturnsDecodedInstance() {
        String type = "urn:xiot-spec:device:chiller:0000a005:parent-device-1:c1:1";
        when(repo.findFirstByInstanceType(type)).thenReturn(Uni.createFrom().item(instanceDoc(type)));

        given().when().get("/matrix/v1/modbus/virtual/instance/" + type)
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data", notNullValue());
    }
}
