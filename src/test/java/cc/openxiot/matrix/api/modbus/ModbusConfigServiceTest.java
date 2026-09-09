package cc.openxiot.matrix.api.modbus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import cc.openxiot.matrix.api.modbus.config.ModbusConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import cc.openxiot.common.person.Person;
import cc.openxiot.matrix.db.modbus.config.ModbusCommand;
import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.config.ModbusConfigRepository;
import cc.openxiot.matrix.db.modbus.config.ModbusDeviceType;
import io.smallrye.mutiny.Uni;

@ExtendWith(MockitoExtension.class)
class ModbusConfigServiceTest {

    @Mock
    ModbusConfigRepository repo;

    @InjectMocks
    ModbusConfigService service;

    private ModbusConfig cfg;

    @BeforeEach
    void setUp() {
        cfg = new ModbusConfig();
        cfg.orgId = "org1";
        cfg.slave.manufacturer = "开利";
        cfg.slave.model = "19XRV";
        cfg.slave.slaveId = 1;
        cfg.visibility = "private";
        cfg.slave.type = typeOf("urn:xiot-spec:device:chiller:0000A005");
    }

    /** 包一个品类 DeviceType 包装对象。 */
    private static ModbusDeviceType typeOf(String urn) {
        ModbusDeviceType t = new ModbusDeviceType();
        t.type = urn;
        return t;
    }

    /** 合法的读保持寄存器动作（默认样例）。 */
    private ModbusCommand readHolding() {
        ModbusCommand c = new ModbusCommand();
        c.name = "CHW_TEMP";
        c.index = 1;
        c.fc = "03";
        c.start = 10;
        c.quantity = 2;
        c.dataType = "float32";
        c.byteOrder = "ABCD";
        c.scale = 0.1;
        c.unit = "℃";
        return c;
    }

    private void insertOk() {
        when(repo.insert(any(ModbusConfig.class))).thenReturn(Uni.createFrom().voidItem());
    }

    @Test
    void createSetsIdAndInserts() {
        cfg.creator = Person.of("acc1", "小明");
        cfg.updater = Person.of("acc1", "小明");
        cfg.commands = List.of(readHolding());
        insertOk();

        ModbusConfig created = service.create(cfg).await().indefinitely();

        assertNotNull(created.id);
        // 创建者/更新者由上层注入，服务层仅保证生成 id 并落库
        assertEquals("acc1", created.creator.id);
        assertNotNull(created.creator.timestamp);
        assertEquals("小明", created.updater.name);
        verify(repo).insert(eq(cfg));
    }

    @Test
    void createWithoutCommandsAllowed() {
        cfg.commands = null;
        insertOk();
        assertNotNull(service.create(cfg).await().indefinitely());
    }

    @Test
    void createWithBlankOrgFails() {
        cfg.orgId = " ";
        assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
    }

    @Test
    void createWithBlankManufacturerFails() {
        cfg.slave.manufacturer = "";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("manufacturer is required", ex.getMessage());
    }

    @Test
    void createWithBlankModelFails() {
        cfg.slave.model = " ";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("model is required", ex.getMessage());
    }

    @Test
    void createWithBlankSlaveIdFails() {
        cfg.slave.slaveId = null;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("slaveId is required", ex.getMessage());
    }

    @Test
    void createWithInvalidSlaveIdFails() {
        cfg.slave.slaveId = 300;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid slaveId: 300", ex.getMessage());
    }

    @Test
    void createWithBlankVisibilityFails() {
        cfg.visibility = "";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("visibility is required", ex.getMessage());
    }

    @Test
    void createWithBlankTypeFails() {
        cfg.slave.type = null;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("type is required", ex.getMessage());
    }

    @Test
    void createWithInvalidVisibilityFails() {
        cfg.visibility = "secret";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid visibility: secret", ex.getMessage());
    }

    @Test
    void createReadHoldingAllowed() {
        cfg.commands = List.of(readHolding());
        insertOk();
        ModbusConfig created = service.create(cfg).await().indefinitely();
        assertNotNull(created);
        // index 随行落库（1 起自然数，排序/虚拟生成 action iid 依赖它）
        assertEquals(1, created.commands.get(0).index);
    }

