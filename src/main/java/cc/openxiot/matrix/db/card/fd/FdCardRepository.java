package cc.openxiot.matrix.db.card.fd;

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
 * 现场设备卡片配置的数据访问（固定落库 {@code matrix}/集合 {@code fd-card-configs}）。
 *
 * <p>为什么放在 {@code matrix} 库而不是 {@code modbus} 库：这是一份**按项目共享的展示配置**，
 * 与设备卡片配置、空间、看板布局同一个生命周期（空间删了就该跟着清）；点表与现场设备本身
 * （{@code modbus} 库）是采集侧的数据，这边只引用其中的寄存器序号。放一起，读配置时不必跨库。</p>
 *
 * <p>文档主键就是 {@code fdid}（见 {@link FdCard}）：现场设备 id 全局唯一，一份配置一台现场设备
 * 一份，读写删都按主键直来直去。{@code spaceId} 只是派生字段，唯一的服务对象是按项目列表。</p>
 */
@ApplicationScoped
public class FdCardRepository {

    /** 上一版身份的复合唯一索引名（{@code (spaceId, fdid)}），启动时顺手删掉。 */
    private static final String LEGACY_UNIQUE_INDEX = "spaceId_1_fdid_1";

    @Inject
    ReactiveMongoClient mongoClient;

    private ReactiveMongoCollection<FdCard> collection() {
        return mongoClient.getDatabase("matrix").getCollection("fd-card-configs", FdCard.class);
    }

    /**
     * 启动时补 {@code spaceId} 普通索引（按项目列表用），并删掉上一版的 {@code (spaceId, fdid)}
     * 唯一索引。幂等、失败仅告警，不阻断启动。
     * 理由与 {@link cc.openxiot.matrix.db.card.device.DeviceCardRepository#ensureIndexes()} 相同：
     * 唯一性已由 {@code _id = fdid} 提供，复合唯一索引是上一版身份的遗留。
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

    /** 某个项目下所有**已配置**的现场设备卡片（没配过的不在其中，前端按缺省规则摆）。 */
    public Uni<List<FdCard>> findBySpaceId(String spaceId) {
        return collection().find(Filters.eq("spaceId", spaceId)).collect().asList();
    }

    /** 单台现场设备的配置；没有文档时返回 {@code null}（调用方据此给「未配置」语义，不报错）。 */
    public Uni<FdCard> findByFdid(String fdid) {
        return collection()
                .find(Filters.eq("_id", fdid))
                .collect().asList()
                .map(cards -> cards.isEmpty() ? null : cards.get(0));
    }

    /** 写入一份配置：主键命中就整份覆盖，没有就插入。 */
    public Uni<Void> upsert(FdCard card) {
        return collection()
                .replaceOne(Filters.eq("_id", card.fdid), card, new ReplaceOptions().upsert(true))
                .replaceWithVoid();
    }

    /** 删除一份配置（「恢复默认」）；返回删除条数。 */
    public Uni<Long> deleteByFdid(String fdid) {
        return collection()
                .deleteOne(Filters.eq("_id", fdid))
                .map(result -> result.getDeletedCount());
    }

    /** 是不是「重复键」失败（11000）：并发首次保存的 upsert 仍有这个竞态，服务层据此重试一次。 */
    public static boolean isDuplicateKey(Throwable failure) {
        return DuplicateKey.is(failure);
    }
}
