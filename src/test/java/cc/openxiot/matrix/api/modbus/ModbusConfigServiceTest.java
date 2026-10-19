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
import cc.openxiot.matrix.db.modbus.config.ModbusConfigOwner;
import cc.openxiot.matrix.db.modbus.config.ModbusConfigRepository;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.service.orgcheck.OrgAccessService;
import io.smallrye.mutiny.Uni;
import org.bson.types.ObjectId;

@ExtendWith(MockitoExtension.class)
class ModbusConfigServiceTest {

    /**
     * 实体的落库主键（系统生成的 ObjectId）。仓储对外方法签名仍是字符串（内部转 ObjectId），
     * 所以下面桩里那些 "c1" / "nope" 只是 mock 的入参 key，与实体 id 类型无关。
     */
    private static final ObjectId CID = new ObjectId("6512f3a4b5c6d7e8f9a0b1c2");
    /** 测试用的当前账号（userId，authorize* 的第一个入参）。 */
    private static final String USER = "u1";

    @Mock
    ModbusConfigRepository repo;

    @Mock
    OrgAccessService orgAccess;

    @InjectMocks
    ModbusConfigService service;

    private ModbusConfig cfg;

    @BeforeEach
    void setUp() {
        cfg = new ModbusConfig();
        cfg.owner = ModbusConfigOwner.ofOrg("org1", "第一个组织");
        cfg.slave.manufacturer = "开利";
        cfg.slave.model = "19XRV";
        cfg.slave.slaveId = 1;
        cfg.visibility = "private";
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
        return c;
    }

    /** 合法的读线圈动作（01 读位，逐位命名见 bitNames）。 */
    private ModbusCommand readCoil() {
        ModbusCommand c = new ModbusCommand();
        c.name = "读开关状态";
        c.index = 1;
        c.fc = "01";
        c.start = 0;
        c.quantity = 8;
        return c;
    }

    private static ModbusCommand.BitName bit(int offset, String name) {
        ModbusCommand.BitName b = new ModbusCommand.BitName();
        b.offset = offset;
        b.name = name;
        return b;
    }

    private static ModbusCommand.Field field(String name) {
        ModbusCommand.Field f = new ModbusCommand.Field();
        f.name = name;
        return f;
    }

    /** 寄存器序号（供 ModbusRegistry.index）：同表内必须唯一，用个计数器保证。 */
    private static int registrySeq;

    /** 寄存器样例：跨多寄存器类型（32/64 位）自动补 byteOrder，其余免填。 */
    private static ModbusRegistry registry(String area, int address, String access, String dataType, String name) {
        ModbusRegistry r = new ModbusRegistry();
        r.name = name;
        r.index = ++registrySeq;
        r.address = address;
        r.area = area;
        r.access = access;
        r.dataType = dataType;
        if (dataType != null && List.of("int32", "uint32", "float32", "int64", "uint64").contains(dataType)) {
            r.byteOrder = "ABCD";
        }
        return r;
    }

    // ===== 寄存器（registries）校验 =====

    /** 以寄存器为中心的点表：带 registries 也能建。 */
    @Test
    void createWithRegistriesSucceeds() {
        cfg.registries = List.of(
                registry("holding", 0, "R", "float32", "进水温度"),
                registry("holding", 2, "RW", "uint16", "开度"),
                registry("coil", 0, "RW", null, "启停"));
        insertOk();

        assertEquals(CID, service.create(cfg).await().indefinitely().id);
    }

