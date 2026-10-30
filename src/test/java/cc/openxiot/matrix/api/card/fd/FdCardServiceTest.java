package cc.openxiot.matrix.api.card.fd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.db.modbus.fd.FieldDevice;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 现场设备卡片配置的规范化：只能选本现场设备点表里**可读**的寄存器，去重保序，数量有上限。
 *
 * <p>与设备卡片不同，这里的校验是硬的：点表就在 {@code configSnapshot} 里（创建服务时冻结），
 * 校验它不需要回查任何东西。纯函数测试，不连库。</p>
 */
class FdCardServiceTest {

    private static ModbusRegistry registry(int index, String access) {
        ModbusRegistry registry = new ModbusRegistry();
        registry.index = index;
        registry.name = "寄存器" + index;
        registry.access = access;
        return registry;
    }

    private static FieldDevice fd() {
        FieldDevice fd = new FieldDevice();
        ModbusConfig config = new ModbusConfig();
        config.registries = List.of(
                registry(1, "R"),
                registry(2, "RW"),
                registry(3, "W"),   // 只写：卡片上没有值可显示
                registry(4, "R"));
        fd.configSnapshot = config;
        return fd;
    }

    @Test
    void keepsReadableRegistersInOrderAndDedupes() {
        assertEquals(List.of(4, 1, 2),
                FdCardService.normalized(fd(), List.of(4, 1, 4, 2)));
    }

    @Test
    void rejectsWriteOnlyRegister() {
        assertThrows(IllegalArgumentException.class,
                () -> FdCardService.normalized(fd(), List.of(3)));
    }

    @Test
    void rejectsUnknownRegister() {
        assertThrows(IllegalArgumentException.class,
                () -> FdCardService.normalized(fd(), List.of(9)));
        assertThrows(IllegalArgumentException.class,
                () -> FdCardService.normalized(fd(), List.of(0)));
        assertThrows(IllegalArgumentException.class,
                () -> FdCardService.normalized(fd(), Arrays.asList((Integer) null)));
    }

    @Test
    void emptySelectionIsAllowed() {
        assertTrue(FdCardService.normalized(fd(), null).isEmpty());
        assertTrue(FdCardService.normalized(fd(), List.of()).isEmpty());
    }

    /** 点表为空（或现场设备没有快照）时，任何非空选择都无从成立。 */
    @Test
    void rejectsRegistersWhenSnapshotHasNoRegistries() {
        FieldDevice fd = new FieldDevice();
        fd.configSnapshot = null;
        assertTrue(FdCardService.normalized(fd, List.of()).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> FdCardService.normalized(fd, List.of(1)));
    }

    @Test
    void capsRegisterCount() {
        FieldDevice fd = new FieldDevice();
        ModbusConfig config = new ModbusConfig();
        List<ModbusRegistry> registries = new ArrayList<>();
        int wanted = FdCardService.MAX_REGISTERS + 1;
        List<Integer> selection = new ArrayList<>();
        for (int index = 1; index <= wanted; index++) {
            registries.add(registry(index, "R"));
            selection.add(index);
        }
        config.registries = registries;
        fd.configSnapshot = config;

        assertThrows(IllegalArgumentException.class, () -> FdCardService.normalized(fd, selection));
    }
}