    @Test
    void createWriteSingleCoilAndSingleRegisterAllowed() {
        ModbusCommand coil = new ModbusCommand();
        coil.name = "PumpOn";
        coil.index = 1;
        coil.fc = "05";
        coil.start = 0;
        coil.coilState = "on";
        ModbusCommand reg = new ModbusCommand();
        reg.name = "SetTemp";
        reg.index = 2;
        reg.fc = "06";
        reg.start = 20;
        reg.registerValue = 120L;
        cfg.commands = List.of(coil, reg);
        insertOk();
        assertNotNull(service.create(cfg).await().indefinitely());
    }

    @Test
    void createWriteMultipleCoilsAllowed() {
        ModbusCommand c = new ModbusCommand();
        c.name = "MultiCoil";
        c.index = 1;
        c.fc = "0F";
        c.start = 30;
        ModbusCommand.Coil c1 = new ModbusCommand.Coil();
        c1.offset = 0;
        c1.on = true;
        ModbusCommand.Coil c2 = new ModbusCommand.Coil();
        c2.offset = 1;
        c2.on = false;
        c.coils = List.of(c1, c2);
        cfg.commands = List.of(c);
        insertOk();
        assertNotNull(service.create(cfg).await().indefinitely());
    }

    @Test
    void createWriteMultipleRegistersAllowed() {
        ModbusCommand c = new ModbusCommand();
        c.name = "MultiReg";
        c.index = 1;
        c.fc = "10";
        c.start = 40;
        ModbusCommand.Register r1 = new ModbusCommand.Register();
        r1.address = 40;
        r1.dataType = "int16";
        r1.byteOrder = "ABCD";
        r1.value = -5L;
        ModbusCommand.Register r2 = new ModbusCommand.Register();
        r2.address = 41;
        r2.dataType = "uint32";
        r2.byteOrder = "DCBA";
        r2.value = 0x12345678L;
        c.registers = List.of(r1, r2);
        cfg.commands = List.of(c);
        insertOk();
        assertNotNull(service.create(cfg).await().indefinitely());
    }

    @Test
    void createWithCommandNameMissingFails() {
        ModbusCommand c = readHolding();
        c.name = null;
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("commands[0].name is required", ex.getMessage());
    }

    @Test
    void createWithInvalidFcFails() {
        ModbusCommand c = readHolding();
        c.fc = "99";
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[0].fc: 99", ex.getMessage());
    }

    @Test
    void createWithMissingStartFails() {
        ModbusCommand c = readHolding();
        c.start = null;
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("commands[0].start is required", ex.getMessage());
    }

    @Test
    void createWithQuantityMismatchTypeFails() {
        ModbusCommand c = readHolding();
        c.dataType = "uint16";
        c.quantity = 2; // uint16 只需 1 个寄存器
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[0].quantity: 2 (dataType uint16 needs 1)", ex.getMessage());
    }

    @Test
    void createWithMissingDataTypeFails() {
        ModbusCommand c = readHolding();
        c.dataType = null;
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("commands[0].dataType is required", ex.getMessage());
    }

    @Test
    void createWithInvalidByteOrderFails() {
        ModbusCommand c = readHolding();
        c.byteOrder = "XYZW";
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[0].byteOrder: XYZW", ex.getMessage());
    }

    @Test
    void createWithMissingCoilStateFails() {
        ModbusCommand c = new ModbusCommand();
        c.name = "PumpOn";
        c.index = 1;
        c.fc = "05";
        c.start = 0;
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("commands[0].coilState is required", ex.getMessage());
    }

    @Test
    void createWithOutOfRangeRegisterValueFails() {
        ModbusCommand c = new ModbusCommand();
        c.name = "SetTemp";
        c.index = 1;
        c.fc = "06";
        c.start = 20;
        c.registerValue = 70000L;
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[0].registerValue: 70000", ex.getMessage());
    }

    @Test
    void createWithEmptyCoilsFails() {
        ModbusCommand c = new ModbusCommand();
        c.name = "MultiCoil";
        c.index = 1;
        c.fc = "0F";
        c.start = 30;
        c.coils = List.of();
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("commands[0].coils is required", ex.getMessage());
    }

