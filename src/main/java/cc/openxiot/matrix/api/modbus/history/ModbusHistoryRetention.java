package cc.openxiot.matrix.api.modbus.history;

/**
 * 历史明细的保留策略：把「保留几天」翻译成 TTL 索引要的秒数，并判定既有索引该怎么动
 * （规则见 Modbus-History.md 第六节）。
 *
 * <p>纯函数、无状态、不碰数据库：与 {@link ModbusHistoryPolicy} 同风格 —— 判定与 Mongo 返回的
 * 索引文档解耦，好让它跑得了单测。仓储那边只负责照着 {@link Action} 执行。</p>
 *
 * <p>部署在 Azure DocumentDB（原 Cosmos DB for MongoDB vCore）上，它跑的是建在 PostgreSQL 上的
 * 开源 DocumentDB 引擎、不是 MongoDB 本体：**服务端认不认自定义字段上的 TTL 只能实测**
 * （见 Modbus-History.md 第六节）。这里只管口径，不管能不能删。</p>
 */
public final class ModbusHistoryRetention {

    /** 一天的秒数 —— TTL 索引的 {@code expireAfterSeconds} 是秒，而配置项是「天」。 */
    public static final long SECONDS_PER_DAY = 86_400L;

    private ModbusHistoryRetention() {
    }

    /** 对 {@code at} 上那条 TTL 索引要做的事。 */
    public enum Action {
        /** 什么都不做（秒数已经一致，或者关着且本来就没有索引）。 */
        NONE,
        /** 建（库里还没有）。 */
        CREATE,
        /** 先删了再建（秒数变了；见 {@link #decide} 的说明为什么不能就地改）。 */
        RECREATE,
        /** 只删（关掉保留策略：删掉才是真「关」，见 {@link #ttlSeconds}）。 */
        DROP
    }

    /**
     * 保留天数 → TTL 秒数。
     *
     * <p>{@code <= 0} 表示**关**（返回 0）：调用方据此把库里可能已有的 TTL 索引删掉 ——
     * 「关」就是真的没有东西会被删，而不是「不再新建」。这是这套策略唯一的关闭方式，
     * 因为缺省是 90 天，要关只能显式设成 0。</p>
     */
    public static long ttlSeconds(int days) {
        return days <= 0 ? 0L : days * SECONDS_PER_DAY;
    }

    /**
     * 该对既有 TTL 索引做什么。
     *
     * <p>改 TTL 在原版 MongoDB 里是 {@code collMod} 的活，而托管服务的命令支持面窄
     * （见 Modbus-History.md 第六节），所以走「秒数对不上就 dropIndex 再 createIndex」这条谁都能走的路 ——
     * 否则「把 90 天改成 30 天」会静默不生效，而 90 天前的数据继续躺在库里。
     * 每次启动都会走一遍这里，所以秒数一致时必须是 {@link Action#NONE}：
     * drop + create 在大集合上是有代价的，不能变成每次重启都重建。</p>
     *
     * @param existingSeconds 既有 TTL 索引上的 {@code expireAfterSeconds}；
     *                        **没有 TTL 索引时传 null**
     */
    public static Action decide(Integer existingSeconds, long wantedSeconds) {
        if (wantedSeconds <= 0) {
            // 关：库里还有就删掉，没有就什么都不做
            return existingSeconds == null ? Action.NONE : Action.DROP;
        }
        if (existingSeconds == null) {
            return Action.CREATE;
        }
        return existingSeconds.longValue() == wantedSeconds ? Action.NONE : Action.RECREATE;
    }
}
