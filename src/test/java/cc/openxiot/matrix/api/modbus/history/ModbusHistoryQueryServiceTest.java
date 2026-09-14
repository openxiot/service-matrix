package cc.openxiot.matrix.api.modbus.history;

import cc.openxiot.matrix.api.modbus.history.ModbusHistoryQueryService.FailureResult;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceFailure;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModbusHistoryQueryService#merge} 的合并规则：空间级故障查询（不传 serviceId）把各服务
 * 各查一次的结果并成一条清单，这一步全在内存里，是这个仓库少数跑得起来的测试
 * （{@code @QuarkusTest} 要连 Mongo）。
 *
 * <p>断言的重点不是「排了个序」，而是**逐服务先按 limit + 1 截一刀再合并，得到的确实是全局前 limit 条**
 * —— 这是那套接口敢只查一次就下结论的依据；另加「截断标志」与「同一时刻怎么排」两处边界。</p>
 */
class ModbusHistoryQueryServiceTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final long SECOND = 1_000L;
    private static final int LIMIT = 3;

    @Test
    void mergesServicesIntoOneTimelineNewestFirst() {
        // 三个服务各一条：跨服务按时刻倒序，而不是「服务 1 的全部在前」
        FailureResult result = ModbusHistoryQueryService.merge(new Date(T0), new Date(T0 + SECOND * 10), LIMIT,
                List.of(failure("a", T0 + SECOND), failure("b", T0 + SECOND * 5), failure("c", T0 + SECOND * 3)));

        assertEquals(List.of("b", "c", "a"), ids(result));
        assertFalse(result.truncated());
        assertEquals(LIMIT, result.limit());
    }

    @Test
    void keepsOnlyMostRecentRowsAndFlagsTruncation() {
        // 5 条只要前 3 条：多出来的（更早的）被截掉，并置 truncated（前端要提示缩小时间范围）
        FailureResult result = ModbusHistoryQueryService.merge(new Date(T0), new Date(T0 + SECOND * 10), LIMIT,
                List.of(failure("a", T0 + SECOND * 5), failure("b", T0 + SECOND * 4),
                        failure("c", T0 + SECOND * 3), failure("d", T0 + SECOND * 2),
                        failure("e", T0 + SECOND)));

        assertEquals(List.of("a", "b", "c"), ids(result));
        assertTrue(result.truncated());
    }

    @Test
    void exactlyAtTheLimitIsNotTruncated() {
        // 边界：正好等于 limit 时不算截断（判定是「多于才截」）—— 与单服务那条路同一个口径
        FailureResult result = ModbusHistoryQueryService.merge(new Date(T0), new Date(T0 + SECOND * 10), LIMIT,
                List.of(failure("a", T0 + SECOND * 3), failure("b", T0 + SECOND * 2),
                        failure("c", T0 + SECOND)));

        assertEquals(3, result.items().size());
        assertFalse(result.truncated());
    }

    @Test
    void sameInstantKeepsTheGivenOrder() {
        // 同一时刻（去重后的故障在同一毫秒上撞车）保持传入顺序：sort 是稳定的，
        // 传入顺序就是服务清单的顺序，于是结果不会在两次查询之间跳来跳去
        FailureResult result = ModbusHistoryQueryService.merge(new Date(T0), new Date(T0 + SECOND * 10), LIMIT,
                List.of(failure("a", T0 + SECOND), failure("b", T0 + SECOND), failure("c", T0 + SECOND)));

        assertEquals(List.of("a", "b", "c"), ids(result));
    }

    @Test
    void emptyInputGivesEmptyResult() {
        // 空间里一个服务都没有：空清单，不是错误
        FailureResult result = ModbusHistoryQueryService.merge(new Date(T0), new Date(T0 + SECOND), LIMIT, List.of());

        assertTrue(result.items().isEmpty());
        assertFalse(result.truncated());
    }

    @Test
    void rowsWithoutTimeGoLast() {
        // at 缺失是老数据的可能形态（写入侧必带，但读侧不该因此排不出结果）：按 0 算，倒序里落最后
        FailureResult result = ModbusHistoryQueryService.merge(new Date(T0), new Date(T0 + SECOND * 10), LIMIT,
                List.of(failure("a", T0 + SECOND), failure("b", null)));

        assertEquals(List.of("a", "b"), ids(result));
    }

    // -- helpers --

    private static ModbusServiceFailure failure(String serviceId, Long at) {
        ModbusServiceFailure failure = new ModbusServiceFailure();
        failure.serviceId = serviceId;
        failure.functionIndex = 1;
        failure.type = "NO_RESPONSE";
        failure.message = "no response";
        failure.at = at == null ? null : new Date(at);
        return failure;
    }

    /** 用 serviceId 当断言里的标识：它正是空间级清单里「这条属于谁」的依据 */
    private static List<String> ids(FailureResult result) {
        return result.items().stream().map(item -> item.serviceId).toList();
    }
}
