package cc.openxiot.matrix.db.energy.config;

import cc.openxiot.matrix.db.energy.EnergyRole;
import cc.openxiot.matrix.db.energy.EnergyType;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
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
 * {@link EnergyConfig} 的数据访问（库 {@code matrix}/集合 {@code energy-configs}）。
 *
 * <p>查询入口都按项目根空间收窄；只有采样线程会取「全部启用配置」，所以额外留一条
 * {@link #listEnabled()}。索引与查询同形：{@code (spaceId, role, type)} 支撑页面按标签页取配置，
 * {@code (enabled)} 支撑采样线程的扫描。</p>
 */
@ApplicationScoped
public class EnergyConfigRepository {

    @Inject
    ReactiveMongoClient mongoClient;

    private ReactiveMongoCollection<EnergyConfig> collection() {
        return mongoClient.getDatabase("matrix").getCollection("energy-configs", EnergyConfig.class);
    }

    /** 启动时建索引（幂等；失败仅告警，不阻断启动）。 */
    public Uni<Void> ensureIndexes() {
        return collection()
                .createIndex(Indexes.ascending("spaceId", "role", "type"))
                .chain(index -> collection()
                        .createIndex(Indexes.ascending("enabled")))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /** 项目下全部配置，按创建时间升序（页面列表的稳定顺序）。 */
    public Uni<List<EnergyConfig>> listBySpace(String spaceId) {
        return collection()
                .find(Filters.eq("spaceId", spaceId), new FindOptions().sort(Sorts.ascending("create", "_id")))
                .collect().asList();
    }

    /** 项目下某角色某类型的配置（报表取数用）。 */
    public Uni<List<EnergyConfig>> listBySpace(String spaceId, EnergyRole role, EnergyType type) {
        Bson filter = Filters.and(
                Filters.eq("spaceId", spaceId),
                Filters.eq("role", role),
                Filters.eq("type", type));
        return collection()
                .find(filter, new FindOptions().sort(Sorts.ascending("create", "_id")))
                .collect().asList();
    }

    /** 全部启用配置：采样线程每轮扫一次。 */
    public Uni<List<EnergyConfig>> listEnabled() {
        return collection()
                .find(Filters.eq("enabled", true),
                        new FindOptions().sort(Sorts.ascending("_id")))
                .collect().asList();
    }

    public Uni<EnergyConfig> findById(String id) {
        ObjectId objectId = objectId(id);
        if (objectId == null) {
            return Uni.createFrom().nullItem();
        }
        return collection().find(Filters.eq("_id", objectId)).collect().first();
    }

    /** 新增（{@code id} 由这里补上，回写到实体）。 */
    public Uni<Void> insert(EnergyConfig config) {
        if (config.id == null) {
            config.id = new ObjectId();
        }
        return collection().insertOne(config).replaceWithVoid();
    }

    /** 按 id 全量覆盖（调用方已保证 id 存在且属于该空间）。 */
    public Uni<Boolean> replace(EnergyConfig config) {
        return collection()
                .replaceOne(Filters.eq("_id", config.id), config)
                .map(result -> result.getMatchedCount() > 0);
    }

    public Uni<Long> delete(String id) {
        ObjectId objectId = objectId(id);
        if (objectId == null) {
            return Uni.createFrom().item(0L);
        }
        return collection().deleteOne(Filters.eq("_id", objectId))
                .map(result -> result.getDeletedCount());
    }

    /** 解析十六进制 id；非法值给 null（调用方按「不存在」处理，不报 500）。 */
    public static ObjectId objectId(String id) {
        if (id == null || id.isBlank() || !ObjectId.isValid(id)) {
            return null;
        }
        return new ObjectId(id);
    }
}
