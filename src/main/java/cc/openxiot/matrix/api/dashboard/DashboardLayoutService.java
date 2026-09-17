package cc.openxiot.matrix.api.dashboard;

import cc.openxiot.common.person.Person;
import cc.openxiot.matrix.api.dashboard.params.DashboardLayoutDto;
import cc.openxiot.matrix.db.dashboard.DashboardLayoutEntity;
import cc.openxiot.matrix.db.dashboard.DashboardLayoutRepository;
import cc.openxiot.matrix.db.dashboard.DashboardWidget;
import cc.openxiot.matrix.db.space.SpaceEntity;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * 看板布局的读写：查（没有就给预置）、存（乐观锁）、恢复默认。
 *
 * <p><b>鉴权不在这里</b>：读写权限（成员 / 管理员）由资源层的 {@code requireSpaceMember} /
 * {@code requireSpaceAdmin} 判，本类只管「怎么读怎么写」。这与 validator 的分工是同一条线：
 * 权限、合法性、持久化三层各管各的。</p>
 *
 * <p><b>布局按根空间存</b>：路径上给的 {@code spaceId} 可能是子空间，而布局是「项目一份」，
 * 所以落库与查询一律用 {@code space.rootSpaceId()}（见 {@link #spaceIdOf}）。这样管理员在
 * 子空间页面和项目页面看到的是同一份布局，也不会因为换了子空间就多出一份空布局。
 * 判定归属（{@code DashboardLayoutValidator}）与取数（{@code WidgetDataService}）用的都是
 * 同一个基准 —— 三处必须一致，否则会出现「存得下但渲染是空的」这种最难查的不一致。</p>
 */
@ApplicationScoped
public class DashboardLayoutService {

    @Inject
    DashboardLayoutRepository repo;

    /** 建索引开关：测试进程关掉它，免得启动就连库（与 {@code modbus.index.enabled} 同口径）。 */
    @ConfigProperty(name = "dashboard.index.enabled", defaultValue = "true")
    boolean indexEnabled;

    /**
     * 启动时确保 {@code spaceId} 唯一索引存在。建索引失败只留告警，不阻断启动 ——
     * 没有索引只是慢，起不来是另一回事。详见 {@link DashboardLayoutRepository#ensureIndexes()}。
     */
    void init(@Observes StartupEvent event) {
        if (!indexEnabled) {
            return;
        }
        repo.ensureIndexes().await().indefinitely();
    }

    /**
     * 读布局：没有文档时返回**预置布局**（不落库、不报 404）。
     *
     * <p>预置布局的 {@code version} 是 0，而 0 恰好也是「首次保存要提交的版本号」，
     * 所以前端不需要知道「我拿到的是预置还是真的」—— 直接原样回传 {@code version} 即可。</p>
     */
    public Uni<DashboardLayoutEntity> load(SpaceEntity space) {
        String spaceId = spaceIdOf(space);
        return repo.findBySpaceId(spaceId)
                .map(stored -> stored != null ? stored : PresetLayouts.build(spaceId));
    }

    /**
     * 保存（整体替换）。乐观锁的语义：
     *
     * <ul>
     *   <li>没有文档时，提交的 {@code version} 必须是 {@code 0}（预置布局的版本），否则报冲突；
     *       成功后版本变 1</li>
     *   <li>有文档时，提交的版本必须与库里一致，否则报冲突；成功后版本 +1</li>
     * </ul>
     *
     * <p>当前用户既是首次的 {@code creator} 也是 {@code updater}；后续保存只动 {@code updater}，
     * 保住「谁建的这份看板」。</p>
     */
    public Uni<DashboardLayoutEntity> save(SpaceEntity space, DashboardLayoutDto body, Person who) {
        String spaceId = spaceIdOf(space);
        return repo.findBySpaceId(spaceId).chain(current -> {
            if (current == null) {
                return create(spaceId, body, who);
            }
            if (body.version != current.version) {
                return conflict();
            }
            return update(current, body, who);
        });
    }

    /** 恢复默认：删掉文档，下次读又回到预置布局。没有文档时也算成功（用户的目标就是「没有自定义」）。 */
    public Uni<Long> reset(SpaceEntity space) {
        return repo.deleteBySpaceId(spaceIdOf(space));
    }

    private Uni<DashboardLayoutEntity> create(String spaceId, DashboardLayoutDto body, Person who) {
        if (body.version != 0L) {
            // 客户端说「库里是第 N 版」，可库里什么都没有 —— 多半是别人刚把这份布局删了（恢复默认）
            return conflict();
        }
        DashboardLayoutEntity layout = new DashboardLayoutEntity();
        layout.spaceId = spaceId;
        layout.version = 1L;
        layout.widgets = normalized(body.widgets);
        layout.creator = who;
        layout.updater = who;
        return repo.insert(layout)
                // 两个管理员同时创建第一份布局时，唯一索引会让后一个拿到 11000：
                // 这与「版本不一致」是同一件事（别人先写成功了），报冲突而不是 500
                .onFailure(DashboardLayoutRepository::isDuplicateKey)
                .transform(failure -> new IllegalArgumentException(VERSION_CONFLICT))
                .replaceWith(layout);
    }

    private Uni<DashboardLayoutEntity> update(DashboardLayoutEntity current, DashboardLayoutDto body, Person who) {
        long expected = current.version;
        current.version = expected + 1L;
        current.widgets = normalized(body.widgets);
        // creator 不动：这份看板是谁建的，不该因为别人改了布局就变成别人建的
        current.updater = who;
        return repo.replaceIfVersion(current, expected).map(written -> {
            if (!written) {
                // 走到这里说明「读」与「写」之间有别人写成功过 —— 版本比较与写入必须是原子的，
                // 所以判断依据是 replaceOne 的匹配数，不是上面那次读（见 replaceIfVersion 的说明）
                throw new IllegalArgumentException(VERSION_CONFLICT);
            }
            return current;
        });
    }

    /**
     * 落库前的规范化：缺省 {@code refresh} 补 60。
     *
     * <p><b>顺序原样保留</b>：{@code widgets} 的数组顺序就是屏幕顺序，这里不排序、不补位。
     * 服务端不再持有任何排版知识（{@code size} 只是个档位名，占几列几行由前端算），
     * 所以这一层今天只剩「补一个缺省的刷新间隔」这一件事。</p>
     *
     * <p>{@code config} 原样落库，不做「补齐缺省项」：缺省项由读取方解释
     * （{@code window} 缺省即不适用、{@code chart} 缺省即饼图），补空值会让「没设」和
     * 「设成了空」再也分不开（与 {@code DashboardLayoutCodec} 不补空值同口径）。</p>
     */
    private static List<DashboardWidget> normalized(List<DashboardWidget> widgets) {
        if (widgets == null) {
            return List.of();
        }
        for (DashboardWidget widget : widgets) {
            if (widget == null) {
                continue;
            }
            if (widget.refresh == null) {
                widget.refresh = DEFAULT_REFRESH_SECONDS;
            }
        }
        return widgets;
    }

    /** 缺省自动刷新间隔（秒）。所有卡片都是纯读，没有需要强制 0 的类型。 */
    private static final int DEFAULT_REFRESH_SECONDS = 60;

    private static final String VERSION_CONFLICT =
            "layout has been modified by someone else, please reload";

    private static Uni<DashboardLayoutEntity> conflict() {
        return Uni.createFrom().failure(new IllegalArgumentException(VERSION_CONFLICT));
    }

    /** 布局的持久化键：根空间（项目）。路径上给子空间时也归到同一个项目。 */
    public static String spaceIdOf(SpaceEntity space) {
        String rootId = space.rootSpaceId();
        // rootSpaceId() 在 parentId 为空时返回自己的 id，正常不会为 null；真为 null 时退回实体 id，
        // 总比拿 null 当键（所有空间共用一份布局）安全
        return rootId == null || rootId.isBlank() ? space.id.toString() : rootId;
    }
}
