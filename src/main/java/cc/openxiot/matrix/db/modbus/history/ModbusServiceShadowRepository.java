package cc.openxiot.matrix.db.modbus.history;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.DeleteResult;
import io.quarkus.mongodb.FindOptions;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ModbusServiceShadow} 的数据访问（Panache，实体固定落库 {@code modbus}/集合 {@code service-shadows}）。
 *
 * <p>按 {@code (serviceId, registerIndex)} 键、写入只有「不存在 insert / 已存在 replace」两条路
 * （与 {@code ModbusServiceLastPollingRepository} 同口径）：调用方先读影子拿到比对基准，
 * 手里的 {@code _id} 直接复用，省一次 upsert 语义的边界情况。</p>
 */
@ApplicationScoped
public class ModbusServiceShadowRepository {

    @Inject
    ReactiveMongoClient mongoClient;

    private ReactiveMongoCollection<ModbusServiceShadow> collection() {
        return mongoClient.getDatabase("modbus").getCollection("service-shadows", ModbusServiceShadow.class);
    }

    /** 启动时确保 {@code (serviceId, registerIndex)} 唯一索引存在（幂等；失败仅告警，不阻断启动）。 */
    public Uni<Void> ensureIndexes() {
        return collection()
                .createIndex(Indexes.ascending("serviceId", "registerIndex"), new IndexOptions().unique(true))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /** 取某只寄存器的当前值，没有则 null：既是前端要的最新值，也是下一次采集的比对基准。 */
    public Uni<ModbusServiceShadow> find(String serviceId, Integer registerIndex) {
        if (serviceId == null || registerIndex == null) {
            return Uni.createFrom().nullItem();
        }
        return collection()
                .find(Filters.and(Filters.eq("serviceId", serviceId), Filters.eq("registerIndex", registerIndex)))
                .collect().first();
    }

    /** 某个服务下所有寄存器的当前值，按寄存器序号升序。只返回**有影子**的寄存器。 */
    public Uni<List<ModbusServiceShadow>> listByServiceId(String serviceId) {
        if (serviceId == null || serviceId.isBlank()) {
            return Uni.createFrom().item(List.of());
        }
        return collection()
                .find(Filters.eq("serviceId", serviceId),
                        new FindOptions().sort(Sorts.ascending("registerIndex")))
                .collect().asList();
    }

    /** 新增一条（{@code _id} 由 Panache 生成并回写到 {@code shadow.id}）。 */
    public Uni<Void> insert(ModbusServiceShadow shadow) {
        if (shadow.id == null) { shadow.id = new ObjectId(); }
        return collection().insertOne(shadow).replaceWithVoid();
    }

    /** 全量替换一条（{@code shadow.id} 必须是已落库的原值，由调用方从 {@link #find} 的结果带过来）。 */
    public Uni<Void> replace(ModbusServiceShadow shadow) {
        return collection()
                .replaceOne(Filters.eq("_id", shadow.id), shadow)
                .replaceWithVoid();
    }

    /**
     * 按 {@code (serviceId, registerIndex)} **幂等写一条成功结果**（不存在插入、存在覆盖），不读先查。
     *
     * <p>轮询成功时逐寄存器更新「当前值」用这条：一次 upsert 就够、不必每寄存器先查一次再决定
     * insert/replace（{@code _id} 也不用手工带）。**清掉故障态**（{@code lastError}/errorAt 置空）：
     * 成功意味着上一次故障已结束。</p>
     */
    public Uni<Void> upsertValue(ModbusServiceShadow shadow) {
        List<Bson> sets = new ArrayList<>();
        sets.add(Updates.set("serviceId", shadow.serviceId));
        sets.add(Updates.set("registerIndex", shadow.registerIndex));
        if (shadow.name != null) {
            sets.add(Updates.set("name", shadow.name));
        }
        if (shadow.unit != null) {
            sets.add(Updates.set("unit", shadow.unit));
        }
        sets.add(Updates.set("value", shadow.value));
        sets.add(Updates.set("recordedAt", shadow.recordedAt));
        sets.add(Updates.set("lastError", null));
        sets.add(Updates.set("errorAt", null));
        return upsert(filterOf(shadow), Updates.combine(sets));
    }

    /**
     * 按 {@code (serviceId, registerIndex)} **幂等记一次失败**（不存在插入、存在覆盖）。
     *
     * <p>只 $set 故障态 {@code lastError}/errorAt 与展示名/单位，**不动 {@code value}/recordedAt**
     * —— 那两只保留「最后一次成功的采集」，前端要靠它们显示最后已知值（及它有多旧）。</p>
     */
    public Uni<Void> upsertFailure(ModbusServiceShadow shadow) {
        List<Bson> sets = new ArrayList<>();
        sets.add(Updates.set("serviceId", shadow.serviceId));
        sets.add(Updates.set("registerIndex", shadow.registerIndex));
        if (shadow.name != null) {
            sets.add(Updates.set("name", shadow.name));
        }
        if (shadow.unit != null) {
            sets.add(Updates.set("unit", shadow.unit));
        }
        sets.add(Updates.set("lastError", shadow.lastError));
        sets.add(Updates.set("errorAt", shadow.errorAt));
        return upsert(filterOf(shadow), Updates.combine(sets));
    }

    private static Bson filterOf(ModbusServiceShadow shadow) {
        return Filters.and(Filters.eq("serviceId", shadow.serviceId), Filters.eq("registerIndex", shadow.registerIndex));
    }

    private Uni<Void> upsert(Bson filter, Bson update) {
        return collection()
                .updateOne(filter, update, new UpdateOptions().upsert(true))
                .replaceWithVoid();
    }

    /** 级联删除一批服务的影子（服务被删后派生状态再也收不到更新，留着只会越来越陈旧）。 */
    public Uni<Long> deleteByServiceIds(List<String> serviceIds) {
        if (serviceIds == null || serviceIds.isEmpty()) {
            return Uni.createFrom().item(0L);
        }
        return collection()
                .deleteMany(Filters.in("serviceId", serviceIds))
                .map(DeleteResult::getDeletedCount);
    }

    /** 删掉指定的几条（影子对账用：定义里已删的寄存器，它的影子再也不会被更新）。 */
    public Uni<Long> deleteByIds(List<ObjectId> ids) {
        if (ids == null || ids.isEmpty()) {
            return Uni.createFrom().item(0L);
        }
        return collection()
                .deleteMany(Filters.in("_id", ids))
                .map(DeleteResult::getDeletedCount);
    }
}