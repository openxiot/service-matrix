package cc.openxiot.matrix.db.modbus.history;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.result.DeleteResult;
import io.quarkus.mongodb.FindOptions;
import io.quarkus.mongodb.panache.reactive.ReactivePanacheMongoRepository;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * {@link ModbusServiceFailure} 的数据访问（Panache，实体固定落库 {@code modbus}/集合 {@code service-failures}）。
 *
 * <p>写侧只有「追加一条」与「级联删除」；读侧一个区间查询，对应故障列表接口（见 Modbus-History-TODO.md 第二步）。
 * 按分类聚合不在这里做：那是纯函数 {@code ModbusServiceFailureCodec.encodeSummary} 的事，
 * 不必为此再起一条聚合管道。</p>
 */
@ApplicationScoped
public class ModbusServiceFailureRepository implements ReactivePanacheMongoRepository<ModbusServiceFailure> {

    private ReactiveMongoCollection<ModbusServiceFailure> collection() {
        return mongoCollection();
    }

    /**
     * 启动时确保 {@code (serviceId, functionIndex, at)} 索引存在（幂等；失败仅告警，不阻断启动）。
     *
     * <p>支撑「某个服务的故障列表，按时间倒序」：前两段等值 + {@code at} 排序。</p>
     */
    public Uni<Void> ensureIndexes() {
        return collection()
                .createIndex(Indexes.ascending("serviceId", "functionIndex", "at"))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /** 追加一条失败记录（一次失败一条，不像历史那样成批）。 */
    public Uni<Void> insert(ModbusServiceFailure failure) {
        return persist(failure).replaceWithVoid();
    }

    /**
     * 某段区间内的故障记录，按 {@code at} **倒序**（最近的在前，列表就是这么看的）。
     *
     * <p>{@code functionIndex} 与 {@code type} 为 null 表示不限（分别是「整个服务」与「所有分类」）。
     * 注意索引是 {@code (serviceId, functionIndex, at)}：不带 {@code functionIndex} 时只有第一段用得上，
     * 排序要靠内存完成 —— 故障是去重过的，行数很少，这样够用（要不要补索引见 Modbus-History-TODO.md 第二步）。</p>
     *
     * @param limit 条数上限；调用方传「上限 + 1」以便判出「还有更早的没取回来」
     */
    public Uni<List<ModbusServiceFailure>> findRange(String serviceId, Integer functionIndex, String type,
                                                     Date from, Date to, int limit) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("serviceId", serviceId));
        if (functionIndex != null) {
            filters.add(Filters.eq("functionIndex", functionIndex));
        }
        if (type != null && !type.isBlank()) {
            filters.add(Filters.eq("type", type));
        }
        filters.add(Filters.gte("at", from));
        filters.add(Filters.lte("at", to));

        return collection()
                .find(Filters.and(filters),
                        new FindOptions().sort(Sorts.descending("at")).limit(limit))
                .collect().asList();
    }

    /**
     * 级联删除一批服务的失败记录：与服务的历史同口径（见 Modbus-History.md 第六节）。
     * 删除条数为 0 不算失败。
     */
    public Uni<Long> deleteByServiceIds(List<String> serviceIds) {
        if (serviceIds == null || serviceIds.isEmpty()) {
            return Uni.createFrom().item(0L);
        }
        return collection()
                .deleteMany(Filters.in("serviceId", serviceIds))
                .map(DeleteResult::getDeletedCount);
    }
}
