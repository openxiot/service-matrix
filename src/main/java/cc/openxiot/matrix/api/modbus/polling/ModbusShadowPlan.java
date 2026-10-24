package cc.openxiot.matrix.api.modbus.polling;

import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.db.modbus.shadow.ModbusShadow;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.poll.response.field.ModbusResponseField;
import cc.openxiot.matrix.db.modbus.service.poll.response.field.ModbusResponseFieldBit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 影子（{@code service-shadows}）跟着定义对账的**纯函数**：定义变了，影子会留下收不到更新的东西。
 *
 * <p>寄存器是**原子单位**：对账以逐寄存器影子（{@link ModbusShadow}）为单位，
 * 现有的「寄存器」由 {@code ModbusService.configSnapshot.registries} 给出。</p>
 *
 * <p>唯一需要处置的是**孤儿寄存器**（定义里删掉的 {@code registerIndex}）：那只寄存器的影子再也不会被更新，
 * 却一直出现在「当前值接口」里 —— 前端会显示一个永远不变的值，而它对应的点位已经不存在了。
 * 必须主动删。寄存器没有「废弃字段键」这一层（值/故障态都是正则子自身），所以对非孤儿一律不改。</p>
 *
 * <p>时机：服务定义的写入路径（{@code create} / {@code update}）+ 启动全量扫一次。
 * **不进周期对账** —— 那要扫「服务 × 影子」，而影子的陈旧只可能来自定义变更，
 * 定义变更必经写入路径；直接改库的情况由启动那次兜住。</p>
 */
public final class ModbusShadowPlan {

    private ModbusShadowPlan() {
    }

    /**
     * 一张寄存器影子该怎么修。
     *
     * @param staleFields 寄存器是原子单位，没有「废弃字段键」—— 恒为空（{@code orphan} 为 true 时无意义）
     * @param orphan      整只寄存器已从点表里删掉 —— 整条影子都要删
     */
    public record Fix(ModbusShadow shadow, List<String> staleFields, boolean orphan) {
    }

    /**
     * 一个方法在影子里会出现的**全部**键：{@code response.fields[].field} 加上各位展开的名字
     * {@code response.fields[].bitList[].field}（见 {@code ModbusResponseParser}：位名与字段名放进同一个结果对象）。
     */
    public static Set<String> definedFields(List<ModbusResponseField> response) {
        Set<String> defined = new HashSet<>();
        if (response == null) {
            return defined;
        }
        for (ModbusResponseField field : response) {
            if (field == null) {
                continue;
            }
            if (field.field != null && !field.field.isBlank()) {
                defined.add(field.field);
            }
            if (field.bitList == null) {
                continue;
            }
            for (ModbusResponseFieldBit bit : field.bitList) {
                if (bit != null && bit.field != null && !bit.field.isBlank()) {
                    defined.add(bit.field);
                }
            }
        }
        return defined;
    }

    /**
     * 算出这批寄存器影子需要做的修改（不需要动的**不出现在结果里**：调用方据此零写操作）。
     *
     * <p>{@code service == null} / {@code configSnapshot.registries == null} 时返回空 ——
     * 拿不到定义就不动任何影子，宁可留一张陈旧影子，也不要在读不到定义时把用户的当前值删掉。</p>
     *
     * <p>寄存器是原子单位，没有「废弃字段键」：对非孤儿（registerIndex 还在点表里）一律不改，
     * 因此只产出 {@code orphan == true} 的修复，且 {@code staleFields} 恒为空。</p>
     */
    public static List<Fix> plan(ModbusService service, List<ModbusShadow> shadows) {
        List<Fix> fixes = new ArrayList<>();
        ModbusConfig config = service == null ? null : service.configSnapshot;
        if (service == null || service.id == null || config == null || config.registries == null || shadows == null) {
            return fixes;
        }

        Set<Integer> definedIndexes = new HashSet<>();
        for (ModbusRegistry registry : config.registries) {
            if (registry != null && registry.index != null) {
                definedIndexes.add(registry.index);
            }
        }

        String serviceId = service.id.toHexString();
        for (ModbusShadow shadow : shadows) {
            if (shadow == null || shadow.id == null || shadow.registerIndex == null) {
                continue;
            }
            // 影子是别的服务的：对账按服务做，串了就是 bug，不动它（正常不会发生）
            if (!serviceId.equals(shadow.serviceId)) {
                continue;
            }
            // 寄存器不在点表里（或点表条目没有 index）：即定义为没有这只寄存器 —— 孤儿，整条删
            if (!definedIndexes.contains(shadow.registerIndex)) {
                fixes.add(new Fix(shadow, List.of(), true));
            }
            // 寄存器在点表里：原子单位，没有废弃字段键，不需要动
        }
        fixes.sort((left, right) -> {
            int byIndex = Integer.compare(left.shadow().registerIndex, right.shadow().registerIndex);
            return byIndex != 0 ? byIndex : left.shadow().id.compareTo(right.shadow().id);
        });
        return fixes;
    }
}