    @Test
    void createWithDuplicateCoilOffsetFails() {
        ModbusCommand c = new ModbusCommand();
        c.name = "MultiCoil";
        c.index = 1;
        c.fc = "0F";
        c.start = 30;
        ModbusCommand.Coil a = new ModbusCommand.Coil();
        a.offset = 0;
        a.on = true;
        ModbusCommand.Coil b = new ModbusCommand.Coil();
        b.offset = 0;
        b.on = false;
        c.coils = List.of(a, b);
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[0].coils[1].offset: duplicated 0", ex.getMessage());
    }

    @Test
    void createWithNonContiguousRegistersFails() {
        ModbusCommand c = new ModbusCommand();
        c.name = "MultiReg";
        c.index = 1;
        c.fc = "10";
        c.start = 40;
        ModbusCommand.Register r1 = new ModbusCommand.Register();
        r1.address = 40;
        r1.dataType = "int16";
        r1.byteOrder = "ABCD";
        r1.value = 1L;
        ModbusCommand.Register r2 = new ModbusCommand.Register();
        r2.address = 42; // 应接 41，跳跃不连续
        r2.dataType = "uint16";
        r2.byteOrder = "ABCD";
        r2.value = 2L;
        c.registers = List.of(r1, r2);
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[0].registers[1].address: 42 (registers must be contiguous)", ex.getMessage());
    }

    @Test
    void createWithBadRegisterValueFails() {
        ModbusCommand c = new ModbusCommand();
        c.name = "MultiReg";
        c.index = 1;
        c.fc = "10";
        c.start = 40;
        ModbusCommand.Register r1 = new ModbusCommand.Register();
        r1.address = 40;
        r1.dataType = "int16";
        r1.byteOrder = "ABCD";
        r1.value = 40000L; // int16 越界
        c.registers = List.of(r1);
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[0].registers[0].value: 40000", ex.getMessage());
    }

    @Test
    void createReadBitQuantityTooLargeFails() {
        ModbusCommand c = new ModbusCommand();
        c.name = "AlarmBit";
        c.index = 1;
        c.fc = "01";
        c.start = 0;
        c.quantity = 2001; // 01 单次最多 2000
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[0].quantity: 2001", ex.getMessage());
    }

    @Test
    void createWithCommandIndexMissingFails() {
        ModbusCommand c = readHolding();
        c.index = null;
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("commands[0].index is required", ex.getMessage());
    }

    @Test
    void createWithNonNaturalIndexFails() {
        ModbusCommand c = readHolding();
        c.index = 0;
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[0].index: 0 (index must be a natural number, 1-based)", ex.getMessage());
    }

    @Test
    void createWithDuplicateIndexFails() {
        ModbusCommand c1 = readHolding();
        ModbusCommand c2 = readHolding();
        c2.name = "ANOTHER_READ"; // 两条 index 都 = 1
        cfg.commands = List.of(c1, c2);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[1].index: 1 (duplicated)", ex.getMessage());
    }

