package cc.openxiot.matrix.db.modbus.history;

import cc.openxiot.matrix.api.modbus.history.ModbusHistoryRetention;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.result.DeleteResult;
import io.quarkus.mongodb.FindOptions;
import io.quarkus.mongodb.panache.reactive.ReactivePanacheMongoRepository;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.jboss.logging.Logger;

import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@link ModbusServiceHistory} 的数据访问（Panache，实体固定落库 {@code modbus}/集合 {@code service-histories}）。
 *
 * <p>写侧只有「批量追加」与「级联删除」；读侧两个查询（carry-in 与区间）对应读取接口
 * （见 Modbus-History-TODO.md 第二步）。查询只返回实体，**不在这里排序口径之外做任何折叠**：
 * 降采样是纯函数 {@code ModbusHistoryWindow} 的事，好让它跑得了单测。</p>
 */
@ApplicationScoped
public class ModbusServiceHistoryRepository implements ReactivePanacheMongoRepository<ModbusServiceHistory> {

    @Inject
    Logger logger;

    private ReactiveMongoCollection<ModbusServiceHistory> collection() {
        return mongoCollection();
    }

    /**
     * 启动时确保索引存在：{@code (serviceId, functionIndex, field, at)} 复合索引，
     * 外加（{@code retentionSeconds > 0} 时）{@code at} 上的 TTL 索引（见 Modbus-History.md 第六节）。
     *
     * <p>这条复合索引支撑「某点某时间段倒序取 N 条」：前三段等值 + {@code at} 范围，
     * 排序可正可反，不需要第二条件索引。</p>
     *
     * <p>顺序有意义：先建复合索引（顺带把集合带出来），再去看有哪些索引 ——
     * 集合还不存在时 {@code listIndexes} 的行为在各版本上不一致，不靠它。</p>
     *
     * @param retentionSeconds TTL 秒数；{@code <= 0} 表示关（不建，且把已有的删掉）
     */
    public Uni<Void> ensureIndexes(long retentionSeconds) {
        return collection()
                .createIndex(Indexes.ascending("serviceId", "functionIndex", "field", "at"))
                .onFailure().recoverWithNull()
                .chain(ignored -> ensureRetention(retentionSeconds));
    }

