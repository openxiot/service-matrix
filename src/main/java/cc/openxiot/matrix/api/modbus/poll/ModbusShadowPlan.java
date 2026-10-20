package cc.openxiot.matrix.api.modbus.poll;

import cc.openxiot.matrix.db.modbus.history.ModbusServiceShadow;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.polling.response.field.ModbusPollingResponseField;
import cc.openxiot.matrix.db.modbus.service.polling.response.field.ModbusPollingResponseFieldBit;
import cc.openxiot.matrix.db.modbus.service.polling.ModbusPolling;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 影子（{@code service-shadows}）跟着定义对账的**纯函数**：定义变了，影子会留下收不到更新的东西。
 *
 * <p>两种「陈旧」的代价与急迫性完全不同，所以处置也不同：</p>
 *
 * <ul>
 *   <li><b>孤儿方法</b>（定义里删掉的 {@code pollingIndex}）：那张影子再也不会被更新，
 *       却一直出现在「当前值接口」里 —— 前端会显示一个永远不变的值，而它对应的点位已经不存在了。
 *       必须主动删。</li>
 *   <li><b>废弃字段键</b>（{@code response} 里删掉的 {@code field}）：{@code ModbusHistoryService.record}
 *       是**整体替换** {@code shadow.fields} 的，所以下一次成功采集就自愈了。
 *       但它最坏要等到保活周期（缺省 10 分钟），期间前端会显示一个已经删掉的点位。顺手剪掉即可。</li>
 * </ul>
 *
 * <p>时机：服务定义的写入路径（{@code create} / {@code update}）+ 启动全量扫一次。
 * **不进周期对账** —— 那要扫「服务 × 影子」，而影子的陈旧只可能来自定义变更，
 * 定义变更必经写入路径；直接改库的情况由启动那次兜住。</p>
 */
public final class ModbusShadowPlan {

    private ModbusShadowPlan() {
    }

    /**
     * 一张影子该怎么修。
     *
     * @param staleFields 定义里已经没有的字段键（{@code orphan} 为 true 时无意义）
     * @param orphan      整个方法已从定义里删掉 —— 整条影子都要删
     */
    public record Fix(ModbusServiceShadow shadow, List<String> staleFields, boolean orphan) {
    }

    /**
     * 一个方法在影子里会出现的**全部**键：{@code response.fields[].field} 加上各位展开的名字
     * {@code response.fields[].bitList[].field}（见 {@code ModbusResponseParser}：位名与字段名放进同一个结果对象）。
     */
    public static Set<String> definedFields(List<ModbusPollingResponseField> response) {
        Set<String> defined = new HashSet<>();
        if (response == null) {
            return defined;
        }
        for (ModbusPollingResponseField field : response) {
            if (field == null) {
                continue;
            }
            if (field.field != null && !field.field.isBlank()) {
                defined.add(field.field);
            }
            if (field.bitList == null) {
                continue;
            }
            for (ModbusPollingResponseFieldBit bit : field.bitList) {
                if (bit != null && bit.field != null && !bit.field.isBlank()) {
                    defined.add(bit.field);
                }
            }
        }
        return defined;
    }

    /**
     * 算出这批影子需要做的修改（不需要动的**不出现在结果里**：调用方据此零写操作）。
     *
     * <p>{@code service == null} / {@code functions == null} 时返回空 —— 拿不到定义就不动任何影子，
     * 宁可留一张陈旧影子，也不要在读不到定义时把用户的当前值删掉。</p>
     *
     * <p>写方法（{@code response} 为空）的影子**不算孤儿**：它确实会被调用（手动 invoke 写方法），
     * 影子也就该存在，只是没有字段而已。</p>
     */
    public static List<Fix> plan(ModbusService service, List<ModbusServiceShadow> shadows) {
        List<Fix> fixes = new ArrayList<>();
        if (service == null || service.id == null || service.functions == null || shadows == null) {
            return fixes;
        }

        Map<Integer, Set<String>> definedByIndex = new HashMap<>();
        for (ModbusPolling function : service.functions) {
            if (function != null && function.index != null) {
                definedByIndex.put(function.index, definedFields(ModbusPolling.responseFields(function)));
            }
        }

        String serviceId = service.id.toHexString();
        for (ModbusServiceShadow shadow : shadows) {
            if (shadow == null || shadow.id == null || shadow.pollingIndex == null) {
                continue;
            }
            // 影子是别的服务的：对账按服务做，串了就是 bug，不动它（正常不会发生）
            if (!serviceId.equals(shadow.serviceId)) {
                continue;
            }
            if (!definedByIndex.containsKey(shadow.pollingIndex)) {
                fixes.add(new Fix(shadow, List.of(), true));
                continue;
            }
            Set<String> defined = definedByIndex.get(shadow.pollingIndex);
            Set<String> stale = new TreeSet<>();
            if (shadow.fields != null) {
                for (String key : shadow.fields.keySet()) {
                    if (key != null && !defined.contains(key)) {
                        stale.add(key);
                    }
                }
            }
            if (!stale.isEmpty()) {
                fixes.add(new Fix(shadow, List.copyOf(stale), false));
            }
        }
        fixes.sort((left, right) -> {
            int byIndex = Integer.compare(left.shadow().pollingIndex, right.shadow().pollingIndex);
            return byIndex != 0 ? byIndex : left.shadow().id.compareTo(right.shadow().id);
        });
        return fixes;
    }
}
