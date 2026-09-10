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
 * 接口契约与 id 生成方式解耦。非法 id（非 24 位十六进制）按「未命中」处理，不抛错。</p>
 *
 * <p>按 id 的读 / 删**不带 org 约束**：鉴权已上移到资源层的空间判定（见 {@code ModbusServiceResource}），
 * 之后按 id 操作（与 {@code DeviceService} 同口径，org 不再参与匹配）。空间维度的查询统一走
 * {@code device.space.spaceId}（创建服务时一并落库的设备落点副本，索引见 {@link #ensureIndexes}）。</p>
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

    /**
     * 启动时确保 device.did / device.space.spaceId 索引存在（幂等；失败仅告警，不阻断启动）。
     * {@code orgId} 也一并建：该字段现在只记录创建时的组织（不参与鉴权与查询），索引留着供运维按组织翻数据。
     */
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

    /** 按 id 查询单条（不带 org 约束，鉴权在资源层）。 */
    public Uni<ModbusService> findById(String id) {
        ObjectId oid = toObjectId(id);
        if (oid == null) {
            return Uni.createFrom().nullItem();
        }
        return collection().find(Filters.eq("_id", oid)).collect().first();
    }

    /**
     * 列出设备落点在该空间的服务：过滤 {@code device.space.spaceId}——与 {@link #findBriefBySpaceId}
     * 同一个字段（索引见 {@link #ensureIndexes}）。副本陈旧（设备搬走）的服务不在其中。
     */
    public Uni<List<ModbusService>> listBySpaceId(String spaceId) {
        return collection().find(Filters.eq("device.space.spaceId", spaceId)).collect().asList();
    }

    /** 列出挂在指定设备下的服务。 */
    public Uni<List<ModbusService>> listByParent(String did) {
        return collection().find(Filters.eq("device.did", did)).collect().asList();
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

    /** 按 id 删除（不带 org 约束，鉴权在资源层）；返回删除条数（非法 id 视为 0 条）。 */
    public Uni<Long> deleteById(String id) {
        ObjectId oid = toObjectId(id);
        if (oid == null) {
            return Uni.createFrom().item(0L);
        }
        return collection()
                .deleteOne(Filters.eq("_id", oid))
                .map(DeleteResult::getDeletedCount);
    }

    /**
     * 按依赖设备批量删除，**不带 org 约束**：删除设备时的级联清理（{@code device.did} 命中即删，
     * 设备删了服务留着就是查不出、也删不掉的孤儿）。返回删除条数，没有服务时为 0。
     */
    public Uni<Long> deleteByDeviceDids(List<String> dids) {
        if (dids == null || dids.isEmpty()) {
            return Uni.createFrom().item(0L);
        }
        return collection()
                .deleteMany(Filters.in("device.did", dids))
                .map(DeleteResult::getDeletedCount);
    }
}
