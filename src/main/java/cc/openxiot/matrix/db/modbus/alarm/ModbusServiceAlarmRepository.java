package cc.openxiot.matrix.db.modbus.alarm;

import cc.openxiot.common.person.Person;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoWriteException;
import com.mongodb.WriteConcernException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import io.quarkus.mongodb.FindOptions;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ModbusServiceAlarm} 的数据访问（Panache，实体固定落库 {@code modbus}/集合 {@code service-alarms}）。
 *
 * <p>这个仓储里没有一条「整文档替换」：告警行一旦落下，此后只有两种改动 ——
 * 关闭（写 {@code recoveredAt} / {@code closeType}、{@code $unset} {@code openKey}）与
 * 处理（写 {@code handled} / {@code handledBy}）。它们是**互不相交的字段**，用 {@code $set}
 * 各写各的，谁也盖不掉谁 —— 与 {@code ModbusPollRepository} 同一条纪律。</p>
 *
 * <p>写侧的另一条纪律：<b>关闭按整个开行标识 {@code updateMany}，不定位到某条 {@code _id}</b>。
 * 这消除了「幽灵告警永远关不掉」这个失效模式，也让采集热路径只需要「存在性」而不需要身份。</p>
 */
@ApplicationScoped
public class ModbusServiceAlarmRepository  {

    @Inject
    ReactiveMongoClient mongoClient;

    /** MongoDB 的重复键错误码。 */
    private static final int DUPLICATE_KEY = 11000;

    private ReactiveMongoCollection<ModbusServiceAlarm> collection() {
        return mongoClient.getDatabase("modbus").getCollection("service-alarms", ModbusServiceAlarm.class);
    }

    /** 按 id 查询单条告警（供详情/关闭入口用）。 */
    public Uni<ModbusServiceAlarm> findById(ObjectId id) {
        return collection().find(Filters.eq("_id", id)).collect().first();
    }

