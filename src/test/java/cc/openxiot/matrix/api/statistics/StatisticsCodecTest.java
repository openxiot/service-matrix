package cc.openxiot.matrix.api.statistics;

import cc.openxiot.matrix.api.statistics.StatisticsQueryService.AlarmStats;
import cc.openxiot.matrix.api.statistics.StatisticsQueryService.Bucket;
import cc.openxiot.matrix.api.statistics.StatisticsQueryService.Count;
import cc.openxiot.matrix.api.statistics.StatisticsQueryService.DeviceStats;
import cc.openxiot.matrix.api.statistics.StatisticsQueryService.FailureStats;
import cc.openxiot.matrix.api.statistics.StatisticsQueryService.Overview;
import cc.openxiot.matrix.api.statistics.StatisticsQueryService.FdStats;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link StatisticsCodec} 的线格式：键名与前端 {@code OverviewStatisticsCodec} 一一对应，
 * 时间一律 epoch 毫秒。
 *
 * <p>这一层没有逻辑，但它是**两端的契约**：键名改一个字前端就静默读成 undefined
 * （首页变成一排 0 或空图）。所以这里逐字钉住整棵树的形状。</p>
 */
class StatisticsCodecTest {

    private static final long FROM = 1_700_000_000_000L;
    private static final long HOUR = 3_600_000L;

    @Test
    void encodesTheWholeBoardWithEpochMillis() {
        JsonObject body = StatisticsCodec.encode(new Overview(
                new Date(FROM), new Date(FROM + HOUR),
                new DeviceStats(3, 2, List.of(new Count("dtu", 2), new Count("meter", 1))),
                new FdStats(2, List.of(new Count("cfg-1", 1), new Count("", 1))),
                new AlarmStats(4, List.of(new Count("温度过高", 3), new Count("UNKNOWN", 1)),
                        List.of(new Bucket(FROM, 3), new Bucket(FROM + HOUR, 1))),
                new FailureStats(1, List.of(new Bucket(FROM, 0), new Bucket(FROM + HOUR, 1)))));

        assertEquals(FROM, body.getLong("from"));
        assertEquals(FROM + HOUR, body.getLong("to"));

        JsonObject devices = body.getJsonObject("devices");
        assertEquals(3, devices.getInteger("total"));
        assertEquals(2, devices.getInteger("online"));
        assertEquals(new JsonObject().put("type", "dtu").put("count", 2),
                devices.getJsonArray("byType").getJsonObject(0));

        // 服务：键是 configId（空串保留，前端据此显示「未定义」）
        assertEquals(new JsonObject().put("configId", "").put("count", 1),
                body.getJsonObject("fds").getJsonArray("byConfig").getJsonObject(1));

        // 告警：文本原样下发（用户数据不翻译），时间线是 {at, count}
        JsonObject alarms = body.getJsonObject("alarms");
        assertEquals(4, alarms.getInteger("total"));
        assertEquals(new JsonObject().put("text", "温度过高").put("count", 3),
                alarms.getJsonArray("byText").getJsonObject(0));
        assertEquals(new JsonObject().put("at", FROM).put("count", 3),
                alarms.getJsonArray("hourly").getJsonObject(0));

        // 故障：零桶照样下发（密集零填充），前端不必猜「没数据还是没请求」
        JsonObject failures = body.getJsonObject("failures");
        assertEquals(1, failures.getInteger("total"));
        assertEquals(new JsonObject().put("at", FROM).put("count", 0),
                failures.getJsonArray("hourly").getJsonObject(0));
    }
}