    /**
     * 把保留策略落到 {@code at} 上的 TTL 索引：该建就建、秒数变了就重建、关了就删掉。
     *
     * <p>与另外四个仓储的静默口径不同，这条失败要留告警：索引建不上只影响查询快慢，
     * 而保留建不上意味着**历史永远不会被删**（或者反过来，想关却没关掉，数据照删）——
     * 那是运维看不见就会出事的那一类。是否致命仍不由这里决定，不阻断启动。</p>
     */
    private Uni<Void> ensureRetention(long retentionSeconds) {
        return collection().listIndexes().collect().asList()
                .map(ModbusServiceHistoryRepository::findTtlIndex)
                .chain(existing -> applyRetention(existing, retentionSeconds))
                .onFailure().invoke(e -> logger.warnv(
                        "history retention not applied, retention = {0}s: {1}", retentionSeconds, e.toString()))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    private Uni<Void> applyRetention(TtlIndex existing, long retentionSeconds) {
        Integer existingSeconds = existing == null ? null : existing.seconds();
        ModbusHistoryRetention.Action action = ModbusHistoryRetention.decide(existingSeconds, retentionSeconds);
        if (action == ModbusHistoryRetention.Action.NONE) {
            return Uni.createFrom().voidItem();
        }
        Uni<Void> dropped = existing == null
                ? Uni.createFrom().voidItem()
                : collection().dropIndex(existing.name());
        if (action == ModbusHistoryRetention.Action.DROP) {
            return dropped.onItem().invoke(ignored -> logger.info(
                    "history retention off: ttl index dropped, nothing is deleted by the server"));
        }
        return dropped
                .chain(ignored -> collection().createIndex(Indexes.ascending("at"),
                        new IndexOptions().expireAfter(retentionSeconds, TimeUnit.SECONDS)))
                .onItem().invoke(name -> logger.infov(
                        "history retention: ttl index {0} on at, {1}s ({2} days) - recreated: {3}",
                        name, retentionSeconds, retentionSeconds / ModbusHistoryRetention.SECONDS_PER_DAY,
                        action == ModbusHistoryRetention.Action.RECREATE))
                .replaceWithVoid();
    }

    /**
     * 从 {@code listIndexes} 的结果里挑出 {@code at} 上那条 TTL 索引：键必须**恰好**是 {@code {at: 1}}
     * （单字段升序，{@code _id_} 与四条复合索引都不满足），且带 {@code expireAfterSeconds}。
     *
     * <p>认错了的后果不小，两种方向都坏：认成「没有」会与既有索引撞名
     * （`IndexOptionsConflict`，改天数永远不生效、每次启动一条告警）；
     * 认成「有但秒数不同」会每次启动都白删白建一遍。所以包级可见、由
     * {@code ModbusServiceHistoryRepositoryTest} 拿真的索引文档形态钉住。</p>
     *
     * <p>秒数按 {@link Number} 读：vCore 上回来的是 int32 还是 int64 没有把握，
     * 按 {@code Integer} 强转会在某个版本上炸。</p>
     */
    static TtlIndex findTtlIndex(List<Document> indexes) {
        for (Document index : indexes) {
            if (!(index.get("key") instanceof Document key) || key.size() != 1) {
                continue;
            }
            if (!(key.get("at") instanceof Number direction) || direction.intValue() != 1) {
                continue;
            }
            if (!(index.get("expireAfterSeconds") instanceof Number seconds)) {
                continue;
            }
            return new TtlIndex(index.getString("name"), seconds.intValue());
        }
        return null;
    }

    /** 库里那条 TTL 索引：改它得靠名字（{@code dropIndex}），拿它得看秒数（要不要重建）。 */
    record TtlIndex(String name, int seconds) {
    }

    /** 批量追加一批样本：一次采集一批（一次采集里可能有多个字段同时变了）。 */
    public Uni<Void> insertAll(List<ModbusServiceHistory> rows) {
        if (rows == null || rows.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        return collection().insertMany(rows).replaceWithVoid();
    }

    /**
     * 区间起点之前最近的一条样本（**carry-in 点**）：没有它，前端就不知道 {@code from} 时刻的值，
     * 曲线从起点开始是空白（见 Modbus-History.md 第五节）。没有则 null。
     *
     * <p>只读一条：这条索引（前三段等值 + {@code at} 范围）会让它落在倒序的第一条上。</p>
     */
    public Uni<ModbusServiceHistory> findCarryIn(String serviceId, Integer functionIndex, String field, Date from) {
        return collection()
                .find(pointFilter(serviceId, functionIndex, field, Filters.lt("at", from)),
                        new FindOptions().sort(Sorts.descending("at")).limit(1))
                .collect().first();
    }

    /**
     * 区间 {@code [from, to]} 内的样本，按 {@code at} 升序。
     *
     * <p>升序由这里定死、{@link ModbusHistoryWindow} 不再排序：排序口径只该有一处。
     * 投影只取 {@code value / at / keepalive} —— 每行重复的 {@code serviceId / functionIndex / field}
     * 在结果里是常量，取回来只是白付流量。</p>
     *
     * @param limit 条数上限；调用方传「上限 + 1」以便判出「超了」（见 {@code ModbusHistoryQueryService}）
     */
    public Uni<List<ModbusServiceHistory>> findRange(String serviceId, Integer functionIndex, String field,
                                                     Date from, Date to, int limit) {
        return collection()
                .find(pointFilter(serviceId, functionIndex, field,
                                Filters.and(Filters.gte("at", from), Filters.lte("at", to))),
                        new FindOptions()
                                .sort(Sorts.ascending("at"))
                                .limit(limit)
                                .projection(Projections.include("value", "at", "keepalive")))
                .collect().asList();
    }

    private static Bson pointFilter(String serviceId, Integer functionIndex, String field, Bson at) {
        return Filters.and(Filters.eq("serviceId", serviceId),
                Filters.eq("functionIndex", functionIndex),
                Filters.eq("field", field),
                at);
    }

    /**
     * 级联删除一批服务的历史：服务被删掉后，前端再没有入口能查到这些点位，
     * 留着就是永远没人读、也永远删不掉的存储垃圾。删除条数为 0 不算失败。
     *
     * <p>（要改成「删服务但保留历史」，去掉调用方那一处级联即可 —— 见 Modbus-History.md 第六节。）</p>
     */
    public Uni<Long> deleteByServiceIds(List<String> serviceIds) {
        if (serviceIds == null || serviceIds.isEmpty()) {
            return Uni.createFrom().item(0L);
        }
        return collection()
                .deleteMany(Filters.in("serviceId", serviceIds))
                .map(DeleteResult::getDeletedCount);
    }
}
