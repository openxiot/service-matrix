package cc.openxiot.matrix.db.dashboard.web;

import cc.openxiot.matrix.db.mongo.DuplicateKey;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;

/**
 * 看板布局的数据访问（Panache，实体固定落库 {@code matrix}/集合 {@code web-dashboard-layouts}）。
 *
 * <p>一个空间最多一份布局，所以这里的查询都以 {@code spaceId} 为键，且<b>没有</b>「按 id 找」
 * 的入口 —— 布局对外的身份就是空间 ID，其 ObjectId 是实现细节，不暴露给前端。</p>
 */
@ApplicationScoped
public class WebDashboardLayoutRepository  {

    @Inject
    ReactiveMongoClient mongoClient;

    private ReactiveMongoCollection<WebDashboardLayoutEntity> collection() {
        return mongoClient.getDatabase("matrix").getCollection("web-dashboard-layouts", WebDashboardLayoutEntity.class);
    }

    /**
     * 启动时确保 spaceId 唯一索引存在（幂等；失败仅告警，不阻断启动）。
     *
     * <p><b>是唯一索引而不是普通索引</b>：一个空间只能有一份布局。虽然正常路径下不会写出第二条
     * （服务层先查后写，且保存走 {@code replaceOne}），但唯一索引是这条不变量的最后一道闸 ——
     * 真被并发写出两条时，宁可让写入失败，也不要出现「同一个空间两个成员看到不同布局」。</p>
     */
    public Uni<Void> ensureIndexes() {
        return collection()
                .createIndex(Indexes.ascending("spaceId"), new IndexOptions().unique(true))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /** 读某个空间的布局；没有文档时返回 {@code null}（调用方据此给预置布局，不报错）。 */
    public Uni<WebDashboardLayoutEntity> findBySpaceId(String spaceId) {
        return collection().find(Filters.eq("spaceId", spaceId)).collect().asList()
                .map(layouts -> layouts.isEmpty() ? null : layouts.get(0));
    }

    /** 新增一份布局（id 由 Panache 生成并回写到 {@code layout.id}）。 */
    public Uni<Void> insert(WebDashboardLayoutEntity layout) {
        if (layout.id == null) { layout.id = new ObjectId(); }
        return collection().insertOne(layout).replaceWithVoid();
    }

    /**
     * 带版本条件的全量覆盖：**把 {@code version} 也放进筛选条件**，返回是否真的写成功。
     *
     * <p>为什么不能只在服务层比一下版本再 {@code replaceOne(_id)}：那是「读—判—写」，
     * 两个管理员同时保存时都能读到 {@code version = 3}、都判定通过，然后后写的那份把先写的
     * 悄悄盖掉 —— 乐观锁形同虚设。把版本放进筛选条件后，比较与写入在 Mongo 侧是一次原子操作：
     * 匹配数为 0 就说明「我读到的版本已经不是最新的」，此时一个字都不该写。</p>
     */
    public Uni<Boolean> replaceIfVersion(WebDashboardLayoutEntity layout, long expectedVersion) {
        return collection()
                .replaceOne(Filters.and(
                                Filters.eq("_id", layout.id),
                                Filters.eq("version", expectedVersion)),
                        layout)
                .map(result -> result.getMatchedCount() > 0);
    }

    /**
     * 是不是「重复键」失败（MongoDB 11000）。用于把「两个管理员同时创建第一份布局」
     * 这条竞态翻译成正常的版本冲突提示，而不是一个 500。
     *
     * <p>这是 Web/Mobile 布局仓库共用的判法，实现在 {@code db.mongo.DuplicateKey}，这里只作转发：
     * Mobile 布局仓库直接用同一个 {@code DuplicateKey.is}。判法为什么按错误码、以及三种异常形状，
     * 见那边。</p>
     */
    public static boolean isDuplicateKey(Throwable failure) {
        return DuplicateKey.is(failure);
    }
}