    /**
     * 启动时确保索引存在（幂等；失败仅告警，不阻断启动，与其它仓储同口径）。
     *
     * <p>建三个：</p>
     * <ol>
     *   <li>{@code {openKey: 1}} <b>unique + partial</b> —— 并发护栏（见 {@link #insertOpen}）。
     *       已实证可用，见下面的冒烟台账。</li>
     *   <li>{@code (serviceId, pollingIndex, openKey)} —— 采集侧每轮查「这个出值开着吗」
     *       （见 {@link #mapOpenRuleByField}），一个方法配 3 个字段也只查一次。</li>
     *   <li>{@code (serviceId, pollingIndex, at)} —— 列表查询，前两段等值 + {@code at} 倒序。</li>
     * </ol>
     *
     * <h4>1 号索引的冒烟台账（2026-09-14，staging）</h4>
     *
     * <p>建之前不能确定：部署是 Postgres 底的 Azure DocumentDB，<b>部分索引支持未实证</b>。
     * 不支持的话 {@code createIndex} 抛错、被这行 {@code recoverWithNull} 静默吞掉 ——
     * 「以为有护栏、其实没有」，比明说没有更糟；而**建错了更危险**：万一服务端忽略
     * {@code partialFilterExpression} 而当成普通唯一索引，已恢复的行 {@code openKey} 是缺失
     * （等同 null），同一出值的第二条已恢复告警会被拒，**告警就开始丢**。</p>
     *
     * <p>在 staging 临时集合 {@code modbus/alarm-smoke} 上实测（跑完 drop），四条都过：</p>
     * <ul>
     *   <li>建索引<b>没抛错、也没被静默吞掉</b>，落成
     *       {@code {"v":2,"key":{"openKey":1},"name":"openKey_unique_partial",
     *       "partialFilterExpression":{"openKey":{"$exists":true}},"unique":true,
     *       "storageEngine":{"enableOrderedIndex":true}}}；</li>
     *   <li>插两条同 {@code openKey} 的，第二条报 11000 {@code Duplicate key violation}</li>
     *   <li>插两条都没有 {@code openKey} 的，<b>两条都成功</b> —— 这一条才证明「部分」真的部分，
     *       普通唯一索引会把第二条的 null 当重复拒掉；</li>
     *   <li>{@code $unset} 关掉一条之后，同一 {@code openKey} 能再开一条。</li>
     * </ul>
     *
     * <p><b>顺带捞到的一条纪律</b>：显式写 {@code openKey: null} 的文档**也算存在**（{@code $exists}
     * 对 null 成立），于是**第二条这样的行会被拒**。所以关闭只能写成 {@code $unset}（见
     * {@link #closeUpdate}），<b>绝不能用 {@code $set openKey: null} 代替</b> —— 那会让「关闭」
     * 在第二条上失败，而失败的样子是静默的（关闭调用方只记日志）。</p>
     */
    public Uni<Void> ensureIndexes() {
        return collection()
                .createIndex(
                        Indexes.ascending("openKey"),
                        new IndexOptions()
                                .name("openKey_unique_partial")
                                .unique(true)
                                .partialFilterExpression(Filters.exists("openKey")))
                .chain(ignored -> collection().createIndex(Indexes.ascending("serviceId", "pollingIndex", "openKey")))
                .chain(ignored -> collection().createIndex(Indexes.ascending("serviceId", "pollingIndex", "at")))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /**
     * 落一条**未恢复**的告警（{@code openKey} 已由调用方填好）。
     *
     * <p>返回值是「**这条是不是我建的**」，用来把并发吞掉：poller 与 API 副本可能同时判定同一
     * {@code (service, function, field)}，两边都读到「没有未恢复的告警」于是都插入。唯一部分索引
     * （见 {@link #ensureIndexes}）挡住第二条，这里把重复键当作**「别的副本先开了」**，
     * 返回 {@code false} 而不是失败 —— 吞掉之后不变式「该键有一条开着的告警」恰好成立，
     * 所以这不是妥协。这与故障去重的竞态**不是同一个后果等级**：那边是追加型日志上多一行信息，
     * 这边两条重复行不会作为一个单位一起恢复（用户处理了 A，B 还挂着未处理，未处理计数永远不对，
     * 页面上同一件事出现两次）。</p>
     *
     * <p>其它失败一律照常外传：调用方（采集链路）自己决定记日志还是咽下去。</p>
     */
    public Uni<Boolean> insertOpen(ModbusServiceAlarm alarm) {
        if (alarm.id == null) { alarm.id = new ObjectId(); }
        return collection().insertOne(alarm)
                .map(ignored -> true)
                .onFailure(ModbusServiceAlarmRepository::isDuplicateKey)
                .recoverWithItem(false);
    }

    /**
     * 这个方法当前**有哪些出值开着告警**（未恢复）：出值名 → 那条开行上的
     * {@link OpenAlarmRule}（哪条规则开的、有没有被处理过）。
     *
     * <p>只投影 {@code field} / {@code ruleId} / {@code handled}：判定要的是「开着的行就是状态」这张表，
     * 不需要行的其余部分。一次查回整个方法的所有开行，所以一个方法配 3 个字段也只查一次。</p>
     *
     * <p><b>值是允许 {@code ruleId} 为 null 的</b>（缺失只可能出现在更早的数据里），所以这里手工装进
     * {@code HashMap} 而不用 {@code Collectors.toMap}（那个连 null 值都不收）——
     * 判定侧 {@code open.containsKey(field)} 只用键，值与当前胜者的 {@code id} 比对时 null 自然不相等。</p>
     *
     * <p>{@code handled} 的缺失转换见 {@link #openRuleOf}：null 与 false 在判定上完全一样。</p>
     */
    public Uni<Map<String, OpenAlarmRule>> mapOpenRuleByField(String serviceId, Integer pollingIndex) {
        return collection()
                .find(Filters.and(
                                Filters.eq("serviceId", serviceId),
                                Filters.eq("pollingIndex", pollingIndex),
                                Filters.exists("openKey")),
                        new FindOptions().projection(Projections.include("field", "ruleId", "handled")))
                .collect().asList()
                .map(rows -> {
                    Map<String, OpenAlarmRule> open = new HashMap<>(rows.size());
                    for (ModbusServiceAlarm row : rows) {
                        if (row.field != null) {
                            open.put(row.field, openRuleOf(row.ruleId, row.handled));
                        }
                    }
                    return open;
                });
    }

    /**
     * 一行的「开着状态」：{@code ruleId} 原样，{@code handled} 把缺失折成 false。
     *
     * <p>抽成静态是为了能单测（本仓库的库测试要连 Mongo）：这一条写错了不会报错，只会让**所有老数据**
     * （{@code handled} 字段缺失）在判定侧被当成「已经处理过」—— 于是每一轮采集都给它们关旧开新，
     * 稳态零写当场失效、告警列表每采一次翻一倍。与 {@link #closeUpdate} / {@link #handleFilter}
     * 同一条理由。</p>
     *
     * <p>{@code null} 当 false，与 {@link #handleFilter} 的 {@code handled: {$ne: true}} 是同一个口径：
     * 「不是 true」就是未处理，判定侧与写入侧对「未处理」的理解必须一模一样。</p>
     */
    static OpenAlarmRule openRuleOf(String ruleId, Boolean handled) {
        return new OpenAlarmRule(ruleId, Boolean.TRUE.equals(handled));
    }

    /**
     * 某个服务下**所有方法**当前开着的告警行（未恢复），只投影对账要用的坐标。
     *
     * <p>定义变更对账用（{@code ModbusAlarmReconciler}）：它要按「新定义里还有没有这条规则」
     * 逐行判，所以得拿到 {@code pollingIndex} / {@code field} / {@code ruleId} 三样 ——
     * 与 {@link #mapOpenRuleByField}（采集热路径，只关心一个方法、只要出值名与规则标识）的用途不同。</p>
     */
    public Uni<List<ModbusServiceAlarm>> listOpenByServiceId(String serviceId) {
        if (serviceId == null) {
            return Uni.createFrom().item(List.of());
        }
        return collection()
                .find(Filters.and(Filters.eq("serviceId", serviceId), Filters.exists("openKey")),
                        new FindOptions().projection(
                                Projections.include("serviceId", "pollingIndex", "field", "ruleId")))
                .collect().asList();
    }

    /**
     * 全部开着的告警行（跨服务），只投影对账要用的坐标：启动时扫一次残留用。
     *
     * <p>不加服务过滤是**故意的**：启动那次要兜的是「直接改库」（运维脚本、迁移），
     * 那时不知道哪个服务被动过。开着的行本来就少（一次越限一条、恢复即关闭），
     * 所以这一次全表读的规模由「当前有多少告警开着」决定，不是由库的大小决定。</p>
     */
    public Uni<List<ModbusServiceAlarm>> listAllOpen() {
        return collection()
                .find(Filters.exists("openKey"),
                        new FindOptions().projection(
                                Projections.include("serviceId", "pollingIndex", "field", "ruleId")))
                .collect().asList();
    }

    /**
     * 关闭一批出值的告警（值回到正常、或新定义已不再配它）。
     *
     * <p>条件是**整个开行标识**（{@code serviceId} + {@code pollingIndex} + {@code field} +
     * {@code openKey} 存在），不是某条 {@code _id}；{@code updateMany} 因此天然幂等 ——
     * 两个评估者同时关闭同一出值，第二个匹配 0 条，不算失败。
     * 过滤条件里那个 {@code exists("openKey")} 不是多余的：已恢复的行 {@code openKey} 已被
     * {@code $unset}，不该被再关一次而覆盖掉它第一次的 {@code recoveredAt}。</p>
     *
     * @param fields    要关的出值名；空集合直接返回 0，不查库
     * @param closeType {@link ModbusServiceAlarm#CLOSE_VALUE} 或 {@link ModbusServiceAlarm#CLOSE_DEFINITION}
     * @return 实际关闭的条数
     */
    public Uni<Long> closeByKeys(String serviceId, Integer pollingIndex, Collection<String> fields,
                                 String closeType, Date at) {
        if (fields == null || fields.isEmpty()) {
            return Uni.createFrom().item(0L);
        }
        return collection().updateMany(closeFilter(serviceId, pollingIndex, fields), closeUpdate(at, closeType))
                .map(UpdateResult::getModifiedCount);
    }

    /**
     * 关闭的过滤条件：**整个开行标识**，不是某条 {@code _id}。
     *
     * <p>抽成静态是为了能单测（本仓库的库测试要连 Mongo）：这个条件写错了不会报错，
     * 只会「关不掉」—— 一条永远关不掉的告警比一条报错的写难查得多。</p>
     */
    static Bson closeFilter(String serviceId, Integer pollingIndex, Collection<String> fields) {
        return Filters.and(
                Filters.eq("serviceId", serviceId),
                Filters.eq("pollingIndex", pollingIndex),
                Filters.in("field", fields),
                Filters.exists("openKey"));
    }

    /**
     * 关闭的写语句：{@code $set recoveredAt / closeType} + <b>{@code $unset openKey}</b>。
     *
     * <p>那个 {@code $unset} 就是「关闭」本身：{@code openKey} 是状态，只写 {@code recoveredAt}
     * 而不拿掉它，这条会永远被算作「还开着」（采集侧的判断、列表的筛选、汇总的 {@code open} 计数全都看它）。
     * <b>也不能换成 {@code $set openKey: null}</b> —— 那条唯一部分索引的条件是
     * {@code {openKey: {$exists: true}}}，而 {@code $exists} 对显式 null 是成立的，于是第二条
     * 「关着的」行会被当重复拒掉（staging 上实测过，见 {@link #ensureIndexes} 的冒烟台账）。
     * 失败的样子还是静默的：关闭的调用方只记日志。</p>
     */
    static Bson closeUpdate(Date at, String closeType) {
        return Updates.combine(
                Updates.set("recoveredAt", at),
                Updates.set("closeType", closeType),
                Updates.unset("openKey"));
    }

    /**
     * 用户点「处理」：CAS 写回执（{@code handled} 从「不是 true」变成 {@code true}）。
     *
     * <p>用 {@code updateOne} 而**绝不 {@code replaceOne}**：处理与告警关闭是两条互不相交的字段写，
     * 替换会把另一方刚写的字段抹掉。过滤条件带 {@code handled: {$ne: true}} 是为了让重复点击
     * 只生效一次（判定与「已处理」/「不存在」的区分留给调用方，它才拿得到精确文案）。</p>
     *
     * <p>{@code handledBy} 显式写成一个 {@link Document}（而不是直接塞 {@link Person}）：
     * 更新语句里没有实体字段的声明类型可用，走 POJO 编码在这里是多余的猜测 ——
     * 与 {@code SpaceRepository.pushAccess} 同口径。处理时刻取 {@code Person.timestamp}，
     * 不另开字段（与 {@code creator} / {@code updater} 一致）。</p>
     *
     * <p>过滤条件与写语句抽成了 {@link #handleFilter} / {@link #handleUpdate}：本仓库的库测试要连
     * Mongo，而这两样恰恰是「写错了也不报错、只是行为悄悄不对」的部分（比如整文档替换会抹掉关闭字段）。</p>
     *
     * @return 改动条数：1 = 这次处理生效；0 = 已经处理过了（或行不在了，由调用方分辨）
     */
    public Uni<Long> handle(ObjectId id, Person who) {
        return collection()
                .updateOne(handleFilter(id), handleUpdate(who))
                .map(UpdateResult::getModifiedCount);
    }

    /** 处理的过滤条件：{@code _id} 定位到一条，且 {@code handled} 还不是 true（重复点击只生效一次）。 */
    static Bson handleFilter(ObjectId id) {
        return Filters.and(Filters.eq("_id", id), Filters.ne("handled", true));
    }

    /** 处理的写语句：两个互不相干的字段各写各的 `$set`（不是整文档替换）。 */
    static Bson handleUpdate(Person who) {
        return Updates.combine(
                Updates.set("handled", true),
                Updates.set("handledBy", handledByDocument(who)));
    }

    /** 处理人落成一个显式 {@link Document}（理由见 {@link #handle}）。 */
    private static Document handledByDocument(Person who) {
        return new Document("id", who.id)
                .append("name", who.name)
                .append("timestamp", who.timestamp);
    }

    /**
     * 告警清单：一个服务下的一段区间，按 {@code at} **倒序**（最近的在前）。
     *
     * <p>{@code filter} 里为 null 的项表示不限。空间级查询由调用方**逐服务**调这个方法再内存归并
     * （与故障清单同口径：不做聚合管道）。</p>
     *
     * @param limit 条数上限；调用方传「上限 + 1」以便判出「还有更早的没取回来」
     */
    public Uni<List<ModbusServiceAlarm>> findRange(String serviceId, RangeFilter filter, int limit) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("serviceId", serviceId));
        if (filter.pollingIndex() != null) {
            filters.add(Filters.eq("pollingIndex", filter.pollingIndex()));
        }
        if (filter.field() != null && !filter.field().isBlank()) {
            filters.add(Filters.eq("field", filter.field()));
        }
        if (filter.level() != null && !filter.level().isBlank()) {
            filters.add(Filters.eq("level", filter.level()));
        }
        if (filter.open() != null) {
            filters.add(filter.open() ? Filters.exists("openKey") : Filters.exists("openKey", false));
        }
        if (filter.handled() != null) {
            filters.add(filter.handled()
                    ? Filters.eq("handled", true)
                    : Filters.ne("handled", true));
        }
        filters.add(Filters.gte("at", filter.from()));
        filters.add(Filters.lte("at", filter.to()));

