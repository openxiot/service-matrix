package cc.openxiot.matrix.api.modbus.shadow;

import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.db.modbus.shadow.ModbusShadow;
import cc.openxiot.matrix.db.modbus.shadow.ModbusShadowRepository;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceRepository;
import cc.openxiot.matrix.db.modbus.service.poll.ModbusPoll;
import cc.openxiot.matrix.db.modbus.service.poll.response.field.ModbusResponseField;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
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
    ModbusShadowRepository registryShadowRepo;

    /**
     * 取一个服务的寄存器缓存网格（{@code ModbusShadow} 有值才出现缓存，规则见
     * {@link #mergeRegisterCache}）。
     *
     * <p>与按方法的「当前值」接口（本类的 {@code ModbusShadowResource.current}）的差别：这里以点表
     * {@code registries} 为准、覆盖全部可读寄存器（含从没被调用过的，标 {@code no-data}）。</p>
     *
     * <p>服务不存在 / 没有点表快照时返回空网格——读取是**投影**，「能给多少给多少」，
     * 不因单个 id 失败而报错（与设备影子的「按 did 取、能取到多少返回多少」同口径）。</p>
     */
    public Uni<List<JsonObject>> registerCache(String serviceId) {
        return repo.findById(serviceId)
                .onItem().transformToUni(service -> service == null
                        ? Uni.createFrom().item(List.of())
                        : registryShadowRepo.listByServiceId(serviceId)
                                .map(shadows -> mergeRegisterCache(
                                        service.configSnapshot, service.polls, shadows)));
    }

    /**
     * 批量取多个服务的网格，按 {@code serviceId} 归组（{@code ModbusShadowResource} 用）。
     *
     * <p>每个服务一张网格并行取；单个 {@code serviceId} 无效时那家为空网格、不拖垮整批。
     * 结果保持入参顺序。</p>
     */
    public Uni<Map<String, List<JsonObject>>> registerCaches(List<String> serviceIds) {
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
     * 值来自逐寄存器的影子 {@code ModbusShadow}（按 {@code registerIndex} 索引到该寄存器）；
     * 拿不到影子、或影子没有值 → {@code no-data}。方法归属 {@code pollIndex} 仍由
     * {@code functions} 里应答字段名 == 寄存器点表名的那个方法解出（只作展示，不决定值）。
     *
     * <p>直接产出对外 JSON（{@code /shadows} 的形状，见 {@link #encodeRegister}）—— 点表投影是
     * 读侧纯投影，不需要一份中间 DTO（原 {@code ModbusRegisterSnapshot} 已删除）。</p>
     */
    private static List<JsonObject> mergeRegisterCache(
            ModbusConfig config,
            List<ModbusPoll> functions,
            List<ModbusShadow> shadows
    ) {
        // 寄存器点位名 → 读它的方法（应答字段名 == 寄存器点位名）
        Map<String, Integer> owner = new HashMap<>();
        if (functions != null) {
            for (ModbusPoll fn : functions) {
                if (fn == null || fn.index == null) {
                    continue;
                }
                for (ModbusResponseField f : ModbusPoll.responseFields(fn)) {
                    if (f != null && f.field != null) {
                        owner.put(f.field, fn.index);
                    }
                }
            }
        }
        // 寄存器序号 → 影子（逐寄存器：身份是 registerIndex）
        Map<Integer, ModbusShadow> byIndex = new HashMap<>();
        if (shadows != null) {
            for (ModbusShadow sh : shadows) {
                if (sh != null && sh.registerIndex != null) {
                    byIndex.put(sh.registerIndex, sh);
                }
            }
        }

        List<JsonObject> out = new ArrayList<>();
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
            ModbusShadow sh = reg.index == null ? null : byIndex.get(reg.index);
            out.add(sh == null || sh.value == null
                    ? encodeRegister(reg, access, owner.get(reg.name), STATUS_NO_DATA,
                            null, null, null, null)
                    : sh.lastError == null
                            ? encodeRegister(reg, access, owner.get(reg.name), STATUS_CURRENT,
                                    sh.value, sh.recordedAt, null, null)
                            : encodeRegister(reg, access, owner.get(reg.name), STATUS_STALE,
                                    sh.value, sh.recordedAt, sh.lastError, sh.errorAt));
        }
        return out;
    }

    /**
     * 一只寄存器投影的对外 JSON，键序与原 {@code ModbusRegisterSnapshotCodec} 逐字一致：
     * {@code name, area?, dataType?, unit?, access, pollIndex?, value?, recordedAt?, status,
     * lastError?, errorAt?}。null 的字段不出键（前端判「有没有缓存」看 {@code value} 在不在）。
     */
    private static JsonObject encodeRegister(ModbusRegistry reg, String access, Integer pollIndex,
                                             String status, Object value, java.util.Date recordedAt,
                                             String lastError, java.util.Date errorAt) {
        JsonObject o = new JsonObject();
        o.put("name", reg.name);
        if (reg.area != null) {
            o.put("area", reg.area);
        }
        if (reg.dataType != null) {
            o.put("dataType", reg.dataType);
        }
        if (reg.unit != null) {
            o.put("unit", reg.unit);
        }
        o.put("access", access);
        if (pollIndex != null) {
            o.put("pollIndex", pollIndex);
        }
        if (value != null) {
            o.put("value", value);
        }
        if (recordedAt != null) {
            o.put("recordedAt", recordedAt.getTime());
        }
        o.put("status", status);
        if (lastError != null) {
            o.put("lastError", lastError);
        }
        if (errorAt != null) {
            o.put("errorAt", errorAt.getTime());
        }
        return o;
    }

    /** 缓存态：正常 / 陈旧（失败后值不再更新）/ 无数据（从没采到）。见 {@link #encodeRegister} 的 status。 */
    private static final String STATUS_CURRENT = "current";
    private static final String STATUS_STALE = "stale";
    private static final String STATUS_NO_DATA = "no-data";
}