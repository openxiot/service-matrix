package cc.openxiot.matrix.api.modbus.shadow;

import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceLastPolling;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceLastPollingRepository;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceRepository;
import cc.openxiot.matrix.db.modbus.service.polling.ModbusPolling;
import cc.openxiot.matrix.db.modbus.service.polling.response.field.ModbusPollingResponseField;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Modbus 服务的「当前值」读投影：寄存器缓存网格。
 *
 * <p>把点表里所有可读寄存器与各方法最后一次轮询快照（{@code ModbusServiceLastPolling}）归并成
 * 「按寄存器」的缓存视图 —— 一次读取看到整张点表的缓存实况。纯读侧投影，不落库、不写盘。</p>
 *
 * <p>与 {@code ModbusServiceService} 是两回事：那边是服务的**定义维护 + 方法调用**（写路径，
 * 落历史 / 影子 / 告警），这里只做**读**。需要解析「服务 → 点表」时用 {@code ModbusServiceRepository}，
 * 不依赖调用逻辑。</p>
 */
@ApplicationScoped
public class ModbusShadowService {

    @Inject
    ModbusServiceRepository repo;

    @Inject
    ModbusServiceLastPollingRepository lastPollingRepo;

    /**
     * 取一个服务的寄存器缓存网格（{@code ModbusServiceLastPolling} 有值才出现缓存，规则见
     * {@link #mergeRegisterCache}）。
     *
     * <p>与按方法的「当前值」接口（{@code ModbusHistoryResource.current}）的差别：这里以点表
     * {@code registries} 为准、覆盖全部可读寄存器（含从没被调用过的，标 {@code no-data}）。</p>
     *
     * <p>服务不存在 / 没有点表快照时返回空网格——读取是**投影**，「能给多少给多少」，
     * 不因单个 id 失败而报错（与设备影子的「按 did 取、能取到多少返回多少」同口径）。</p>
     */
    public Uni<List<ModbusRegisterSnapshot>> registerCache(String serviceId) {
        return repo.findById(serviceId)
                .onItem().transformToUni(service -> service == null
                        ? Uni.createFrom().item(List.of())
                        : lastPollingRepo.listByServiceId(serviceId)
                                .map(snapshots -> mergeRegisterCache(
                                        service.configSnapshot, service.functions, snapshots)));
    }

    /**
     * 批量取多个服务的网格，按 {@code serviceId} 归组（{@code ModbusShadowResource} 用）。
     *
     * <p>每个服务一张网格并行取；单个 {@code serviceId} 无效时那家为空网格、不拖垮整批。
     * 结果保持入参顺序。</p>
     */
    public Uni<Map<String, List<ModbusRegisterSnapshot>>> registerCaches(List<String> serviceIds) {
        if (serviceIds == null || serviceIds.isEmpty()) {
            return Uni.createFrom().item(Map.of());
        }
        return Uni.join().all(serviceIds.stream()
                        .map(serviceId -> registerCache(serviceId).map(list -> Map.entry(serviceId, list)))
                        .toList())
                .andFailFast()
                .map(entries -> entries.stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                                (a, b) -> a, LinkedHashMap::new)));
    }

    /**
     * 归并本核。可读寄存器 = 点表 {@code access ∈ {R, RW}} 的那些（写寄存器没有缓存值）。
     * 值来自归属它的读方法的快照 {@code fields[寄存器名]}；拿不到归属方法、或缓存里没有这个键 → {@code no-data}。
     */
    private static List<ModbusRegisterSnapshot> mergeRegisterCache(
            ModbusConfig config,
            List<ModbusPolling> functions,
            List<ModbusServiceLastPolling> snapshots
    ) {
        // 寄存器点位名 → 读它的方法（应答字段名 == 寄存器点位名）
        Map<String, Integer> owner = new HashMap<>();
        if (functions != null) {
            for (ModbusPolling fn : functions) {
                if (fn == null || fn.index == null) {
                    continue;
                }
                for (ModbusPollingResponseField f : ModbusPolling.responseFields(fn)) {
                    if (f != null && f.field != null) {
                        owner.put(f.field, fn.index);
                    }
                }
            }
        }
        Map<Integer, ModbusServiceLastPolling> byIndex = new HashMap<>();
        if (snapshots != null) {
            for (ModbusServiceLastPolling lp : snapshots) {
                if (lp != null && lp.pollingIndex != null) {
                    byIndex.put(lp.pollingIndex, lp);
                }
            }
        }

        List<ModbusRegisterSnapshot> out = new ArrayList<>();
        if (config == null || config.registries == null) {
            return out;
        }
        for (ModbusRegistry reg : config.registries) {
            if (reg == null) {
                continue;
            }
            String access = reg.access;
            if (!"R".equals(access) && !"RW".equals(access)) {
                continue;
            }
            ModbusRegisterSnapshot snap = new ModbusRegisterSnapshot();
            snap.name = reg.name;
            snap.area = reg.area;
            snap.dataType = reg.dataType;
            snap.unit = reg.unit;
            snap.access = access;
            Integer idx = owner.get(reg.name);
            snap.pollingIndex = idx;
            ModbusServiceLastPolling lp = idx == null ? null : byIndex.get(idx);
            Object value = lp != null && lp.fields != null ? lp.fields.get(reg.name) : null;
            if (value == null) {
                snap.status = ModbusRegisterSnapshot.STATUS_NO_DATA;
            } else {
                snap.value = value;
                snap.recordedAt = lp.recordedAt;
                if (lp.lastError == null) {
                    snap.status = ModbusRegisterSnapshot.STATUS_CURRENT;
                } else {
                    snap.status = ModbusRegisterSnapshot.STATUS_STALE;
                    snap.lastError = lp.lastError;
                    snap.errorAt = lp.errorAt;
                }
            }
            out.add(snap);
        }
        return out;
    }
}