package cc.openxiot.matrix.db.dashboard.mobile;

import cc.openxiot.matrix.db.mongo.DuplicateKey;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.quarkus.mongodb.panache.reactive.ReactivePanacheMongoRepository;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * 移动端看板布局的数据访问（Panache，实体固定落库 {@code matrix}/集合 {@code mobile-dashboard-layouts}）。
 *
 * <p>结构镜像 Web 布局仓库：一个空间最多一份布局，以 {@code spaceId} 为键，且<b>没有</b>
 * 「按 id 找」的入口 —— 布局对外的身份就是空间 ID，其 ObjectId 是实现细节。</p>
 *
 * <p>「重复键」判法由 {@code db.mongo.DuplicateKey} 与 Web 仓库共用，这里直接引用，不再拷一份。</p>
 */
@ApplicationScoped
public class MobileDashboardLayoutRepository implements ReactivePanacheMongoRepository<MobileDashboardLayoutEntity> {

    private ReactiveMongoCollection<MobileDashboardLayoutEntity> collection() {
        return mongoCollection();
    }

    /**
     * 启动时确保 spaceId 唯一索引存在（幂等；失败仅告警，不阻断启动）。
     *
     * <p><b>是唯一索引而不是普通索引</b>：一个空间只能有一份移动端布局。虽然正常路径下不会写出
     * 第二条（服务层先查后写，且保存走 {@code replaceOne}），但唯一索引是这条不变量的最后一道闸 ——
     * 真被并发写出两条时，宁可让写入失败，也不要出现「同一个项目两个成员看到不同版」。</p>
     */
    public Uni<Void> ensureIndexes() {
        return collection()
                .createIndex(Indexes.ascending("spaceId"), new IndexOptions().unique(true))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /** 读某个空间的移动端布局；没有文档时返回 {@code null}（调用方据此给预置布局，不报错）。 */
    public Uni<MobileDashboardLayoutEntity> findBySpaceId(String spaceId) {
        return list("spaceId", spaceId).map(layouts -> layouts.isEmpty() ? null : layouts.get(0));
    }

    /** 新增一份布局（id 由 Panache 生成并回写到 {@code layout.id}）。 */
    public Uni<Void> insert(MobileDashboardLayoutEntity layout) {
        return persist(layout).replaceWithVoid();
    }

    /**
     * 带版本条件的全量覆盖：**把 {@code version} 也放进筛选条件**，返回是否真的写成功。
     *
     * <p>为什么不能只在服务层比一下版本再 {@code replaceOne(_id)}：那是「读—判—写」，
     * 两个管理员同时保存时都能读到 {@code version = 3}、都判定通过，然后后写的那份把先写的
     * 悄悄盖掉 —— 乐观锁形同虚设。把版本放进筛选条件后，比较与写入在 Mongo 侧是一次原子操作：
     * 匹配数为 0 就说明「我读到的版本已经不是最新的」，此时一个字都不该写。</p>
     */
    public Uni<Boolean> replaceIfVersion(MobileDashboardLayoutEntity layout, long expectedVersion) {
        return collection()
                .replaceOne(Filters.and(
                                Filters.eq("_id", layout.id),
                                Filters.eq("version", expectedVersion)),
                        layout)
                .map(result -> result.getMatchedCount() > 0);
    }

    /** 是不是「重复键」失败（MongoDB 11000），与 Web 布局仓库同一判法。 */
    public static boolean isDuplicateKey(Throwable failure) {
        return DuplicateKey.is(failure);
    }
}