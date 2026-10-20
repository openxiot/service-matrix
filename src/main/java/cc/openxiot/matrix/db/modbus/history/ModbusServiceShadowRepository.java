package cc.openxiot.matrix.db.modbus.history;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.result.DeleteResult;
import io.quarkus.mongodb.FindOptions;
import io.quarkus.mongodb.panache.reactive.ReactivePanacheMongoRepository;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.types.ObjectId;

import java.util.List;

/**
 * {@link ModbusServiceShadow} 的数据访问（Panache，实体固定落库 {@code modbus}/集合 {@code service-shadows}）。
 *
 * <p>写入只有两条路，与 {@code ModbusServiceRepository} 同口径：不存在走 {@link #insert}（主键由 Panache 生成），
 * 已存在走 {@link #replace}（带原 {@code _id} 全量替换）。不用 upsert ——
 * 调用方本来就是先读影子拿到比对基准，手里的 {@code _id} 直接复用，省掉一次 upsert 语义的边界情况。</p>
 */
@ApplicationScoped
public class ModbusServiceShadowRepository implements ReactivePanacheMongoRepository<ModbusServiceShadow> {

    private ReactiveMongoCollection<ModbusServiceShadow> collection() {
        return mongoCollection();
    }

    /**
     * 启动时确保 {@code (serviceId, pollingIndex)} 唯一索引存在
     * （幂等；失败仅告警，不阻断启动，与 {@code ModbusServiceRepository.ensureIndexes} 同口径）。
     */
    public Uni<Void> ensureIndexes() {
        return collection()
                .createIndex(Indexes.ascending("serviceId", "pollingIndex"), new IndexOptions().unique(true))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /** 取某个方法的当前值，没有则 null：既是前端要的最新值，也是下一次采集的比对基准。 */
    public Uni<ModbusServiceShadow> find(String serviceId, Integer pollingIndex) {
        if (serviceId == null || pollingIndex == null) {
            return Uni.createFrom().nullItem();
        }
        return collection()
                .find(Filters.and(Filters.eq("serviceId", serviceId), Filters.eq("pollingIndex", pollingIndex)))
                .collect().first();
    }

    /**
     * 某个服务下所有方法的当前值，按方法序号升序（当前值接口用）。
     *
     * <p>只返回**有影子**的方法：没有的表示从没被调用过，与「有值但很旧」是两回事。</p>
     */
    public Uni<List<ModbusServiceShadow>> listByServiceId(String serviceId) {
        if (serviceId == null || serviceId.isBlank()) {
            return Uni.createFrom().item(List.of());
        }
        return collection()
                .find(Filters.eq("serviceId", serviceId),
                        new FindOptions().sort(Sorts.ascending("pollingIndex")))
                .collect().asList();
    }

    /** 新增一条（{@code _id} 由 Panache 生成并回写到 {@code shadow.id}）。 */
    public Uni<Void> insert(ModbusServiceShadow shadow) {
        return persist(shadow).replaceWithVoid();
    }

    /** 全量替换一条（{@code shadow.id} 必须是已落库的原值，由调用方从 {@link #find} 的结果带过来）。 */
    public Uni<Void> replace(ModbusServiceShadow shadow) {
        return collection()
                .replaceOne(Filters.eq("_id", shadow.id), shadow)
                .replaceWithVoid();
    }

    /**
     * 级联删除一批服务的影子：服务（或依赖设备）被删掉后，影子是再也收不到更新的派生状态，
     * 留着只会越来越陈旧。删除条数为 0 不算失败。
     */
    public Uni<Long> deleteByServiceIds(List<String> serviceIds) {
        if (serviceIds == null || serviceIds.isEmpty()) {
            return Uni.createFrom().item(0L);
        }
        return collection()
                .deleteMany(Filters.in("serviceId", serviceIds))
                .map(DeleteResult::getDeletedCount);
    }

    /**
     * 删掉指定的几条（影子对账用：定义里已经删掉的方法，它的影子再也不会被更新）。
     * 按 {@code _id} 删而不是按 {@code (serviceId, pollingIndex)}：对账手里拿到的就是读出来的文档，
     * 主键现成，且不必再拼一次复合条件。删除条数为 0 不算失败。
     */
    public Uni<Long> deleteByIds(List<ObjectId> ids) {
        if (ids == null || ids.isEmpty()) {
            return Uni.createFrom().item(0L);
        }
        return collection()
                .deleteMany(Filters.in("_id", ids))
                .map(DeleteResult::getDeletedCount);
    }
}