    /** 同一 (area, address) 重复：一个物理位只出现一次。 */
    @Test
    void createWithDuplicatedRegistryAddressFails() {
        cfg.registries = List.of(
                registry("holding", 0, "R", "float32", "进水温度"),
                registry("holding", 0, "R", "float32", "重复点"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid registries[1]: duplicated (area holding, address 0)", ex.getMessage());
    }

    /** 区不在四区内。 */
    @Test
    void createWithInvalidRegistryAreaFails() {
        cfg.registries = List.of(registry("bogus", 0, "R", "uint16", "x"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid registries[0].area: bogus", ex.getMessage());
    }

    /** 32 位跨 2 寄存器：byteOrder 必填。 */
    @Test
    void createWithRegistryMultiRegisterMissingByteOrderFails() {
        ModbusRegistry r = registry("holding", 0, "R", "float32", "进水温度");
        r.byteOrder = null;
        cfg.registries = List.of(r);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("registries[0].byteOrder is required for dataType float32", ex.getMessage());
    }

    /** 读写方向缺失。 */
    @Test
    void createWithRegistryMissingAccessFails() {
        ModbusRegistry r = registry("holding", 0, "R", "uint16", "x");
        r.access = null;
        cfg.registries = List.of(r);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("registries[0].access is required", ex.getMessage());
    }

    /** 应答字段名重复：会互为覆盖，拒绝。 */
    @Test
    void createWithDuplicateFieldNamesFails() {
        ModbusCommand c = readHolding();
        c.fields = List.of(field("温度"), field("温度"));
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[0].fields[1].name: 温度 duplicated", ex.getMessage());
    }

    /** 桩：模拟仓储落库时由 Panache 生成主键（服务层不再自造 id）。 */
    private void insertOk() {
        when(repo.insert(any(ModbusConfig.class))).thenAnswer(inv -> {
            inv.<ModbusConfig>getArgument(0).id = CID;
            return Uni.createFrom().voidItem();
        });
    }

    /** 桩：当前账号 {@link #USER} 在 org1 是成员（authorizeOwner 的组织归属鉴权路径）。 */
    private void authOrgOk() {
        when(orgAccess.checkMember("org1", USER)).thenReturn(Uni.createFrom().voidItem());
    }

    @Test
    void createInsertsAndKeepsGeneratedId() {
        cfg.creator = Person.of("acc1", "小明");
        cfg.updater = Person.of("acc1", "小明");
        cfg.commands = List.of(readHolding());
        insertOk();

        ModbusConfig created = service.create(cfg).await().indefinitely();

        // id 由仓储（Panache）生成；服务层只负责落库并把生成的 id 随返回体带出
        assertEquals(CID, created.id);
        // 创建者/更新者由上层注入
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
    void createWithBlankOwnerIdFails() {
        cfg.owner.id = " ";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("owner.id is required", ex.getMessage());
    }

    @Test
    void createWithInvalidOwnerTypeFails() {
        cfg.owner.type = "department";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid owner.type: department", ex.getMessage());
    }

    @Test
    void createWithUserOwnerAllowed() {
        cfg.owner = ModbusConfigOwner.ofUser(USER, "小明");
        cfg.commands = List.of(readHolding());
        insertOk();
        assertEquals(CID, service.create(cfg).await().indefinitely().id);
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

    /** 数量是「值的个数」：uint16 读 3 个值 = 3 个寄存器，合法。 */
    @Test
    void createWithMultipleUint16ValuesSucceeds() {
        ModbusCommand c = readHolding();
        c.dataType = "uint16";
        c.quantity = 3;
        c.fields = List.of(field("第一个温度值"), field("第二个温度值"), field("第三个温度值"));
        cfg.commands = List.of(c);
        insertOk();

        assertEquals(CID, service.create(cfg).await().indefinitely().id);
    }

    /** 数量 × 类型跨度 = 实际要读的寄存器数，超过 125 个即非法。 */
    @Test
    void createWithTooManyRegistersFails() {
        ModbusCommand c = readHolding();
        c.quantity = 63; // float32 → 126 个寄存器
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[0].quantity: 63 (dataType float32 reads 126 registers)", ex.getMessage());
    }

    @Test
    void createWithFieldNameCountMismatchFails() {
        ModbusCommand c = readHolding();
        c.dataType = "uint16";
        c.quantity = 3;
        c.fields = List.of(field("第一个温度值"), field("第二个温度值")); // 3 个值应有 3 个名称
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[0].fields: 2 (fc 03 needs 3)", ex.getMessage());
    }

    @Test
    void createWithBlankFieldNameFails() {
        ModbusCommand c = readHolding();
        c.fields = List.of(field("进水温度"), field(" "));
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("commands[0].fields[1].name is required", ex.getMessage());
    }

    /** string 整段算一个值：无论数量多少，应答只有一个字段。 */
    @Test
    void createWithStringReadNeedsOneFieldName() {
        ModbusCommand c = readHolding();
        c.dataType = "string";
        c.quantity = 5;
        c.fields = List.of(field("设备型号"), field("备用"));
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[0].fields: 2 (fc 03 needs 1)", ex.getMessage());
    }

    @Test
    void createWithFieldNamesOnWriteFails() {
        ModbusCommand c = new ModbusCommand();
        c.name = "开机";
        c.index = 1;
        c.fc = "05";
        c.start = 0;
        c.coilState = "on";
        c.fields = List.of(field("开关状态"));
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("commands[0].fields is not allowed: fc 05 has no response fields", ex.getMessage());
    }

    /** 01/02 读位：逐位命名走 bitNames（offset 0 基），可以只命名其中几位。 */
    @Test
    void createWithBitNamesSucceeds() {
        ModbusCommand c = readCoil();
        c.bitNames = List.of(bit(0, "进水阀"), bit(3, "报警"));
        cfg.commands = List.of(c);
        insertOk();

        assertEquals(CID, service.create(cfg).await().indefinitely().id);
    }

    /** 位偏移必须落在读回的位区间内。 */
    @Test
    void createWithBitNameOffsetOutOfRangeFails() {
        ModbusCommand c = readCoil();
        c.bitNames = List.of(bit(8, "越界位")); // 数量 8 → 有效偏移 0..7
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[0].bitNames[0].offset: 8 (quantity 8)", ex.getMessage());
    }

    @Test
    void createWithDuplicatedBitNameOffsetFails() {
        ModbusCommand c = readCoil();
        c.bitNames = List.of(bit(0, "进水阀"), bit(0, "出水阀"));
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("invalid commands[0].bitNames[1].offset: 0 (duplicated)", ex.getMessage());
    }

    @Test
    void createWithBlankBitNameFails() {
        ModbusCommand c = readCoil();
        c.bitNames = List.of(bit(0, " "));
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("commands[0].bitNames[0].name is required", ex.getMessage());
    }

    /** 01/02 的应答字段只有一个（整段位掩码），字段名不再由点表给（生成时按功能名称兜底）。 */
    @Test
    void createWithFieldsOnBitReadFails() {
        ModbusCommand c = readCoil();
        c.fields = List.of(field("开关状态"));
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("commands[0].fields is not allowed: fc 01 names each bit via bitNames", ex.getMessage());
    }

    @Test
    void createWithBitNamesOnRegisterReadFails() {
        ModbusCommand c = readHolding();
        c.bitNames = List.of(bit(0, "进水阀"));
        cfg.commands = List.of(c);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cfg).await().indefinitely());
        assertEquals("commands[0].bitNames is not allowed: fc 03 has no bit response", ex.getMessage());
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
        when(repo.findById("nope")).thenReturn(Uni.createFrom().nullItem());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.update(USER, "nope", cfg).await().indefinitely());
        assertEquals("modbus device config not found", ex.getMessage());
    }

    @Test
    void updateMergesEditableFields() {
        ModbusConfig existing = new ModbusConfig();
        existing.id = CID;
        existing.owner = ModbusConfigOwner.ofOrg("org1", "第一个组织");
        existing.slave.manufacturer = "old";
        existing.slave.model = "old-model";
        existing.slave.slaveId = 5;
        existing.visibility = "public";
        existing.creator = Person.of("acc1", "小明");
        existing.updater = Person.of("acc1", "小明");
        authOrgOk();
        when(repo.findById("c1")).thenReturn(Uni.createFrom().item(existing));
        when(repo.replace(any(ModbusConfig.class))).thenReturn(Uni.createFrom().voidItem());

        cfg.slave.manufacturer = "new";
        cfg.updater = Person.of("acc2", "小红");
        cfg.commands = List.of(readHolding());
        ModbusConfig updated = service.update(USER, "c1", cfg).await().indefinitely();

        assertEquals("new", updated.slave.manufacturer);
        assertEquals("19XRV", updated.slave.model);
        assertEquals(1, updated.slave.slaveId);
        assertEquals("private", updated.visibility);
        assertEquals(CID, updated.id);
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
        existing.id = CID;
        existing.owner = ModbusConfigOwner.ofOrg("org1", "第一个组织");
        existing.slave.manufacturer = "m";
        existing.slave.model = "mm";
        existing.visibility = "private";
        existing.commands = List.of(readHolding());
        existing.creator = Person.of("acc1", "小明");
        authOrgOk();
        when(repo.findById("c1")).thenReturn(Uni.createFrom().item(existing));
        when(repo.replace(any(ModbusConfig.class))).thenReturn(Uni.createFrom().voidItem());

        // 只改设备信息：patch.commands 为 null（请求体未带 commands 字段）
        cfg.slave.manufacturer = "new-m";
        cfg.updater = Person.of("acc2", "小红");
        ModbusConfig updated = service.update(USER, "c1", cfg).await().indefinitely();

        assertEquals("new-m", updated.slave.manufacturer);
        // 既有功能码不被整体清空
        assertEquals(1, updated.commands.size());
        assertEquals("CHW_TEMP", updated.commands.get(0).name);
    }

    @Test
    void updateWithEmptyCommandsClearsAll() {
        ModbusConfig existing = new ModbusConfig();
        existing.id = CID;
        existing.owner = ModbusConfigOwner.ofOrg("org1", "第一个组织");
        existing.slave.manufacturer = "m";
        existing.slave.model = "mm";
        existing.visibility = "private";
        existing.commands = List.of(readHolding());
        existing.creator = Person.of("acc1", "小明");
        authOrgOk();
        when(repo.findById("c1")).thenReturn(Uni.createFrom().item(existing));
        when(repo.replace(any(ModbusConfig.class))).thenReturn(Uni.createFrom().voidItem());

        // 显式传空数组才算清空全部功能码
        cfg.updater = Person.of("acc2", "小红");
        cfg.commands = List.of();
        ModbusConfig updated = service.update(USER, "c1", cfg).await().indefinitely();

        assertEquals(0, updated.commands.size());
    }

    @Test
    void updateWithBlankModelFails() {
        ModbusConfig existing = new ModbusConfig();
        existing.id = CID;
        existing.owner = ModbusConfigOwner.ofOrg("org1", "第一个组织");
        authOrgOk();
        when(repo.findById("c1")).thenReturn(Uni.createFrom().item(existing));

        cfg.slave.model = "";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.update(USER, "c1", cfg).await().indefinitely());
        assertEquals("model is required", ex.getMessage());
    }

    @Test
    void updateCrossOrgOwnerForbiddenAsNotFound() {
        ModbusConfig existing = new ModbusConfig();
        existing.id = CID;
        existing.owner = ModbusConfigOwner.ofOrg("org2", "第二个组织"); // 他组织
        existing.slave.manufacturer = "m";
        existing.slave.model = "mm";
        existing.visibility = "private";
        when(repo.findById("c1")).thenReturn(Uni.createFrom().item(existing));
        // 非本组织成员：ORG2 成员校验失败 → 一律回 not found（不透出「Not a member」）
        when(orgAccess.checkMember("org2", USER))
                .thenReturn(Uni.createFrom().failure(new IllegalArgumentException("Not a member of the organization")));

        cfg.slave.manufacturer = "new";
        cfg.updater = Person.of("acc2", "小红");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.update(USER, "c1", cfg).await().indefinitely());
        assertEquals("modbus device config not found", ex.getMessage());
    }

    @Test
    void updateUserOwnerPassesWithoutOrgCheck() {
        ModbusConfig existing = new ModbusConfig();
        existing.id = CID;
        existing.owner = ModbusConfigOwner.ofUser(USER, "小明");
        existing.slave.manufacturer = "old";
        existing.slave.model = "mm";
        existing.visibility = "private";
        when(repo.findById("c1")).thenReturn(Uni.createFrom().item(existing));
        when(repo.replace(any(ModbusConfig.class))).thenReturn(Uni.createFrom().voidItem());

        cfg.slave.manufacturer = "new";
        cfg.updater = Person.of("acc2", "小红");
        cfg.commands = List.of(readHolding());
        ModbusConfig updated = service.update(USER, "c1", cfg).await().indefinitely();
        assertEquals("new", updated.slave.manufacturer);
        // user 归属只认本人，不查组织成员（无组织个人访问）
        verify(orgAccess, org.mockito.Mockito.times(0)).checkMember(eq(existing.owner.id), eq(USER));
    }

    @Test
    void getNotFoundFails() {
        when(repo.findById("ghost")).thenReturn(Uni.createFrom().nullItem());
        assertThrows(IllegalArgumentException.class,
                () -> service.get(USER, "ghost").await().indefinitely());
    }

    @Test
    void getBlankIdFails() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.get(USER, " ").await().indefinitely());
        assertEquals("id is required", ex.getMessage());
    }

    @Test
    void getDelegatesToVisibleLookup() {
        cfg.id = CID;
        authOrgOk();
        when(repo.findById("c1")).thenReturn(Uni.createFrom().item(cfg));
        ModbusConfig found = service.get(USER, "c1").await().indefinitely();
        assertEquals(CID, found.id);
    }

    @Test
    void getCrossOrgPublicReadable() {
        ModbusConfig pub = new ModbusConfig();
        pub.id = CID;
        pub.owner = ModbusConfigOwner.ofOrg("org2", "第二个组织"); // 他组织
        pub.visibility = "public";
        when(repo.findById("c2")).thenReturn(Uni.createFrom().item(pub));
        ModbusConfig found = service.get(USER, "c2").await().indefinitely();
        assertEquals("public", found.visibility);
        assertEquals("org2", found.owner.id);
    }

    @Test
    void getCrossOrgPrivateNotReadable() {
        ModbusConfig priv = new ModbusConfig();
        priv.id = CID;
        priv.owner = ModbusConfigOwner.ofOrg("org2", "第二个组织"); // 他组织私有
        priv.visibility = "private";
        when(repo.findById("c2")).thenReturn(Uni.createFrom().item(priv));
        when(orgAccess.checkMember("org2", USER))
                .thenReturn(Uni.createFrom().failure(new IllegalArgumentException("Not a member of the organization")));
        assertThrows(IllegalArgumentException.class,
                () -> service.get(USER, "c2").await().indefinitely());
    }

    @Test
    void getPublicReadable() {
        ModbusConfig pub = new ModbusConfig();
        pub.id = CID;
        pub.owner = ModbusConfigOwner.ofOrg("org2", "第二个组织");
        pub.visibility = "public";
        when(repo.findPublicById("c2")).thenReturn(Uni.createFrom().item(pub));
        ModbusConfig found = service.getPublic("c2").await().indefinitely();
        assertEquals(CID, found.id);
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
        when(repo.listVisible(USER, "org1")).thenReturn(Uni.createFrom().item(List.of(cfg)));
        assertEquals(1, service.listVisible(USER, "org1").await().indefinitely().size());
    }

    @Test
    void listVisibleWithNullOrgDelegates() {
        // 没有组织上下文：透传给仓储（仓储内部退化为「公开 + 本人」），服务层不拦截
        when(repo.listVisible(USER, " ")).thenReturn(Uni.createFrom().item(List.of(cfg)));
        assertEquals(1, service.listVisible(USER, " ").await().indefinitely().size());
        verify(repo).listVisible(USER, " ");
    }

    @Test
    void listByUserDelegates() {
        when(repo.listByUser(USER)).thenReturn(Uni.createFrom().item(List.of(cfg)));
        assertEquals(1, service.listByUser(USER).await().indefinitely().size());
        verify(repo).listByUser(USER);
    }

    @Test
    void listVisibleByOrgDelegates() {
        when(repo.listVisibleByOrg("org1")).thenReturn(Uni.createFrom().item(List.of(cfg)));
        assertEquals(1, service.listVisibleByOrg("org1").await().indefinitely().size());
        verify(repo).listVisibleByOrg("org1");
    }

    @Test
    void deleteZeroFailsAsNotFound() {
        // 鉴权（按 owner）先按 id 读原档：开发态才放行到删除
        ModbusConfig existing = devConfig();
        authOrgOk();
        when(repo.findById("c1")).thenReturn(Uni.createFrom().item(existing));
        when(repo.deleteById("c1")).thenReturn(Uni.createFrom().item(0L));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.delete(USER, "c1").await().indefinitely());
        assertEquals("modbus device config not found: c1", ex.getMessage());
    }

    @Test
    void deleteDelegates() {
        ModbusConfig existing = devConfig();
        authOrgOk();
        when(repo.findById("c1")).thenReturn(Uni.createFrom().item(existing));
        when(repo.deleteById("c1")).thenReturn(Uni.createFrom().item(1L));
        assertEquals(1L, service.delete(USER, "c1").await().indefinitely());
    }

    // ===== 生命周期：仅开发态可修改/删除 =====

    /** 造一条已落库的配置骨架（生命周期可另指定），用于 update/delete 门禁测试。 */
    private ModbusConfig devConfig() {
        ModbusConfig existing = new ModbusConfig();
        existing.id = CID;
        existing.owner = ModbusConfigOwner.ofOrg("org1", "第一个组织");
        existing.lifecycle = "development";
        existing.visibility = "private";
        existing.slave.manufacturer = "m";
        existing.slave.model = "mm";
        existing.creator = Person.of("acc1", "小明");
        return existing;
    }

    @Test
    void createDefaultsLifecycleToDevelopment() {
        cfg.commands = List.of(readHolding());
        insertOk();
        ModbusConfig created = service.create(cfg).await().indefinitely();
        assertEquals("development", created.lifecycle);
    }

    @Test
    void createForcesLifecycleToDevelopmentRegardlessOfRequest() {
        cfg.lifecycle = "released"; // 客户端请求里带的生命周期被忽略，一律回到开发态
        insertOk();
        ModbusConfig created = service.create(cfg).await().indefinitely();
        assertEquals("development", created.lifecycle);
    }

    @Test
    void updateDevelopmentConfigAllowed() {
        ModbusConfig existing = devConfig();
        authOrgOk();
        when(repo.findById("c1")).thenReturn(Uni.createFrom().item(existing));
        when(repo.replace(any(ModbusConfig.class))).thenReturn(Uni.createFrom().voidItem());

        cfg.slave.manufacturer = "new";
        cfg.updater = Person.of("acc2", "小红");
        cfg.commands = List.of(readHolding());
        ModbusConfig updated = service.update(USER, "c1", cfg).await().indefinitely();
        assertEquals("new", updated.slave.manufacturer);
        // 生命周期不随普通更新流转，仍停留在开发态
        assertEquals("development", updated.lifecycle);
    }

    @Test
    void updateReleasedConfigFails() {
        ModbusConfig existing = devConfig();
        existing.lifecycle = "released";
        authOrgOk();
        when(repo.findById("c1")).thenReturn(Uni.createFrom().item(existing));

        cfg.slave.manufacturer = "new";
        cfg.updater = Person.of("acc2", "小红");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.update(USER, "c1", cfg).await().indefinitely());
        assertEquals("modbus device config is released, only development config can be modified: " + CID.toHexString(),
                ex.getMessage());
    }

    @Test
    void updatePreviewConfigFails() {
        ModbusConfig existing = devConfig();
        existing.lifecycle = "preview";
        authOrgOk();
        when(repo.findById("c1")).thenReturn(Uni.createFrom().item(existing));

        cfg.slave.manufacturer = "new";
        cfg.updater = Person.of("acc2", "小红");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.update(USER, "c1", cfg).await().indefinitely());
        assertEquals("modbus device config is preview, only development config can be modified: " + CID.toHexString(),
                ex.getMessage());
    }

    @Test
    void deleteReleasedConfigFails() {
        ModbusConfig existing = devConfig();
        existing.lifecycle = "released";
        authOrgOk();
        when(repo.findById("c1")).thenReturn(Uni.createFrom().item(existing));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.delete(USER, "c1").await().indefinitely());
        assertEquals("modbus device config is released, only development config can be modified: " + CID.toHexString(),
                ex.getMessage());
    }

    @Test
    void changeLifecycleInvalidValueFails() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.changeLifecycle(USER, "c1", "archive", null).await().indefinitely());
        assertEquals("invalid lifecycle: archive", ex.getMessage());
    }

    @Test
    void changeLifecycleNullValueFails() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.changeLifecycle(USER, "c1", null, null).await().indefinitely());
        assertEquals("invalid lifecycle: null", ex.getMessage());
    }

    @Test
    void changeLifecycleNotFoundFails() {
        when(repo.findById("ghost")).thenReturn(Uni.createFrom().nullItem());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.changeLifecycle(USER, "ghost", "preview", null).await().indefinitely());
        assertEquals("modbus device config not found", ex.getMessage());
    }

    @Test
    void changeLifecycleDevelopmentToPreviewUpdates() {
        ModbusConfig existing = devConfig();
        authOrgOk();
        when(repo.findById("c1")).thenReturn(Uni.createFrom().item(existing));
        when(repo.replace(any(ModbusConfig.class))).thenReturn(Uni.createFrom().voidItem());

        Person who = Person.of("acc2", "小红");
        ModbusConfig updated = service.changeLifecycle(USER, "c1", "preview", who).await().indefinitely();
        assertEquals("preview", updated.lifecycle);
        assertEquals(who, updated.updater);
        verify(repo).replace(eq(existing));
    }

    @Test
    void changeLifecyclePreviewToReleasedAllowed() {
        ModbusConfig existing = devConfig();
        existing.lifecycle = "preview";
        authOrgOk();
        when(repo.findById("c1")).thenReturn(Uni.createFrom().item(existing));
        when(repo.replace(any(ModbusConfig.class))).thenReturn(Uni.createFrom().voidItem());

        ModbusConfig updated = service.changeLifecycle(USER, "c1", "released", null).await().indefinitely();
        assertEquals("released", updated.lifecycle);
    }

    @Test
    void changeLifecycleReleasedToDevelopmentUpdates() {
        ModbusConfig existing = devConfig();
        existing.lifecycle = "released";
        authOrgOk();
        when(repo.findById("c1")).thenReturn(Uni.createFrom().item(existing));
        when(repo.replace(any(ModbusConfig.class))).thenReturn(Uni.createFrom().voidItem());

        ModbusConfig updated = service.changeLifecycle(USER, "c1", "development", null).await().indefinitely();
        assertEquals("development", updated.lifecycle);
    }
}
