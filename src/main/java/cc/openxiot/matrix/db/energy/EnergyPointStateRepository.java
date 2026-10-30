package cc.openxiot.matrix.db.energy;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * {@link EnergyPointState} 的数据访问（库 {@code matrix}/集合 {@code energy-point-states}）。
 *
 * <p>主键是字符串 {@code configId#pointKey}，所以 upsert 用 {@code replaceOne(filter, doc, upsert)}
 * 即可：每次采样后整条覆盖（基线本来就只有一份，没有并发改不同字段的场景）。</p>
 */
@ApplicationScoped
public class EnergyPointStateRepository {

    @Inject
    ReactiveMongoClient mongoClient;

    private ReactiveMongoCollection<EnergyPointState> collection() {
        return mongoClient.getDatabase("matrix")
                .getCollection("energy-point-states", EnergyPointState.class);
    }

    /** 一条配置下的全部点位基线（采样一轮读一次）。 */
    public Uni<List<EnergyPointState>> listByConfig(String configId) {
        return collection()
                .find(Filters.eq("configId", configId))
                .collect().asList();
    }

    /** 整条覆盖（不存在则插入）。 */
    public Uni<Void> save(EnergyPointState state) {
        return collection()
                .replaceOne(Filters.eq("_id", state.id), state, new ReplaceOptions().upsert(true))
                .replaceWithVoid();
    }

    /** 配置删除时连基线一起删：重建同名配置不会沿用旧读数。 */
    public Uni<Long> deleteByConfig(String configId) {
        return collection()
                .deleteMany(Filters.eq("configId", configId))
                .map(result -> result.getDeletedCount());
    }
}
