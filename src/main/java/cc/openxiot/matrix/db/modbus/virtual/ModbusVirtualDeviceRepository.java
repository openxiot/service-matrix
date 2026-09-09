package cc.openxiot.matrix.db.modbus.virtual;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.result.DeleteResult;
import io.quarkus.mongodb.panache.reactive.ReactivePanacheMongoRepository;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Modbus 虚拟设备的数据访问（Panache，实体固定落库 {@code modbus}/集合 {@code virtual-devices}）。
 *
 * <p>与点表配置（{@code configs}）分库存储：单条 Modbus 点表可被多次虚拟化（挂不同父设备/不同 siid/aiid），
 * 故虚拟设备自持 did（经 {@code @BsonId} 落 {@code _id}），不复用配置 id。</p>
 */
@ApplicationScoped
public class ModbusVirtualDeviceRepository implements ReactivePanacheMongoRepository<ModbusVirtualDevice> {

    private ReactiveMongoCollection<ModbusVirtualDevice> collection() {
        return mongoCollection();
    }

    /** 启动时确保 orgId 索引存在（幂等；失败仅告警，不阻断启动）。 */
    public Uni<Void> ensureIndexes() {
        return collection()
                .createIndex(Indexes.ascending("orgId"))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /** 新增一条虚拟设备（did 由上层生成）。 */
    public Uni<Void> insert(ModbusVirtualDevice v) {
        return collection().insertOne(v).replaceWithVoid();
    }

    /** 按 orgId + did 查询单条（org 约束防跨组织删除）。 */
    public Uni<ModbusVirtualDevice> findByIdAndOrg(String did, String orgId) {
        return collection()
                .find(Filters.and(Filters.eq("orgId", orgId), Filters.eq("_id", did)))
                .collect().first();
    }

    /** 按 orgId + did 删除（org 约束防跨组织删除）；返回删除条数。 */
    public Uni<Long> deleteByIdAndOrg(String did, String orgId) {
        return collection()
                .deleteOne(Filters.and(Filters.eq("orgId", orgId), Filters.eq("_id", did)))
                .map(DeleteResult::getDeletedCount);
    }

    /** 按派生的实例 DeviceType（{@code instance.type}）取单条（公开只读，跨组织，供实例读取/接入侧用）。 */
    public Uni<ModbusVirtualDevice> findFirstByInstanceType(String type) {
        return collection()
                .find(Filters.eq("instance.type", type))
                .collect().first();
    }
}
