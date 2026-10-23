package cc.openxiot.matrix.db.modbus.history;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.result.DeleteResult;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@link ModbusServicePollingHistory} 的数据访问（Panache，实体固定落库 {@code modbus}/集合
 * {@code service-polling-histories}）。
 *
 * <p>写侧是「每次调用追加一条」；读侧（轮询明细、失败统计）由上层模块按
 * {@code (serviceId, pollingIndex, at)} 过滤，索引在这里建好。操作日志按采集频率线性增长，
 * 所以 {@code at} 上要有 TTL 保留策略兜底（与 {@link ModbusServiceRegistryHistoryRepository} 同口径）。</p>
 */
@ApplicationScoped
public class ModbusServicePollingHistoryRepository {

    @Inject
    ReactiveMongoClient mongoClient;

    private ReactiveMongoCollection<ModbusServicePollingHistory> collection() {
        return mongoClient.getDatabase("modbus")
                .getCollection("service-polling-histories", ModbusServicePollingHistory.class);
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
    public Uni<Void> insert(ModbusServicePollingHistory row) {
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