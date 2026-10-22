package cc.openxiot.matrix.api.modbus.poll;

import cc.openxiot.matrix.db.modbus.service.polling.ModbusPollingFrom;
import cc.openxiot.matrix.db.modbus.service.polling.ModbusPolling;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceLastPolling;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.polling.response.field.ModbusPollingResponseField;
import cc.openxiot.matrix.db.modbus.service.polling.response.field.ModbusPollingResponseFieldBit;
import cc.openxiot.matrix.db.modbus.service.polling.request.ModbusPollingRequests;
import cc.openxiot.matrix.db.modbus.service.polling.response.ModbusPollingResponses;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModbusLastPollingPlan}：定义改了之后，影子该跟着动哪些。
 *
 * <p>两件事分开看：**孤儿方法**（定义里删掉的方法）必须主动删 —— 它的影子再也不会被更新，
 * 却一直出现在当前值接口里；**废弃字段键**（{@code response} 里删掉的字段）下一次成功采集就自愈，
 * 顺手剪掉即可。</p>
 */
class ModbusLastPollingPlanTest {

    private static final String SERVICE_ID = new ObjectId().toHexString();

    /** 定义里已经没有这个方法了：整条影子要删（否则前端一直显示一个不存在的点位）。 */
    @Test
    void shadowOfADeletedFunctionIsAnOrphan() {
        ModbusService service = service(function(1, field("temp")));
        ModbusServiceLastPolling alive = shadow(SERVICE_ID, 1, Map.of("temp", 21));
        ModbusServiceLastPolling orphan = shadow(SERVICE_ID, 2, Map.of("gone", 1));

        List<ModbusLastPollingPlan.Fix> fixes = ModbusLastPollingPlan.plan(service, List.of(alive, orphan));

        assertEquals(1, fixes.size());
        assertTrue(fixes.get(0).orphan());
        assertEquals(2, fixes.get(0).shadow().pollingIndex);
        assertTrue(fixes.get(0).staleFields().isEmpty());
    }

    /** 定义里删掉的字段键：剪掉（最坏也只是拖到保活周期，所以不单独扫）。 */
    @Test
    void fieldsMissingFromTheDefinitionAreStale() {
        ModbusService service = service(function(1, field("temp"), field("humidity")));
        ModbusServiceLastPolling shadow = shadow(SERVICE_ID, 1,
                map("temp", 21, "humidity", 60, "removed", 1));

        List<ModbusLastPollingPlan.Fix> fixes = ModbusLastPollingPlan.plan(service, List.of(shadow));

        assertEquals(1, fixes.size());
        assertFalse(fixes.get(0).orphan());
        assertEquals(List.of("removed"), fixes.get(0).staleFields());
    }

    /** 位展开的名字与字段名放在同一个结果对象里，所以两者都算「定义里有的」。 */
    @Test
    void bitNamesCountAsDefinedFields() {
        ModbusPollingResponseField word = field("status");
        word.bitList = List.of(bit(0, "running"), bit(1, "fault"));
        ModbusService service = service(function(1, word));
        ModbusServiceLastPolling shadow = shadow(SERVICE_ID, 1,
                map("status", 3, "running", 1, "fault", 1, "stale", 0));

        List<ModbusLastPollingPlan.Fix> fixes = ModbusLastPollingPlan.plan(service, List.of(shadow));

        assertEquals(1, fixes.size());
        assertEquals(List.of("stale"), fixes.get(0).staleFields());
    }

    /** 影子与定义一致时**不出现在结果里**：调用方据此零写操作（对账路径上顺手剪，不该每次都写库）。 */
    @Test
    void consistentShadowsProduceNoFix() {
        ModbusService service = service(function(1, field("temp")));

        assertTrue(ModbusLastPollingPlan.plan(service, List.of(shadow(SERVICE_ID, 1, Map.of("temp", 21)))).isEmpty());
        assertTrue(ModbusLastPollingPlan.plan(service, List.of(shadow(SERVICE_ID, 1, Map.of()))).isEmpty());
        assertTrue(ModbusLastPollingPlan.plan(service, List.of(shadow(SERVICE_ID, 1, null))).isEmpty());
        assertTrue(ModbusLastPollingPlan.plan(service, List.of()).isEmpty());
        assertTrue(ModbusLastPollingPlan.plan(service, null).isEmpty());
    }

    /** 写方法（空 {@code response}）的影子**不算孤儿**：它确实会被调用（手动 invoke 写方法）。 */
    @Test
    void writeFunctionsShadowIsNotAnOrphan() {
        ModbusService service = service(writeFunction(1));
        ModbusServiceLastPolling shadow = shadow(SERVICE_ID, 1, null);

        assertTrue(ModbusLastPollingPlan.plan(service, List.of(shadow)).isEmpty());
    }

    /** 写方法没有读值可记，但影子上一旦留下过字段，它们就成了「定义里没有的」—— 一样要剪掉。 */
    @Test
    void fieldsOnAWriteFunctionsShadowAreStale() {
        ModbusService service = service(writeFunction(1));
        ModbusServiceLastPolling shadow = shadow(SERVICE_ID, 1, Map.of("leftover", 1));

        List<ModbusLastPollingPlan.Fix> fixes = ModbusLastPollingPlan.plan(service, List.of(shadow));

        assertEquals(1, fixes.size());
        assertFalse(fixes.get(0).orphan());
        assertEquals(List.of("leftover"), fixes.get(0).staleFields());
    }