        return collection()
                .find(Filters.and(filters),
                        new FindOptions().sort(Sorts.descending("at")).limit(limit))
                .collect().asList();
    }

    /**
     * 某个服务在一段区间内的告警**时间线**，只取统计要用的两列：{@code at} 与 {@code text}。
     *
     * <p>与 {@link #findRange} 的两处不同，都是有意的：</p>
     * <ul>
     *   <li><b>不打 limit</b>：调用方是统计（今日条数、按文本分组、按整点分桶），
     *       截断会让结果变成静默的下限 —— 而图上完全看不出来。量级由调用方的窗口上限兜住。</li>
     *   <li><b>不排序</b>：统计在内存里分桶，顺序没有意义；{@link #findRange} 那个倒序是清单页要的。</li>
     * </ul>
     *
     * <p>索引 {@code (serviceId, pollingIndex, at)} 上这里只有第一段用得上（与故障清单的
     * {@code ModbusServiceFailureRepository#findRange} 同一个道理）。返回的对象只有
     * {@code serviceId} / {@code at} / {@code text} 有值，**不要当完整告警行用**。</p>
     */
    public Uni<List<ModbusServiceAlarm>> findTimeline(String serviceId, Date from, Date to) {
        return collection()
                .find(Filters.and(Filters.eq("serviceId", serviceId),
                                Filters.gte("at", from), Filters.lte("at", to)),
                        new FindOptions().projection(Projections.include("at", "text")))
                .collect().asList();
    }

    /** 级联删除一批服务的告警：与历史、故障同口径（服务 / 设备被删时一并删）。 */
    public Uni<Long> deleteByServiceIds(List<String> serviceIds) {
        if (serviceIds == null || serviceIds.isEmpty()) {
            return Uni.createFrom().item(0L);
        }
        return collection()
                .deleteMany(Filters.in("serviceId", serviceIds))
                .map(DeleteResult::getDeletedCount);
    }

    /**
     * 告警清单的筛选条件（为 null 的项 = 不限）。
     *
     * @param open    {@code true} 只看未恢复、{@code false} 只看已恢复、{@code null} 都要
     * @param handled 同上，针对「是否已处理」
     */
    public record RangeFilter(Integer pollingIndex, String field, String level,
                              Boolean open, Boolean handled, Date from, Date to) {
    }

    /**
     * 是不是「重复键」失败（MongoDB 11000）。
     *
     * <p>三种异常类型都认，因为同一次冲突在不同调用路径上会以不同形状冒出来，而**判漏的后果是静默的**
     * （两条开着的告警，见 {@link #insertOpen}）：单条插入抛 {@link MongoWriteException}、
     * 服务端命令抛 {@link MongoCommandException}、驱动自己那个「这就是重复键」的类型
     * {@link com.mongodb.DuplicateKeyException} 继承的是 {@link WriteConcernException}
     * （**不是** {@code MongoWriteException} 的子类，容易想当然）。三者都以错误码判定，
     * 不靠类型名称猜 —— 11000 才是语义，类型只是它的外壳。</p>
     *
     * <p>要顺着 {@code cause} 链找：驱动的异常有可能被上层包一层。链上出现自引用时立刻停下
     * （异常里的自引用 cause 是已知的坑，会让这里变成死循环）。</p>
     */
    public static boolean isDuplicateKey(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof MongoWriteException write
                    && write.getError().getCode() == DUPLICATE_KEY) {
                return true;
            }
            if (current instanceof MongoCommandException command
                    && command.getErrorCode() == DUPLICATE_KEY) {
                return true;
            }
            if (current instanceof WriteConcernException concern
                    && concern.getErrorCode() == DUPLICATE_KEY) {
                return true;
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return false;
    }
}
