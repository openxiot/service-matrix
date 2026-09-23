package cc.openxiot.matrix.db.modbus.config;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.result.DeleteResult;
import io.quarkus.mongodb.panache.reactive.ReactivePanacheMongoRepository;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.util.List;

/**
 * Modbus 设备点表配置的数据访问（Panache，实体固定落库 {@code modbus}/集合 {@code configs}）。
 *
 * <p>落库主键 {@code _id} 是系统生成的 ObjectId（见 {@link ModbusConfig}），但对外（HTTP 路径参数、
 * 服务 {@code configId} 引用、JSON）一律是十六进制字符串：本仓储的方法签名保持 String，
 * 内部再转 ObjectId，接口契约与 id 生成方式解耦。非法 id（非 24 位十六进制）按「未命中」处理，不抛错。
 * 归属主体（org/user）经 {@code ModbusConfig#owner} 子文档表达，读/改/删的鉴权在
 * {@link cc.openxiot.matrix.api.modbus.config.ModbusConfigService} 里按 owner 判（例仿
 * {@code ModbusServiceService} 的「按 id 取再判权」先例，id 是不可猜的 ObjectId）。</p>
 */
@ApplicationScoped
public class ModbusConfigRepository implements ReactivePanacheMongoRepository<ModbusConfig> {

    private ReactiveMongoCollection<ModbusConfig> collection() {
        return mongoCollection();
    }

    /** 十六进制字符串主键 → ObjectId；非法（空/非 24 位十六进制）返回 null，调用方按未命中处理。 */
    private static ObjectId toObjectId(String id) {
        return id != null && ObjectId.isValid(id) ? new ObjectId(id) : null;
    }

    /** 启动时确保 owner 索引存在（幂等；失败仅告警，不阻断启动）。 */
    public Uni<Void> ensureIndexes() {
        return collection()
                .createIndex(Indexes.ascending("owner.type", "owner.id"))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /** 新增一条配置（id 由 Panache 生成并回写到 {@code cfg.id}，落库即 ObjectId）。 */
    public Uni<Void> insert(ModbusConfig cfg) {
        return persist(cfg).replaceWithVoid();
    }

    /** 按 id 裸查单条（不按 owner 过滤；读/改/删的归属鉴权在 Service 里按 owner 判）。 */
    public Uni<ModbusConfig> findById(String id) {
        ObjectId oid = toObjectId(id);
        if (oid == null) {
            return Uni.createFrom().nullItem();
        }
        return collection().find(Filters.eq("_id", oid)).collect().first();
    }

    /** 按 id 查询公开配置（无组织场景读详情用；未公开 → 空）。 */
    public Uni<ModbusConfig> findPublicById(String id) {
        ObjectId oid = toObjectId(id);
        if (oid == null) {
            return Uni.createFrom().nullItem();
        }
        return collection()
                .find(Filters.and(Filters.eq("_id", oid), Filters.eq("visibility", "public")))
                .collect().first();
    }

    /** 全量替换一条（更新：整文档覆盖；{@code cfg.id} 已是落库对象，直接用）。 */
    public Uni<Void> replace(ModbusConfig cfg) {
        return collection()
                .replaceOne(Filters.eq("_id", cfg.id), cfg)
                .replaceWithVoid();
    }

    /** 列出组织全部配置（组织点表 = owner.type=organization 且 owner.id=该组织）。 */
    public Uni<List<ModbusConfig>> listByOrg(String orgId) {
        return collection()
                .find(Filters.and(Filters.eq("owner.type", ModbusConfigOwner.TYPE_ORGANIZATION),
                        Filters.eq("owner.id", orgId)))
                .collect().asList();
    }

    /** 列出指定账号全部私有配置（owner.type=user 且 owner.id=该账号），与 listByOrg 对称。 */
    public Uni<List<ModbusConfig>> listByUser(String userId) {
        return collection()
                .find(Filters.and(Filters.eq("owner.type", ModbusConfigOwner.TYPE_USER),
                        Filters.eq("owner.id", userId)))
                .collect().asList();
    }

    /** 列出公开配置（visibility=public）。 */
    public Uni<List<ModbusConfig>> listPublic() {
        return collection().find(Filters.eq("visibility", "public")).collect().asList();
    }

    /**
     * 组织内可见配置（精确口径，不含调用者个人私有）：visibility=public（公开）或
     * owner 属于该组织（owner.type=organization 且 owner.id=orgId）。供 getVisibleByOrg 用。
     */
    public Uni<List<ModbusConfig>> listVisibleByOrg(String orgId) {
        return collection()
                .find(Filters.or(Filters.eq("visibility", "public"),
                        Filters.and(Filters.eq("owner.type", ModbusConfigOwner.TYPE_ORGANIZATION),
                                Filters.eq("owner.id", orgId))))
                .collect().asList();
    }

    /**
     * 组织内可见配置：visibility=public（公开）或 owner 属于「当前组织」（owner.type=organization
     * 且 owner.id=orgId）或「本人」（owner.type=user 且 owner.id=userId）。orgId 为 null 时
     * 只保留「公开 + 本人」两段（未选组织/无组织的个人浏览）。
     */
    public Uni<List<ModbusConfig>> listVisible(String userId, String orgId) {
        if (userId == null) {
            return listPublic();
        }
        return collection()
                .find(Filters.or(buildVisibleClauses(userId, orgId)))
                .collect().asList();
    }

    /** 按 id 删除（鉴权在 Service 判过）；返回删除条数（非法 id 视为 0 条）。 */
    public Uni<Long> deleteById(String id) {
        ObjectId oid = toObjectId(id);
        if (oid == null) {
            return Uni.createFrom().item(0L);
        }
        return collection()
                .deleteOne(Filters.eq("_id", oid))
                .map(DeleteResult::getDeletedCount);
    }

    /** 组装 listVisible 的 $or 子句，按存在与否裁剪。 */
    private List<Bson> buildVisibleClauses(String userId, String orgId) {
        List<Bson> clauses = new java.util.ArrayList<>();
        clauses.add(Filters.eq("visibility", "public"));
        if (orgId != null && !orgId.isBlank()) {
            clauses.add(Filters.and(Filters.eq("owner.type", ModbusConfigOwner.TYPE_ORGANIZATION),
                    Filters.eq("owner.id", orgId)));
        }
        clauses.add(Filters.and(Filters.eq("owner.type", ModbusConfigOwner.TYPE_USER),
                Filters.eq("owner.id", userId)));
        return clauses;
    }
}
