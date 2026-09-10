package cc.openxiot.matrix.db.modbus.service;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import io.quarkus.mongodb.FindOptions;
import com.mongodb.client.result.DeleteResult;
import io.quarkus.mongodb.panache.reactive.ReactivePanacheMongoRepository;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.types.ObjectId;

import java.util.List;

/**
 * Modbus 服务的数据访问（Panache，实体固定落库 {@code modbus}/集合 {@code services}）。
 *
 * <p>落库主键 {@code _id} 是系统生成的 ObjectId（见 {@link ModbusService}），对外（HTTP 路径参数、
 * invoke 请求体、JSON）一律是十六进制字符串：方法签名保持 String，内部再转 ObjectId，
 * 接口契约与 id 生成方式解耦。非法 id（非 24 位十六进制）按「未命中」处理，不抛错。
 * 所有按 id 的读/删都带 orgId 约束，防跨组织访问。</p>
 */
@ApplicationScoped
public class ModbusServiceRepository implements ReactivePanacheMongoRepository<ModbusService> {

    /** 精简查询（{@link #findBriefBySpaceId}）要带回来的字段；{@code _id} 由 Mongo 默认带出。 */
    private static final List<String> BRIEF_FIELDS = List.of("name", "device.did", "device.space.spaceId");

    private ReactiveMongoCollection<ModbusService> collection() {
        return mongoCollection();
    }

    /** 十六进制字符串主键 → ObjectId；非法（空/非 24 位十六进制）返回 null，调用方按未命中处理。 */
    private static ObjectId toObjectId(String id) {
        return id != null && ObjectId.isValid(id) ? new ObjectId(id) : null;
    }

    /** 启动时确保 orgId / device.did / device.space.spaceId 索引存在（幂等；失败仅告警，不阻断启动）。 */
    public Uni<Void> ensureIndexes() {
        return collection()
                .createIndex(Indexes.ascending("orgId"))
                .chain(ignored -> collection().createIndex(Indexes.ascending("device.did")))
                .chain(ignored -> collection().createIndex(Indexes.ascending("device.space.spaceId")))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /** 新增一条（id 由 Panache 生成并回写到 {@code service.id}，落库即 ObjectId）。 */
    public Uni<Void> insert(ModbusService service) {
        return persist(service).replaceWithVoid();
    }

    /** 全量替换一条（更新：整文档覆盖；{@code service.id} 已是落库对象，直接用）。 */
    public Uni<Void> replace(ModbusService service) {
        return collection()
                .replaceOne(Filters.eq("_id", service.id), service)
                .replaceWithVoid();
    }

    /** 按 orgId + id 查询单条（org 约束防跨组织读取）。 */
    public Uni<ModbusService> findByIdAndOrg(String id, String orgId) {
        ObjectId oid = toObjectId(id);
        if (oid == null) {
            return Uni.createFrom().nullItem();
        }
        return collection()
                .find(Filters.and(Filters.eq("orgId", orgId), Filters.eq("_id", oid)))
                .collect().first();
    }

    /** 列出组织全部服务。 */
    public Uni<List<ModbusService>> listByOrg(String orgId) {
        return collection().find(Filters.eq("orgId", orgId)).collect().asList();
    }

    /** 列出挂在指定设备下的服务（org 约束防跨组织读取）。 */
    public Uni<List<ModbusService>> listByParent(String did, String orgId) {
        return collection()
                .find(Filters.and(Filters.eq("orgId", orgId), Filters.eq("device.did", did)))
                .collect().asList();
    }

    /**
     * 列出依赖的设备落在该空间的服务，**只取清单用得到的字段**：{@code _id}（Mongo 默认带出）、
     * {@code name}、{@code device.did}、{@code device.space.spaceId}（见 {@link #BRIEF_FIELDS}）。
     *
     * <p>给空间图这类「只要列个清单」的地方用：服务文档带上 functions / response 动辄几十 KB，
     * 一个空间下面挂了十几个就不适合整份拉回来。投影在 Mongo 侧就生效，其余字段不上网络；
     * 返回的对象只有上述字段有值，**不要当成完整定义用**（要详情另按 id 查）。</p>
     */
    public Uni<List<ModbusService>> findBriefBySpaceId(String spaceId) {
        // 这个版本的 ReactiveMongoCollection.find() 直接给 Multi，投影只能经 FindOptions 传
        return collection()
                .find(Filters.eq("device.space.spaceId", spaceId),
                        new FindOptions().projection(Projections.include(BRIEF_FIELDS)))
                .collect().asList();
    }

    /** 按 orgId + id 删除（org 约束防跨组织删除）；返回删除条数（非法 id 视为 0 条）。 */
    public Uni<Long> deleteByIdAndOrg(String id, String orgId) {
        ObjectId oid = toObjectId(id);
        if (oid == null) {
            return Uni.createFrom().item(0L);
        }
        return collection()
                .deleteOne(Filters.and(Filters.eq("orgId", orgId), Filters.eq("_id", oid)))
                .map(DeleteResult::getDeletedCount);
    }
}
