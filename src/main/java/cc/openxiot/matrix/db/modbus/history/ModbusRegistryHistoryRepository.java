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

import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@link ModbusRegistryHistory} 的数据访问（Panache，实体固定落库 {@code modbus}/集合
 * {@code service-registry-histories}）。
 *
 * <p>写侧只有「批量追加」与「级联删除」；读侧两个查询（carry-in 与区间）对应曲线取数。
 * 一次区间查询同时带回成功与失败事件（{@code failure=true} 的无 {@code value}），由消费方筛开：
 * 成功的排成值曲线、失败的在时刻上画竖线 —— 一条源回答两件事。</p>
 */
@ApplicationScoped
public class ModbusRegistryHistoryRepository {

    @Inject
    ReactiveMongoClient mongoClient;

    private ReactiveMongoCollection<ModbusRegistryHistory> collection() {
        return mongoClient.getDatabase("modbus")
                .getCollection("service-registry-histories", ModbusRegistryHistory.class);
    }

    /**
     * 启动时确保索引存在：{@code (serviceId, registerIndex, at)} 复合索引（前三段等值 + {@code at} 范围），
     * 外加（{@code retentionSeconds > 0} 时）{@code at} 上的 TTL 索引。
     */
    public Uni<Void> ensureIndexes(long retentionSeconds) {
        return collection()
                .createIndex(Indexes.ascending("serviceId", "registerIndex", "at"))
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

    /** 批量追加一批事件（一次成功的读 → 每只寄存器一条）。 */
    public Uni<Void> insertAll(List<ModbusRegistryHistory> rows) {
        if (rows == null || rows.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        return collection().insertMany(rows).replaceWithVoid();
    }

    /** 区间起点之前最近的一条事件（carry-in）：曲线从 {@code from} 起的值要它续上。 */
    public Uni<ModbusRegistryHistory> findCarryIn(String serviceId, Integer registerIndex, Date from) {
        return collection()
                .find(pointFilter(serviceId, registerIndex, Filters.lt("at", from)),
                        new FindOptions().sort(Sorts.descending("at")).limit(1))
                .collect().first();
    }

    /** 区间 {@code [from, to]} 内的事件，按 {@code at} 升序。投影只取画图要用的几键。 */
    public Uni<List<ModbusRegistryHistory>> findRange(String serviceId, Integer registerIndex,
                                                             Date from, Date to, int limit) {
        return collection()
                .find(pointFilter(serviceId, registerIndex,
                                Filters.and(Filters.gte("at", from), Filters.lte("at", to))),
                        new FindOptions()
                                .sort(Sorts.ascending("at"))
                                .limit(limit)
                                .projection(Projections.include("value", "at", "failure", "message")))
                .collect().asList();
    }

    private static Bson pointFilter(String serviceId, Integer registerIndex, Bson at) {
        return Filters.and(Filters.eq("serviceId", serviceId),
                Filters.eq("registerIndex", registerIndex), at);
    }

    /** 级联删除一批服务的寄存器历史（服务没了就没有读它的入口，留着是存储垃圾）。 */
    public Uni<Long> deleteByServiceIds(List<String> serviceIds) {
        if (serviceIds == null || serviceIds.isEmpty()) {
            return Uni.createFrom().item(0L);
        }
        return collection()
                .deleteMany(Filters.in("serviceId", serviceIds))
                .map(DeleteResult::getDeletedCount);
    }
}