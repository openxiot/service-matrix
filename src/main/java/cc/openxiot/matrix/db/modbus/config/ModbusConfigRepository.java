package cc.openxiot.matrix.db.modbus.config;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.result.DeleteResult;
import io.quarkus.mongodb.panache.reactive.ReactivePanacheMongoRepository;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Modbus 设备点表配置的数据访问（Panache，实体固定落库 {@code modbus}/集合 {@code device-configs}）。
 *
 * <p>主键为字符串 {@code _id}；所有按 id 的读/删都带 orgId 约束，防跨组织访问。</p>
 */
@ApplicationScoped
public class ModbusConfigRepository implements ReactivePanacheMongoRepository<ModbusConfig> {

    private ReactiveMongoCollection<ModbusConfig> collection() {
        return mongoCollection();
    }

    /** 启动时确保 orgId 索引存在（幂等；失败仅告警，不阻断启动）。 */
    public Uni<Void> ensureIndexes() {
        return collection()
                .createIndex(Indexes.ascending("orgId"))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /** 新增一条配置（id 由上层生成）。 */
    public Uni<Void> insert(ModbusConfig cfg) {
        return collection().insertOne(cfg).replaceWithVoid();
    }

    /** 按 orgId + id 查询单条（org 约束防跨组织读取）。 */
    public Uni<ModbusConfig> findByIdAndOrg(String id, String orgId) {
        return collection()
                .find(Filters.and(Filters.eq("orgId", orgId), Filters.eq("_id", id)))
                .collect().first();
    }

    /**
     * 按 id 查询「组织内可见」的单条配置：本组织（任意可见度）或任意组织的公开配置，
     * 与 {@link #listVisible} 单条口径一致（读详情用，跨组织仅放行 public）。
     */
    public Uni<ModbusConfig> findVisibleById(String id, String orgId) {
        return collection()
                .find(Filters.and(Filters.eq("_id", id),
                        Filters.or(Filters.eq("orgId", orgId), Filters.eq("visibility", "public"))))
                .collect().first();
    }

    /** 按 id 查询公开配置（无组织场景读详情用；未公开 → 空）。 */
    public Uni<ModbusConfig> findPublicById(String id) {
        return collection()
                .find(Filters.and(Filters.eq("_id", id), Filters.eq("visibility", "public")))
                .collect().first();
    }

    /** 全量替换一条（更新：整文档覆盖）。 */
    public Uni<Void> replace(ModbusConfig cfg) {
        return collection()
                .replaceOne(Filters.eq("_id", cfg.id), cfg)
                .replaceWithVoid();
    }

    /** 列出组织全部配置。 */
    public Uni<List<ModbusConfig>> listByOrg(String orgId) {
        return collection().find(Filters.eq("orgId", orgId)).collect().asList();
    }

    /** 列出公开配置（visibility=public）。 */
    public Uni<List<ModbusConfig>> listPublic() {
        return collection().find(Filters.eq("visibility", "public")).collect().asList();
    }

    /** 组织内可见配置：visibility=public（公开）或 orgId 属于该组织。 */
    public Uni<List<ModbusConfig>> listVisible(String orgId) {
        return collection()
                .find(Filters.or(Filters.eq("visibility", "public"), Filters.eq("orgId", orgId)))
                .collect().asList();
    }

    /** 按 orgId + id 删除（org 约束防跨组织删除）；返回删除条数。 */
    public Uni<Long> deleteByIdAndOrg(String id, String orgId) {
        return collection()
                .deleteOne(Filters.and(Filters.eq("orgId", orgId), Filters.eq("_id", id)))
                .map(DeleteResult::getDeletedCount);
    }
}
