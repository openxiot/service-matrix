package cc.openxiot.matrix.api.modbus.alarm;

import cc.openxiot.matrix.api.modbus.alarm.ModbusAlarmQueryService.AlarmResult;
import cc.openxiot.matrix.db.modbus.alarm.ModbusAlarm;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModbusAlarmQueryService#merge} 的合并规则：空间级查询（不传 serviceId）把各服务各查一次的结果
 * 并成一条清单，这一步全在内存里（{@code @QuarkusTest} 要连 Mongo，这个仓库跑不了）。
 *
 * <p>断言的重点不是「排了个序」，而是**逐服务先按 {@code limit + 1} 截一刀再合并，得到的确实是
 * 全局前 limit 条** —— 这是那套接口敢只查一次就下结论的依据；另加「截断标志」与「同一时刻怎么排」
 * 两处边界。与 {@code ModbusTransactionHistoryServiceTest} 同结构，两处的口径本该一致。</p>
 */
class ModbusAlarmQueryServiceTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final long SECOND = 1_000L;
    private static final int LIMIT = 3;

    @Test
    void mergesServicesIntoOneTimelineNewestFirst() {
        // 三个服务各一条：跨服务按时刻倒序，而不是「服务 1 的全部在前」
        AlarmResult result = ModbusAlarmQueryService.merge(new Date(T0), new Date(T0 + SECOND * 10), LIMIT,
                List.of(alarm("a", T0 + SECOND), alarm("b", T0 + SECOND * 5), alarm("c", T0 + SECOND * 3)));

        assertEquals(List.of("b", "c", "a"), serviceIds(result));
        assertFalse(result.truncated());
        assertEquals(LIMIT, result.limit());
    }

    @Test
    void keepsOnlyMostRecentRowsAndFlagsTruncation() {
        // 5 条只要前 3 条：多出来的（更早的）被截掉，并置 truncated（前端要提示缩小时间范围）
        AlarmResult result = ModbusAlarmQueryService.merge(new Date(T0), new Date(T0 + SECOND * 10), LIMIT,
                List.of(alarm("a", T0 + SECOND * 5), alarm("b", T0 + SECOND * 4),
                        alarm("c", T0 + SECOND * 3), alarm("d", T0 + SECOND * 2),
                        alarm("e", T0 + SECOND)));

        assertEquals(List.of("a", "b", "c"), serviceIds(result));
        assertTrue(result.truncated());
    }

    @Test
    void exactlyAtTheLimitIsNotTruncated() {
        // 边界：正好等于 limit 时不算截断（判定是「多于才截」）—— 与单服务那条路同一个口径
        AlarmResult result = ModbusAlarmQueryService.merge(new Date(T0), new Date(T0 + SECOND * 10), LIMIT,
                List.of(alarm("a", T0 + SECOND * 3), alarm("b", T0 + SECOND * 2), alarm("c", T0 + SECOND)));

        assertEquals(3, result.items().size());
        assertFalse(result.truncated());
    }

    @Test
    void sameInstantKeepsTheGivenOrder() {
        // 同一时刻（同一轮采集里两个字段一起越限，at 完全一样）保持传入顺序：sort 是稳定的，
        // 传入顺序就是服务清单的顺序，于是结果不会在两次查询之间跳来跳去
        AlarmResult result = ModbusAlarmQueryService.merge(new Date(T0), new Date(T0 + SECOND * 10), LIMIT,
                List.of(alarm("a", T0 + SECOND), alarm("b", T0 + SECOND), alarm("c", T0 + SECOND)));

        assertEquals(List.of("a", "b", "c"), serviceIds(result));
    }

    @Test
    void emptyInputGivesEmptyResult() {
        // 空间里一个服务都没有：空清单，不是错误
        AlarmResult result = ModbusAlarmQueryService.merge(new Date(T0), new Date(T0 + SECOND), LIMIT, List.of());

        assertTrue(result.items().isEmpty());
        assertFalse(result.truncated());
    }

    @Test
    void rowsWithoutTimeGoLast() {
        // at 缺失是老数据的可能形态（写入侧必带，但读侧不该因此排不出结果）：按 0 算，倒序里落最后
        AlarmResult result = ModbusAlarmQueryService.merge(new Date(T0), new Date(T0 + SECOND * 10), LIMIT,
                List.of(alarm("a", T0 + SECOND), alarm("b", null)));

        assertEquals(List.of("a", "b"), serviceIds(result));
    }

    @Test
    void mergeDoesNotCareAboutOpenOrHandled() {
        // 合并只按时刻排，不按状态分堆：一条已恢复的告警仍可能比一条未恢复的更新，
        // 列表要的是时间线（「刚才发生了什么」），不是「未处理的排前面」——
        // 后者是筛选（open/handled 参数）的职责，不是排序的
        AlarmResult result = ModbusAlarmQueryService.merge(new Date(T0), new Date(T0 + SECOND * 10), LIMIT,
                List.of(closed("a", T0 + SECOND * 5), alarm("b", T0 + SECOND)));

        assertEquals(List.of("a", "b"), serviceIds(result));
    }

    // -- helpers --

    private static ModbusAlarm alarm(String serviceId, Long at) {
        return row(serviceId, at, null);
    }

    /** 已恢复的一条：合并仍按 at 排（见 {@link #mergeDoesNotCareAboutOpenOrHandled}）。 */
    private static ModbusAlarm closed(String serviceId, Long at) {
        return row(serviceId, at, new Date(at + SECOND));
    }

    private static ModbusAlarm row(String serviceId, Long at, Date recoveredAt) {
        ModbusAlarm alarm = new ModbusAlarm();
        alarm.serviceId = serviceId;
        alarm.transactionIndex = 1;
        alarm.field = "进水温度";
        alarm.level = "WARN";
        alarm.text = "温度过高";
        alarm.at = at == null ? null : new Date(at);
        alarm.recoveredAt = recoveredAt;
        return alarm;
    }

    /** 用 serviceId 当断言里的标识：它正是空间级清单里「这条属于谁」的依据。 */
    private static List<String> serviceIds(AlarmResult result) {
        return result.items().stream().map(item -> item.serviceId).toList();
    }
}
