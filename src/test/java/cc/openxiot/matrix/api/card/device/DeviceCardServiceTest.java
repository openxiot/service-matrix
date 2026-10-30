package cc.openxiot.matrix.api.card.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.openxiot.matrix.api.card.device.params.DeviceCardDto;
import cc.openxiot.matrix.db.card.device.DeviceCard;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 设备卡片配置的规范化：去重保序（订单即显示顺序）、非法 id 报错、数量上限。
 *
 * <p>纯函数测试，不连库：「设备属不属于这个项目」由资源层与所属服务的集成测试覆盖，
 * 这里只钉「一进一出这副形状」。</p>
 */
class DeviceCardServiceTest {

    private static DeviceCardDto.ServiceSelection selection(Integer siid, Integer... piids) {
        DeviceCardDto.ServiceSelection selection = new DeviceCardDto.ServiceSelection();
        selection.siid = siid;
        // Arrays.asList：piids 里可能有 null（那是被特意用来测非法输入的）
        selection.piids = piids == null ? null : new ArrayList<>(Arrays.asList(piids));
        return selection;
    }

    @Test
    void keepsOrderAndMergesDuplicateServices() {
        List<DeviceCard.ServiceSelection> services = DeviceCardService.normalized(List.of(
                selection(2, 5, 4),
                selection(1, 1),
                // 同一个服务再次出现：属性按首次出现的位置合并、重复的只留一次
                selection(2, 4, 6)));

        assertEquals(2, services.size());
        assertEquals(2, services.get(0).siid);
        assertEquals(List.of(5, 4, 6), services.get(0).piids);
        assertEquals(1, services.get(1).siid);
        assertEquals(List.of(1), services.get(1).piids);
    }

    @Test
    void nullSelectionListMeansEmpty() {
        assertTrue(DeviceCardService.normalized(null).isEmpty());
    }

    @Test
    void serviceWithoutPropertiesIsAllowed() {
        List<DeviceCard.ServiceSelection> services =
                DeviceCardService.normalized(List.of(selection(1)));

        assertEquals(1, services.size());
        assertEquals(List.of(), services.get(0).piids);
    }

    @Test
    void rejectsNonPositiveIds() {
        assertThrows(IllegalArgumentException.class,
                () -> DeviceCardService.normalized(List.of(selection(null, 1))));
        assertThrows(IllegalArgumentException.class,
                () -> DeviceCardService.normalized(List.of(selection(0, 1))));
        assertThrows(IllegalArgumentException.class,
                () -> DeviceCardService.normalized(List.of(selection(1, 0))));
        assertThrows(IllegalArgumentException.class,
                () -> DeviceCardService.normalized(List.of(selection(1, (Integer) null))));
    }

    @Test
    void capsServiceCount() {
        List<DeviceCardDto.ServiceSelection> input = new ArrayList<>();
        for (int siid = 1; siid <= DeviceCardService.MAX_SERVICES + 1; siid++) {
            input.add(selection(siid));
        }
        assertThrows(IllegalArgumentException.class, () -> DeviceCardService.normalized(input));
    }

    @Test
    void capsPropertyCountPerService() {
        List<DeviceCardDto.ServiceSelection> input = new ArrayList<>();
        Integer[] piids = new Integer[DeviceCardService.MAX_PIIDS_PER_SERVICE + 1];
        for (int i = 0; i < piids.length; i++) {
            piids[i] = i + 1;
        }
        input.add(selection(1, piids));
        assertThrows(IllegalArgumentException.class, () -> DeviceCardService.normalized(input));
    }
}