    /** 拿不到定义就不动任何影子：宁可留一张陈旧影子，也不要把用户的当前值删掉。 */
    @Test
    void withoutDefinitionNothingIsTouched() {
        ModbusService noFunctions = service(function(1, field("temp")));
        noFunctions.functions = null;
        ModbusService noId = service(function(1, field("temp")));
        noId.id = null;

        List<ModbusServiceLastPolling> shadows = List.of(shadow(SERVICE_ID, 9, Map.of("x", 1)));

        assertTrue(ModbusLastPollingPlan.plan(null, shadows).isEmpty());
        assertTrue(ModbusLastPollingPlan.plan(noFunctions, shadows).isEmpty());
        assertTrue(ModbusLastPollingPlan.plan(noId, shadows).isEmpty());
    }

    /** 影子是别的服务的：对账按服务做，串了就是 bug —— 不动它，也不要把它当孤儿删掉。 */
    @Test
    void shadowsOfAnotherServiceAreSkipped() {
        ModbusService service = service(function(1, field("temp")));
        ModbusServiceLastPolling other = shadow(new ObjectId().toHexString(), 9, Map.of("x", 1));

        assertTrue(ModbusLastPollingPlan.plan(service, List.of(other)).isEmpty());
    }

    /** 多个修复按 pollingIndex 升序（调用方按顺序写库，日志里也好读）。 */
    @Test
    void fixesAreSortedByFunctionIndex() {
        ModbusService service = service(function(1, field("temp")));
        List<ModbusServiceLastPolling> shadows = new ArrayList<>(List.of(
                shadow(SERVICE_ID, 3, Map.of("x", 1)),
                shadow(SERVICE_ID, 1, map("temp", 21, "stale", 0)),
                shadow(SERVICE_ID, 2, Map.of("y", 1))));

        List<ModbusLastPollingPlan.Fix> fixes = ModbusLastPollingPlan.plan(service, shadows);

        assertEquals(3, fixes.size());
        assertEquals(1, fixes.get(0).shadow().pollingIndex);
        assertEquals(List.of("stale"), fixes.get(0).staleFields());
        assertEquals(2, fixes.get(1).shadow().pollingIndex);
        assertTrue(fixes.get(1).orphan());
        assertEquals(3, fixes.get(2).shadow().pollingIndex);
    }

    /** 取名字时把空名与空元素都滤掉：它们进不了影子，也就不该出现在「定义里有」这一侧。 */
    @Test
    void definedFieldsReadsFieldAndBitNames() {
        ModbusPollingResponseField word = field("status");
        word.bitList = List.of(bit(0, "running"), bit(1, null));

        assertEquals(Set.of("status", "running"),
                ModbusLastPollingPlan.definedFields(List.of(word, field(null), field("  "))));
        assertTrue(ModbusLastPollingPlan.definedFields(null).isEmpty());
    }

    // -- helpers --

    private static ModbusPolling function(Integer index, ModbusPollingResponseField... response) {
        ModbusPolling function = new ModbusPolling();
                    function.from = new ModbusPollingFrom();
                    function.from.type = "command";
                    function.from.index = index;
                    function.index = index;
        function.request = ModbusPollingRequests.holding();
        function.response = response.length == 0 ? null : ModbusPollingResponses.of(response);
        return function;
    }

    /** 写方法（fc 06）：请求帧是写，{@code response} 为空 —— 它不会产出一个字段。 */
    private static ModbusPolling writeFunction(Integer index) {
        ModbusPolling function = new ModbusPolling();
                    function.from = new ModbusPollingFrom();
                    function.from.type = "command";
                    function.from.index = index;
                    function.index = index;
        function.request = ModbusPollingRequests.write("06", 1, ModbusPollingRequests.field(1, "写入值", "uint16", 200));
        function.response = null;
        return function;
    }

    private static ModbusPollingResponseField field(String name) {
        ModbusPollingResponseField field = new ModbusPollingResponseField();
        field.field = name;
        return field;
    }

    private static ModbusPollingResponseFieldBit bit(int offset, String name) {
        ModbusPollingResponseFieldBit bit = new ModbusPollingResponseFieldBit();
        bit.offset = offset;
        bit.field = name;
        return bit;
    }

    /** 定义与影子必须指同一个服务（对账按服务做），所以 id 从 {@link #SERVICE_ID} 还原。 */
    private static ModbusService service(ModbusPolling... functions) {
        ModbusService service = new ModbusService();
        service.id = new ObjectId(SERVICE_ID);
        service.name = "svc";
        service.functions = new ArrayList<>(List.of(functions));
        return service;
    }

    private static ModbusServiceLastPolling shadow(String serviceId, Integer pollingIndex, Map<String, Object> fields) {
        ModbusServiceLastPolling shadow = new ModbusServiceLastPolling();
        shadow.id = new ObjectId();
        shadow.serviceId = serviceId;
        shadow.pollingIndex = pollingIndex;
        shadow.fields = fields == null ? null : new LinkedHashMap<>(fields);
        return shadow;
    }

    private static Map<String, Object> map(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }

    private static Map<String, Object> map(String k1, Object v1, String k2, Object v2, String k3, Object v3) {
        Map<String, Object> map = map(k1, v1, k2, v2);
        map.put(k3, v3);
        return map;
    }

    private static Map<String, Object> map(String k1, Object v1, String k2, Object v2, String k3, Object v3,
                                           String k4, Object v4) {
        Map<String, Object> map = map(k1, v1, k2, v2, k3, v3);
        map.put(k4, v4);
        return map;
    }
}
