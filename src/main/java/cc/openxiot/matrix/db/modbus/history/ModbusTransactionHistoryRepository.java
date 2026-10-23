package cc.openxiot.matrix.db.modbus.history;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.result.DeleteResult;
import io.quarkus.mongodb.FindOptions;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@link ModbusTransactionHistory} 的数据访问（Panache，实体固定落库 {@code modbus}/集合
 * {@code service-polling-histories}）。
 *
 * <p>写侧是「每次调用追加一条」；读侧（轮询明细、失败统计）由上层模块按
 * {@code (serviceId, pollingIndex, at)} 过滤，索引在这里建好。操作日志按采集频率线性增长，
 * 所以 {@code at} 上要有 TTL 保留策略兜底（与 {@link ModbusRegistryHistoryRepository} 同口径）。</p>
 */
@ApplicationScoped
public class ModbusTransactionHistoryRepository {

    @Inject
    ReactiveMongoClient mongoClient;

    private ReactiveMongoCollection<ModbusTransactionHistory> collection() {
        return mongoClient.getDatabase("modbus")
                .getCollection("service-polling-histories", ModbusTransactionHistory.class);
    }

    /** 启动时确保索引存在：{@code (serviceId, pollingIndex, at)} 复合索引（前两段等值 + {@code at} 范围），
     *  外加（{@code retentionSeconds > 0} 时）{@code at} 上的 TTL 索引。 */
    public Uni<Void> ensureIndexes(long retentionSeconds) {
        return collection()
                .createIndex(Indexes.ascending("serviceId", "pollingIndex", "at"))
                .onFailure().recoverWithNull()
                .chain(ignored -> ensureRetention(retentionSeconds));
    }

    private Uni<Void> ensureRetention(long retentionSeconds) {
        if (retentionSeconds <= 0) {
            return Uni.createFrom().voidItem();
        }
        return collection()
                .createIndex(Indexes.ascending("at"),
                        new IndexOptions().expireAfter(retentionSeconds, TimeUnit.SECONDS))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /** 附加一条操作日志（每次调用一条；失败静默——操作日志只是副作用，不拖累调用）。 */
    public Uni<Void> insert(ModbusTransactionHistory row) {
        if (row == null) {
            return Uni.createFrom().voidItem();
        }
        if (row.id == null) {
            row.id = new org.bson.types.ObjectId();
        }
        return collection().insertOne(row)
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /**
     * 某段区间内的**失败调用**（{@code ok=false}），按 {@code at} **倒序**（最近的在前，故障清单这么看）。
     *
     * <p>{@code pollingIndex} 与 {@code type} 为 null 表示不限（分别是「整个服务」与「所有失败分类」）。
     * 索引是 {@code (serviceId, pollingIndex, at)}：不带 {@code pollingIndex} 时只有第一段用得上，
     * 排序要靠内存完成 —— 我们要的就是倒序，直接让查询倒序排即可。</p>
     *
     * @param limit 条数上限；调用方传「上限 + 1」以便判出「还有更早的没取回来」
     */
    public Uni<List<ModbusTransactionHistory>> findFailureRange(String serviceId, Integer pollingIndex,
                                                                   String type, Date from, Date to, int limit) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("serviceId", serviceId));
        filters.add(Filters.eq("ok", false));
        if (pollingIndex != null) {
            filters.add(Filters.eq("pollingIndex", pollingIndex));
        }
        if (type != null && !type.isBlank()) {
            filters.add(Filters.eq("type", type));
        }
        filters.add(Filters.gte("at", from));
        filters.add(Filters.lte("at", to));

        return collection()
                .find(Filters.and(filters),
                        new FindOptions().sort(Sorts.descending("at")).limit(limit))
                .collect().asList();
    }

    /**
     * 某个服务在一段区间内的失败调用**时间线**，只取统计要用的那一列：{@code at}。
     *
     * <p><b>不打 limit</b>（调用方是统计，截断会让「今日异常」变成静默的下限，量级由窗口上限兜住）、
     * **不排序**（统计在内存里分桶，顺序没有意义）。这与告警时间线同口径。</p>
     */
    public Uni<List<ModbusTransactionHistory>> findFailureTimeline(String serviceId, Date from, Date to) {
        return collection()
                .find(Filters.and(Filters.eq("serviceId", serviceId), Filters.eq("ok", false),
                                Filters.gte("at", from), Filters.lte("at", to)),
                        new FindOptions().projection(Projections.include("at")))
                .collect().asList();
    }

    /** 级联删除一批服务的操作日志（服务没了就没有读它的入口，留着是存储垃圾）。 */
    public Uni<Long> deleteByServiceIds(List<String> serviceIds) {
        if (serviceIds == null || serviceIds.isEmpty()) {
            return Uni.createFrom().item(0L);
        }
        return collection()
                .deleteMany(Filters.in("serviceId", serviceIds))
                .map(DeleteResult::getDeletedCount);
    }
}