    @Test
    void updateNotFoundFails() {
        when(repo.findByIdAndOrg("nope", "org1")).thenReturn(Uni.createFrom().nullItem());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.update("org1", "nope", cfg).await().indefinitely());
        assertEquals("modbus device config not found: nope", ex.getMessage());
    }

    @Test
    void updateMergesEditableFields() {
        ModbusConfig existing = new ModbusConfig();
        existing.id = "c1";
        existing.orgId = "org1";
        existing.slave.manufacturer = "old";
        existing.slave.model = "old-model";
        existing.slave.slaveId = 5;
        existing.visibility = "public";
        existing.creator = Person.of("acc1", "小明");
        existing.updater = Person.of("acc1", "小明");
        when(repo.findByIdAndOrg("c1", "org1")).thenReturn(Uni.createFrom().item(existing));
        when(repo.replace(any(ModbusConfig.class))).thenReturn(Uni.createFrom().voidItem());

        cfg.slave.manufacturer = "new";
        cfg.updater = Person.of("acc2", "小红");
        cfg.commands = List.of(readHolding());
        ModbusConfig updated = service.update("org1", "c1", cfg).await().indefinitely();

        assertEquals("new", updated.slave.manufacturer);
        assertEquals("19XRV", updated.slave.model);
        assertEquals(1, updated.slave.slaveId);
        assertEquals("private", updated.visibility);
        assertEquals("c1", updated.id);
        // 更新只替换 updater，creator 保持不变
        assertEquals("acc1", updated.creator.id);
        assertEquals("acc2", updated.updater.id);
        assertNotNull(updated.updater.timestamp);
        assertEquals(1, updated.commands.size());
        verify(repo).replace(eq(existing));
    }

    @Test
    void updateWithCommandsAbsentKeepsExistingCommands() {
        ModbusConfig existing = new ModbusConfig();
        existing.id = "c1";
        existing.orgId = "org1";
        existing.slave.manufacturer = "m";
        existing.slave.model = "mm";
        existing.visibility = "private";
        existing.commands = List.of(readHolding());
        existing.creator = Person.of("acc1", "小明");
        when(repo.findByIdAndOrg("c1", "org1")).thenReturn(Uni.createFrom().item(existing));
        when(repo.replace(any(ModbusConfig.class))).thenReturn(Uni.createFrom().voidItem());

        // 只改设备信息：patch.commands 为 null（请求体未带 commands 字段）
        cfg.slave.manufacturer = "new-m";
        cfg.updater = Person.of("acc2", "小红");
        ModbusConfig updated = service.update("org1", "c1", cfg).await().indefinitely();

        assertEquals("new-m", updated.slave.manufacturer);
        // 既有功能码不被整体清空
        assertEquals(1, updated.commands.size());
        assertEquals("CHW_TEMP", updated.commands.get(0).name);
    }

    @Test
    void updateWithEmptyCommandsClearsAll() {
        ModbusConfig existing = new ModbusConfig();
        existing.id = "c1";
        existing.orgId = "org1";
        existing.slave.manufacturer = "m";
        existing.slave.model = "mm";
        existing.visibility = "private";
        existing.commands = List.of(readHolding());
        existing.creator = Person.of("acc1", "小明");
        when(repo.findByIdAndOrg("c1", "org1")).thenReturn(Uni.createFrom().item(existing));
        when(repo.replace(any(ModbusConfig.class))).thenReturn(Uni.createFrom().voidItem());

        // 显式传空数组才算清空全部功能码
        cfg.updater = Person.of("acc2", "小红");
        cfg.commands = List.of();
        ModbusConfig updated = service.update("org1", "c1", cfg).await().indefinitely();

        assertEquals(0, updated.commands.size());
    }

    @Test
    void updateWithBlankModelFails() {
        ModbusConfig existing = new ModbusConfig();
        existing.id = "c1";
        existing.orgId = "org1";
        when(repo.findByIdAndOrg("c1", "org1")).thenReturn(Uni.createFrom().item(existing));

        cfg.slave.model = "";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.update("org1", "c1", cfg).await().indefinitely());
        assertEquals("model is required", ex.getMessage());
    }

    @Test
    void updateCopiesTypeWhenPresent() {
        ModbusConfig existing = new ModbusConfig();
        existing.id = "c1";
        existing.orgId = "org1";
        existing.slave.manufacturer = "m";
        existing.slave.model = "mm";
        existing.visibility = "private";
        existing.slave.type = typeOf("urn:xiot-spec:device:old:00000001");
        existing.creator = Person.of("acc1", "小明");
        when(repo.findByIdAndOrg("c1", "org1")).thenReturn(Uni.createFrom().item(existing));
        when(repo.replace(any(ModbusConfig.class))).thenReturn(Uni.createFrom().voidItem());

        cfg.updater = Person.of("acc2", "小红");
        cfg.slave.type = typeOf("urn:xiot-spec:device:chiller:0000A005");
        ModbusConfig updated = service.update("org1", "c1", cfg).await().indefinitely();

        assertEquals("urn:xiot-spec:device:chiller:0000A005", updated.slave.type.type);
    }

    @Test
    void updateWithoutTypeKeepsExisting() {
        ModbusConfig existing = new ModbusConfig();
        existing.id = "c1";
        existing.orgId = "org1";
        existing.slave.manufacturer = "m";
        existing.slave.model = "mm";
        existing.visibility = "private";
        existing.slave.type = typeOf("urn:xiot-spec:device:chiller:0000A005");
        existing.creator = Person.of("acc1", "小明");
        when(repo.findByIdAndOrg("c1", "org1")).thenReturn(Uni.createFrom().item(existing));
        when(repo.replace(any(ModbusConfig.class))).thenReturn(Uni.createFrom().voidItem());

        // 只改设备信息、请求体未带 type（旧客户端/存量配置），不清空既有 type
        cfg.slave.type = null;
        cfg.slave.manufacturer = "new-m";
        cfg.updater = Person.of("acc2", "小红");
        ModbusConfig updated = service.update("org1", "c1", cfg).await().indefinitely();

        assertEquals("urn:xiot-spec:device:chiller:0000A005", updated.slave.type.type);
    }

    @Test
    void getNotFoundFails() {
        when(repo.findVisibleById("ghost", "org1")).thenReturn(Uni.createFrom().nullItem());
        assertThrows(IllegalArgumentException.class,
                () -> service.get("org1", "ghost").await().indefinitely());
    }

    @Test
    void getBlankOrgFails() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.get(" ", "c1").await().indefinitely());
        assertEquals("orgId is required", ex.getMessage());
    }

    @Test
    void getDelegatesToVisibleLookup() {
        cfg.id = "c1";
        when(repo.findVisibleById("c1", "org1")).thenReturn(Uni.createFrom().item(cfg));
        ModbusConfig found = service.get("org1", "c1").await().indefinitely();
        assertEquals("c1", found.id);
    }

    @Test
    void getCrossOrgPublicReadable() {
        ModbusConfig pub = new ModbusConfig();
        pub.id = "c2";
        pub.orgId = "org2"; // 他组织
        pub.visibility = "public";
        when(repo.findVisibleById("c2", "org1")).thenReturn(Uni.createFrom().item(pub));
        ModbusConfig found = service.get("org1", "c2").await().indefinitely();
        assertEquals("public", found.visibility);
        assertEquals("org2", found.orgId);
    }

    @Test
    void getPublicReadable() {
        ModbusConfig pub = new ModbusConfig();
        pub.id = "c2";
        pub.orgId = "org2";
        pub.visibility = "public";
        when(repo.findPublicById("c2")).thenReturn(Uni.createFrom().item(pub));
        ModbusConfig found = service.getPublic("c2").await().indefinitely();
        assertEquals("c2", found.id);
    }

    @Test
    void getPublicNotFoundFails() {
        when(repo.findPublicById("ghost")).thenReturn(Uni.createFrom().nullItem());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.getPublic("ghost").await().indefinitely());
        assertEquals("modbus device config not found: ghost", ex.getMessage());
    }

    @Test
    void getPublicBlankIdFails() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.getPublic(" ").await().indefinitely());
        assertEquals("id is required", ex.getMessage());
    }

    @Test
    void listByOrgDelegates() {
        when(repo.listByOrg("org1")).thenReturn(Uni.createFrom().item(List.of(cfg)));
        assertEquals(1, service.listByOrg("org1").await().indefinitely().size());
    }

    @Test
    void listByOrgBlankFails() {
        assertThrows(IllegalArgumentException.class,
                () -> service.listByOrg(" ").await().indefinitely());
    }

    @Test
    void listPublicDelegates() {
        when(repo.listPublic()).thenReturn(Uni.createFrom().item(List.of(cfg)));
        assertEquals(1, service.listPublic().await().indefinitely().size());
    }

    @Test
    void listVisibleDelegates() {
        when(repo.listVisible("org1")).thenReturn(Uni.createFrom().item(List.of(cfg)));
        assertEquals(1, service.listVisible("org1").await().indefinitely().size());
    }

    @Test
    void listVisibleBlankOrgFails() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.listVisible(" ").await().indefinitely());
        assertEquals("orgId is required", ex.getMessage());
    }

    @Test
    void deleteZeroFailsAsNotFound() {
        when(repo.deleteByIdAndOrg("c1", "org1")).thenReturn(Uni.createFrom().item(0L));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.delete("org1", "c1").await().indefinitely());
        assertEquals("modbus device config not found: c1", ex.getMessage());
    }

    @Test
    void deleteDelegates() {
        when(repo.deleteByIdAndOrg("c1", "org1")).thenReturn(Uni.createFrom().item(1L));
        assertEquals(1L, service.delete("org1", "c1").await().indefinitely());
    }
}
