package cc.openxiot.matrix.api.device;

import cc.openxiot.matrix.db.device.DeviceEntity;
import cc.openxiot.matrix.db.device.DeviceRepository;
import cc.openxiot.matrix.db.space.SpaceOn;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class DeviceService {

    /** 在线状态仅供展示的虚拟协议：挂在某父设备下的逻辑子设备，无独立在线。 */
    private static final String VIRTUAL_PROTOCOL = "modbus";

    @Inject
    DeviceRepository deviceRepository;

    public Uni<List<DeviceEntity>> getBySpaceId(String spaceId) {
        return deviceRepository.findBySpaceId(spaceId).flatMap(this::withParentOnline);
    }

    public Uni<List<DeviceEntity>> getByDeviceIds(List<String> dids) {
        return deviceRepository.findByDeviceIds(dids).flatMap(this::withParentOnline);
    }

    public Uni<DeviceEntity> getByDeviceId(String did) {
        return deviceRepository.findByDeviceId(did).flatMap(device -> withParentOnline(device, new HashSet<>()));
    }

    public Uni<DeviceEntity> save(DeviceEntity device) {
        if (device.create == null) {
            device.create = new Date();
        }
        return deviceRepository.persistOrUpdate(device);
    }

    public Uni<List<DeviceEntity>> saveAll(List<DeviceEntity> devices) {
        List<Uni<DeviceEntity>> unis = devices.stream()
                .map(this::save)
                .toList();
        return Uni.join().all(unis).andFailFast();
    }

    public Uni<Void> updateSpace(String did, String spaceId, String rootSpaceId) {
        return deviceRepository.findByDeviceId(did)
                .chain(device -> {
                    if (device != null) {
                        SpaceOn space = new SpaceOn();
                        space.spaceId = spaceId;
                        space.rootId = rootSpaceId;
                        device.space = space;
                        return deviceRepository.persistOrUpdate(device).replaceWithVoid();
                    }
                    return Uni.createFrom().voidItem();
                });
    }

    public Uni<Long> deleteByDeviceIds(List<String> dids) {
        return deviceRepository.deleteByDeviceIds(dids);
    }

    // ===== 虚拟子设备 online 归一 =====

    /** 识别 Modbus 虚拟子设备：protocol=modbus 且挂在父设备下（parentId 非空）。 */
    public static boolean isModbusVirtual(DeviceEntity device) {
        return device != null
                && VIRTUAL_PROTOCOL.equals(device.protocol)
                && device.parentId != null
                && !device.parentId.isBlank();
    }

    /**
     * Modbus 虚拟子设备无独立在线，其 online 应与父设备保持一致。对一批设备就地改写：每个虚拟子设备的
     * online 取自身可上溯到的最上层祖先（父链若仍是虚拟设备则继续上溯）的 online；父设备不在本批内时补查。
     */
    private Uni<List<DeviceEntity>> withParentOnline(List<DeviceEntity> devices) {
        if (devices == null || devices.isEmpty()) {
            return Uni.createFrom().item(devices);
        }
        Map<String, DeviceEntity> byDid = new HashMap<>();
        List<String> missing = new ArrayList<>();
        for (DeviceEntity d : devices) {
            if (d == null || d.did == null) {
                continue;
            }
            byDid.put(d.did, d);
        }
        for (DeviceEntity d : devices) {
            if (isModbusVirtual(d) && !byDid.containsKey(d.parentId) && !missing.contains(d.parentId)) {
                missing.add(d.parentId);
            }
        }
        if (missing.isEmpty()) {
            applyParentOnline(devices, byDid);
            return Uni.createFrom().item(devices);
        }
        return deviceRepository.findByDeviceIds(missing).flatMap(parents -> {
            for (DeviceEntity p : parents) {
                if (p != null && p.did != null) {
                    byDid.putIfAbsent(p.did, p);
                }
            }
            applyParentOnline(devices, byDid);
            return Uni.createFrom().item(devices);
        });
    }

    /** 单个设备读取：虚拟子设备时沿父链上溯到最近非虚拟祖先，取其 online 回填（防嵌套/循环）。 */
    private Uni<DeviceEntity> withParentOnline(DeviceEntity device, Set<String> seen) {
        if (!isModbusVirtual(device)) {
            return Uni.createFrom().item(device);
        }
        if (device.parentId == null || !seen.add(device.parentId)) {
            return Uni.createFrom().item(device);
        }
        return deviceRepository.findByDeviceId(device.parentId).flatMap(parent -> {
            if (parent == null) {
                return Uni.createFrom().item(device);
            }
            return withParentOnline(parent, seen).map(root -> {
                device.online = root.online;
                return device;
            });
        });
    }

    /** 就地改写 devices 中每个虚拟子设备的 online 为可上溯祖先的 online（父不在图中则保留原值）。 */
    private static void applyParentOnline(List<DeviceEntity> devices, Map<String, DeviceEntity> byDid) {
        for (DeviceEntity d : devices) {
            if (!isModbusVirtual(d)) {
                continue;
            }
            DeviceEntity cur = d;
            Set<String> seen = new HashSet<>();
            seen.add(d.did);
            while (isModbusVirtual(cur) && cur.parentId != null && byDid.containsKey(cur.parentId)) {
                DeviceEntity parent = byDid.get(cur.parentId);
                if (!seen.add(parent.did)) {
                    break;
                }
                cur = parent;
            }
            // cur 为最近的非虚拟祖先，或父链顶端仍虚拟但父不在集合内的节点（父已删/未见 → 保留该节点原 online）
            if (cur != d) {
                d.online = cur.online;
            }
        }
    }
}
