package cc.openxiot.matrix.db.modbus.history;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
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
     * 启动时确保 {@code (serviceId, pollingIndex, at)} 索引存在（幂等；失败仅告警，不阻断启动）。
     *
     * <p>支撑「某个服务的故障列表，按时间倒序」：前两段等值 + {@code at} 排序。</p>
     */
    public Uni<Void> ensureIndexes() {
        return collection()
                .createIndex(Indexes.ascending("serviceId", "pollingIndex", "at"))
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
     * <p>{@code pollingIndex} 与 {@code type} 为 null 表示不限（分别是「整个服务」与「所有分类」）。
     * 注意索引是 {@code (serviceId, pollingIndex, at)}：不带 {@code pollingIndex} 时只有第一段用得上，
     * 排序要靠内存完成 —— 故障是去重过的，行数很少，这样够用（要不要补索引见 Modbus-History-TODO.md 第二步）。</p>
     *
     * @param limit 条数上限；调用方传「上限 + 1」以便判出「还有更早的没取回来」
     */
    public Uni<List<ModbusServiceFailure>> findRange(String serviceId, Integer pollingIndex, String type,
                                                     Date from, Date to, int limit) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("serviceId", serviceId));
        if (pollingIndex != null) {
            filters.add(Filters.eq("pollingIndex", pollingIndex));
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
     * 某个服务在一段区间内的失败记录**时间线**，只取统计要用的那一列：{@code at}。
     *
     * <p>与 {@link #findRange} 的两处不同，都是有意的：**不打 limit**（调用方是统计，截断会让
     * 「今日异常」变成静默的下限，量级由窗口上限兜住）、**不排序**（统计在内存里分桶，顺序没有意义）。
     * 故障行本就按 message 去重过，行数很少，但口径仍与告警时间线保持一致。</p>
     *
     * <p>返回的对象只有 {@code serviceId} 与 {@code at} 有值，**不要当完整故障行用**。</p>
     */
    public Uni<List<ModbusServiceFailure>> findTimeline(String serviceId, Date from, Date to) {
        return collection()
                .find(Filters.and(Filters.eq("serviceId", serviceId),
                                Filters.gte("at", from), Filters.lte("at", to)),
                        new FindOptions().projection(Projections.include("at")))
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
