package cc.openxiot.matrix.db.modbus.poll;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.DeleteResult;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * {@link ModbusPoll} 的数据访问（Panache，实体固定落库 {@code modbus}/集合 {@code polls}）。
 *
 * <p>写入分成几条互不重叠的路，各自 {@code $set} 自己那几个字段：
 * {@link #updatePlan} 只动调度字段（{@code did} / {@code interval}，对账用）、
 * {@link #claim} 只动租约与「下一次时刻」（派发那一刻）、
 * {@link #updateSuccess} / {@link #updateFailure} 只动结果（采集回来）。
 * <b>不做整文档替换</b>：替换会让「对账写 did」和「采集写 lastStatus」互相覆盖
 * —— 这两个写发生在不同的线程甚至不同的进程上，而它们本来毫无关系。</p>
 */
@ApplicationScoped
public class ModbusPollRepository  {

    @Inject
    ReactiveMongoClient mongoClient;

    /** {@link ModbusPoll#lastStatus} 的两个取值：只有成功与失败**两种**，跳过不是状态。 */
    public static final String STATUS_OK = "OK";
    public static final String STATUS_FAILED = "FAILED";

    private ReactiveMongoCollection<ModbusPoll> collection() {
        return mongoClient.getDatabase("modbus").getCollection("polls", ModbusPoll.class);
    }

    /**
     * 启动时确保索引存在（幂等；失败仅告警，不阻断启动，与其它仓储同口径）。
     *
     * <p>{@code (serviceId, pollingIndex)} 唯一：一条 = 一个方法的任务，重复会让同一个方法被采两次。
     * {@code (nextRunAt, leaseUntil)}：本轮的内存时间轮并不扫库，建它是为了运维查询
     * 与「以后真要多副本分片」时不必再等索引建好。</p>
     */
    public Uni<Void> ensureIndexes() {
        return collection()
                .createIndex(Indexes.ascending("serviceId", "pollingIndex"), new IndexOptions().unique(true))
                .chain(ignored -> dropLegacyFunctionIndex())
                .chain(ignored -> collection().createIndex(Indexes.ascending("nextRunAt", "leaseUntil")))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /**
     * 兼容旧索引：早期版本建过非 sparse 的 {@code (serviceId, functionIndex)} 唯一索引
     * （名字 {@code serviceId_1_functionIndex_1}）。改名成 {@code pollingIndex} 之后，
     * 新行会带着 {@code "pollingIndex"} 而 {@code "functionIndex"} 留空 —— 若旧索引还在，
     * 第二行 null 就会违反旧索引的唯一性，导致插入失败。所以这里幂等地把它删掉
     * （不存在时容错，不阻断启动）。
     */
    private Uni<Void> dropLegacyFunctionIndex() {
        return collection().dropIndex("serviceId_1_functionIndex_1")
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /** 全量任务（对账与启动恢复用）。表很小：一条 = 一个方法，与采样频率无关。 */
    public Uni<List<ModbusPoll>> listAll() {
        return collection().find().collect().asList();
    }

    /** 某个服务下的任务，按方法序号升序。 */
    public Uni<List<ModbusPoll>> findByServiceId(String serviceId) {
        if (serviceId == null || serviceId.isBlank()) {
            return Uni.createFrom().item(List.of());
        }
        return collection().find(Filters.eq("serviceId", serviceId)).collect().asList();
    }

    /** 一批服务的任务（查询接口按空间取：空间 → serviceId 列表 → 任务）。 */
    public Uni<List<ModbusPoll>> listByServiceIds(List<String> serviceIds) {
        if (serviceIds == null || serviceIds.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        return collection().find(Filters.in("serviceId", serviceIds)).collect().asList();
    }

    /** 新增一条（{@code _id} 由 Panache 生成并回写到 {@code poll.id}）。 */
    public Uni<Void> insert(ModbusPoll poll) {
        if (poll.id == null) { poll.id = new ObjectId(); }
        return collection().insertOne(poll).replaceWithVoid();
    }

    /** 对账：只更新调度字段（定义改了 `did` / `interval`，或周期变了要重排下一次）。 */
    public Uni<Void> updatePlan(ObjectId id, String did, Integer interval, Date nextRunAt) {
        List<Bson> sets = new ArrayList<>(3);
        sets.add(Updates.set("did", did));
        if (interval != null) {
            sets.add(Updates.set("interval", interval));
        }
        if (nextRunAt != null) {
            sets.add(Updates.set("nextRunAt", nextRunAt));
        }
        return collection()
                .updateOne(Filters.eq("_id", id), Updates.combine(sets))
                .replaceWithVoid();
    }

    /**
     * 采集成功：只更新结果状态，**不动 {@code lastRunAt}**（它记的是「开始执行」的时刻，
     * 在 {@link #claim} 里已经写过）。
     *
     * <p>成功要把上一次的失败原因清掉（否则前端会一直看到一条早已恢复的报错），连续失败计数归零。</p>
     */
    public Uni<Void> updateSuccess(ObjectId id) {
        Bson updates = Updates.combine(
                Updates.set("lastStatus", STATUS_OK),
                Updates.set("failCount", 0),
                Updates.unset("lastError"));
        return collection()
                .updateOne(Filters.eq("_id", id), updates)
                .replaceWithVoid();
    }

    /**
     * 采集失败：只更新结果状态，**任务本身不动**（不删、不改周期、不退避）。
     *
     * <p>计数用 {@code $inc} 而不是「读出来 +1 再写」：多副本并存时（租约过期被别人接管的那一小段）
     * 读改写会把对方的 +1 覆盖掉，而 {@code $inc} 是原子的；顺带也省掉一次读。</p>
     */
    public Uni<Void> updateFailure(ObjectId id, String error) {
        Bson updates = Updates.combine(
                Updates.set("lastStatus", STATUS_FAILED),
                Updates.set("lastError", error),
                Updates.inc("failCount", 1));
        return collection()
                .updateOne(Filters.eq("_id", id), updates)
                .replaceWithVoid();
    }

    /**
     * 认领这一轮并前推下一次时刻：**一次** {@code findOneAndUpdate} 同时做完「这次归我跑」与
     * 「记下什么时候跑的、下次什么时候跑」。返回 null = 没认领到（别人正持有），本次不跑。
     *
     * <p>三个字段一起写在一次原子操作里是有意的：租约与 {@code nextRunAt} 必须**同时**生效。
     * 分开写的话，没抢到租约的那个副本也会把 {@code nextRunAt} 前推（覆盖掉胜者的续排），
     * 或者胜者抢到了租约却没来得及前推就被杀掉 —— 后者会让任务按旧的 {@code nextRunAt} 立刻重跑。</p>
     *
     * <p>可认领的三种情形：没人持有（{@code leaseUntil} 为 null / 缺失）、租约已过期、**持有者还是自己**
     * （缺省的单副本部署下永远命中这一条，等于空跑）。与
     * {@code ModbusPollSchedule.leaseClaimable} 是同一份口径，改一处要改两处。</p>
     *
     * <p>注意必须显式写 {@code eq("leaseUntil", null)}：Mongo 的 {@code $lt} 有类型分桶，
     * 单写 {@code lt("leaseUntil", now)} 匹配不到字段缺失的文档 —— 而「从没被跑过」的任务正是这一种。</p>
     *
     * <p><b>不做提前释放</b>：租约由下一次认领续期，靠 {@code leaseUntil} 到期自然失效。
     * 提前释放会让一次被中断的采集立刻被另一个副本重发（同一帧上总线两次），
     * 而它的收益只是停机后少等一段 —— 见 Modbus-Service-Poller.md 第七节。</p>
     */
    public Uni<ModbusPoll> claim(ObjectId id, String owner, Date now, Date leaseUntil, Date nextRunAt) {
        Bson filter = Filters.and(
                Filters.eq("_id", id),
                Filters.or(
                        Filters.eq("leaseUntil", null),
                        Filters.lt("leaseUntil", now),
                        Filters.eq("leaseOwner", owner)));
        Bson update = Updates.combine(
                Updates.set("leaseOwner", owner),
                Updates.set("leaseUntil", leaseUntil),
                Updates.set("lastRunAt", now),
                Updates.set("nextRunAt", nextRunAt));
        return collection().findOneAndUpdate(filter, update);
    }

    /** 级联删除一批服务的任务（服务或依赖设备被删时调用），删除条数为 0 不算失败。 */
    public Uni<Long> deleteByServiceIds(List<String> serviceIds) {
        if (serviceIds == null || serviceIds.isEmpty()) {
            return Uni.createFrom().item(0L);
        }
        return collection()
                .deleteMany(Filters.in("serviceId", serviceIds))
                .map(DeleteResult::getDeletedCount);
    }

    /** 对账：删掉某个服务里已不再需要轮询的方法（方法被删了、或周期被去掉了）。 */
    public Uni<Long> deleteByPollingIndexes(String serviceId, List<Integer> pollingIndexes) {
        if (serviceId == null || pollingIndexes == null || pollingIndexes.isEmpty()) {
            return Uni.createFrom().item(0L);
        }
        return collection()
                .deleteMany(Filters.and(
                        Filters.eq("serviceId", serviceId),
                        Filters.in("pollingIndex", pollingIndexes)))
                .map(DeleteResult::getDeletedCount);
    }
}
