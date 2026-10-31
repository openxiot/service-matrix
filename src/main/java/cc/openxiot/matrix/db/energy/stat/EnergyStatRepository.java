package cc.openxiot.matrix.db.energy.stat;

import cc.openxiot.matrix.db.energy.EnergyRole;
import cc.openxiot.matrix.db.energy.config.EnergyConfig;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
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

/**
 * {@link EnergyStat} 的数据访问（库 {@code matrix}/集合 {@code energy-stats}）。
 *
 * <p>写入是**每个采样轮一次原子累加**（{@code $inc} / {@code $min} / {@code $max}），
 * 不做「读出来改内存再写回」：两个进程同时采样（或上一轮还没写完）时，读改写会丢一笔。
 * 唯一索引 {@code (configId, bucketAt)} 让「同一配置同一小时只有一行」由库保证，
 * upsert 因此可以放心用。</p>
 *
 * <p>读取按 {@code configId} 逐条查（{@code (configId, bucketAt)} 是索引前缀）。
 * 不用 {@code $in configId} 把多个配置一次查回来：那会丢掉前缀、变成全集合扫，
 * 与告警/历史接口「逐服务查再归并」是同一个理由。</p>
 */
@ApplicationScoped
public class EnergyStatRepository {

    @Inject
    ReactiveMongoClient mongoClient;

    private ReactiveMongoCollection<EnergyStat> collection() {
        return mongoClient.getDatabase("matrix").getCollection("energy-stats", EnergyStat.class);
    }

    /** 启动时建索引（幂等；失败仅告警，不阻断启动）。 */
    public Uni<Void> ensureIndexes() {
        return collection()
                .createIndex(Indexes.ascending("configId", "bucketAt"), new IndexOptions().unique(true))
                .chain(index -> collection()
                        .createIndex(Indexes.ascending("spaceId", "role", "bucketAt")))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /**
     * 把一次采样的增量累加进「配置 × 小时桶」。
     *
     * <p>瞬时量分三路写：{@code $inc} 和与次数（用来算平均）、{@code $min}/{@code $max}（极值）、
     * {@code $set} 最后值（容量这类只需要最后值）。没有的指标一个字段都不碰 ——
     * 写 0 会把「没配置」变成「采到 0」。</p>
     */
    public Uni<Void> addSample(String spaceId, EnergyConfig config, long bucketAt, EnergySample sample) {
        List<Bson> updates = new ArrayList<>();
        updates.add(Updates.set("spaceId", spaceId));
        updates.add(Updates.set("configId", config.id.toHexString()));
        updates.add(Updates.set("role", config.role.name()));
        updates.add(Updates.set("type", config.type.name()));
        updates.add(Updates.set("updatedAt", new Date()));

        if (sample.energy() != 0) {
            updates.add(Updates.inc("energy", sample.energy()));
        }
        if (sample.charge() != 0) {
            updates.add(Updates.inc("charge", sample.charge()));
        }
        if (sample.discharge() != 0) {
            updates.add(Updates.inc("discharge", sample.discharge()));
        }
        if (sample.cost() != 0) {
            updates.add(Updates.inc("cost", sample.cost()));
        }
        if (sample.revenue() != 0) {
            updates.add(Updates.inc("revenue", sample.revenue()));
        }
        if (sample.power() != null) {
            updates.add(Updates.inc("powerSum", sample.power()));
            updates.add(Updates.inc("powerCount", 1L));
            updates.add(Updates.max("powerMax", sample.power()));
            updates.add(Updates.min("powerMin", sample.power()));
            updates.add(Updates.set("powerLast", sample.power()));
        }
        if (sample.soc() != null) {
            updates.add(Updates.inc("socSum", sample.soc()));
            updates.add(Updates.inc("socCount", 1L));
            updates.add(Updates.min("socMin", sample.soc()));
            updates.add(Updates.max("socMax", sample.soc()));
            updates.add(Updates.set("socLast", sample.soc()));
        }
        if (sample.capacity() != null) {
            updates.add(Updates.set("capacity", sample.capacity()));
        }
        if (sample.soh() != null) {
            updates.add(Updates.set("soh", sample.soh()));
        }
        if (sample.temperature() != null) {
            updates.add(Updates.set("temperature", sample.temperature()));
        }
        if (sample.cycles() != null) {
            updates.add(Updates.set("cycles", sample.cycles()));
        }
        updates.add(Updates.inc("samples", 1L));

        Bson filter = Filters.and(
                Filters.eq("configId", config.id.toHexString()),
                Filters.eq("bucketAt", bucketAt));
        return collection()
                .updateOne(filter, Updates.combine(updates), new UpdateOptions().upsert(true))
                .replaceWithVoid();
    }

    /** 一个配置在 {@code [from, to)} 内的小时桶，按桶起点升序。 */
    public Uni<List<EnergyStat>> findRange(String configId, long from, long to) {
        Bson filter = Filters.and(
                Filters.eq("configId", configId),
                Filters.gte("bucketAt", from),
                Filters.lt("bucketAt", to));
        return collection()
                .find(filter, new FindOptions().sort(Sorts.ascending("bucketAt")))
                .collect().asList();
    }

    /** 项目下某角色最新的一小时桶（摘要用；没有数据返回 null）。 */
    public Uni<EnergyStat> latest(String spaceId, EnergyRole role) {
        Bson filter = Filters.and(
                Filters.eq("spaceId", spaceId),
                Filters.eq("role", role.name()));
        return collection()
                .find(filter, new FindOptions().sort(Sorts.descending("bucketAt")).limit(1))
                .collect().first();
    }

    /** 配置删除时清掉它的历史桶（避免同名新配置「继承」旧数据）。 */
    public Uni<Long> deleteByConfig(String configId) {
        return collection()
                .deleteMany(Filters.eq("configId", configId))
                .map(result -> result.getDeletedCount());
    }
}
