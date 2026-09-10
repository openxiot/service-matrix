package cc.openxiot.matrix.db.modbus.service;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
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

    private ReactiveMongoCollection<ModbusService> collection() {
        return mongoCollection();
    }

    /** 十六进制字符串主键 → ObjectId；非法（空/非 24 位十六进制）返回 null，调用方按未命中处理。 */
    private static ObjectId toObjectId(String id) {
        return id != null && ObjectId.isValid(id) ? new ObjectId(id) : null;
    }

    /** 启动时确保 orgId / parent.did 索引存在（幂等；失败仅告警，不阻断启动）。 */
    public Uni<Void> ensureIndexes() {
        return collection()
                .createIndex(Indexes.ascending("orgId"))
                .chain(ignored -> collection().createIndex(Indexes.ascending("parent.did")))
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

    /** 列出挂在指定父设备下的服务（org 约束防跨组织读取）。 */
    public Uni<List<ModbusService>> listByParent(String did, String orgId) {
        return collection()
                .find(Filters.and(Filters.eq("orgId", orgId), Filters.eq("parent.did", did)))
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
