package cc.openxiot.matrix.db.card.device;

import cc.openxiot.matrix.db.mongo.DuplicateKey;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * 设备卡片配置的数据访问（固定落库 {@code matrix}/集合 {@code device-card-configs}）。
 *
 * <p>文档主键就是 {@code did}（见 {@link DeviceCard}）：设备 id 全局唯一，一份配置一台设备一份，
 * 读写删都按主键直来直去。{@code spaceId} 只是派生字段，唯一的服务对象是按项目列表。</p>
 */
@ApplicationScoped
public class DeviceCardRepository {

    /** 上一版身份的复合唯一索引名（{@code (spaceId, did)}），启动时顺手删掉。 */
    private static final String LEGACY_UNIQUE_INDEX = "spaceId_1_did_1";

    @Inject
    ReactiveMongoClient mongoClient;

    private ReactiveMongoCollection<DeviceCard> collection() {
        return mongoClient.getDatabase("matrix").getCollection("device-card-configs", DeviceCard.class);
    }

    /**
     * 启动时补 {@code spaceId} 普通索引（按项目列表用），并删掉上一版的 {@code (spaceId, did)}
     * 唯一索引。幂等、失败仅告警，不阻断启动。
     *
     * <p>唯一性现在由 {@code _id = did} 免费提供，不再需要复合唯一索引；留着它只会让人误以为
     * {@code spaceId} 还参与身份。删一个不存在的索引会报 IndexNotFound —— 这正是「已经删过」，
     * 吞掉即可。</p>
     */
    public Uni<Void> ensureIndexes() {
        return collection()
                .dropIndex(LEGACY_UNIQUE_INDEX)
                .onFailure().recoverWithNull()
                .replaceWithVoid()
                .chain(() -> collection().createIndex(Indexes.ascending("spaceId")))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /** 某个项目下所有**已配置**的设备卡片（没配过的设备不在其中，前端按缺省规则摆）。 */
    public Uni<List<DeviceCard>> findBySpaceId(String spaceId) {
        return collection().find(Filters.eq("spaceId", spaceId)).collect().asList();
    }

    /** 单台设备的配置；没有文档时返回 {@code null}（调用方据此给「未配置」语义，不报错）。 */
    public Uni<DeviceCard> findByDid(String did) {
        return collection()
                .find(Filters.eq("_id", did))
                .collect().asList()
                .map(cards -> cards.isEmpty() ? null : cards.get(0));
    }

    /** 写入一份配置：主键命中就整份覆盖，没有就插入。 */
    public Uni<Void> upsert(DeviceCard card) {
        return collection()
                .replaceOne(Filters.eq("_id", card.did), card, new ReplaceOptions().upsert(true))
                .replaceWithVoid();
    }

    /** 删除一份配置（「恢复默认」）；返回删除条数。 */
    public Uni<Long> deleteByDid(String did) {
        return collection()
                .deleteOne(Filters.eq("_id", did))
                .map(result -> result.getDeletedCount());
    }

    /** 是不是「重复键」失败（11000）：并发首次保存的 upsert 仍有这个竞态，服务层据此重试一次。 */
    public static boolean isDuplicateKey(Throwable failure) {
        return DuplicateKey.is(failure);
    }
}
