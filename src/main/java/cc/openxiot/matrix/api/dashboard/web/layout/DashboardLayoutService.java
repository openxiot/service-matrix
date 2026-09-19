package cc.openxiot.matrix.api.dashboard.web.layout;

import cc.openxiot.common.person.Person;
import cc.openxiot.matrix.api.dashboard.web.params.DashboardLayoutDto;
import cc.openxiot.matrix.db.dashboard.web.DashboardLayoutEntity;
import cc.openxiot.matrix.db.dashboard.web.DashboardLayoutRepository;
import cc.openxiot.matrix.db.dashboard.web.widget.DashboardWidget;
import cc.openxiot.matrix.db.space.SpaceEntity;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * 看板布局的读写：查（没有就给预置）、存（乐观锁）、取预置（只读，给「恢复默认」当草稿起点）。
 *
 * <p><b>鉴权不在这里</b>：读写权限（成员 / 管理员）由资源层的 {@code requireSpaceMember} /
 * {@code requireSpaceAdmin} 判，本类只管「怎么读怎么写」。这与 validator 的分工是同一条线：
 * 权限、合法性、持久化三层各管各的。</p>
 *
 * <p><b>布局按根空间存</b>：路径上给的 {@code spaceId} 可能是子空间，而布局是「项目一份」，
 * 所以落库与查询一律用 {@code space.rootSpaceId()}。这样管理员在
 * 子空间页面和项目页面看到的是同一份布局，也不会因为换了子空间就多出一份空布局。
 * 判定归属（{@code WidgetOwnershipValidator}）与取数（{@code WidgetDataService}）用的都是
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
        String spaceId = space.rootSpaceId();
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
        String spaceId = space.rootSpaceId();
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

    /**
     * 预置布局（只读）。「恢复默认」拿它当**草稿的起点** —— 由前端装进草稿、标脏，
     * 用户再点「保存布局」才落库（修订（十七））。所以这里是纯读：一个字节都不写，
     * 也就不需要「先删掉文档」那一步。
     */
    public DashboardLayoutEntity preset(SpaceEntity space) {
        return PresetLayouts.build(space.rootSpaceId());
    }

    private Uni<DashboardLayoutEntity> create(String spaceId, DashboardLayoutDto body, Person who) {
        if (body.version != 0L) {
            // 客户端说「库里是第 N 版」，可库里什么都没有。改造前这条路是「别人刚点了恢复默认」
            // （那时它真会删文档）；现在没有删除这一说了，剩下的可能只有并发首次保存或脏数据
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
     * 落库前的规范化：**今天只做一件事 —— 把 {@code null} 的清单当成空清单**。
     *
     * <p>它原先还负责「缺省 {@code refresh} 补 60」。自动刷新间隔改成工具条上那个**全局**的
     * 档位之后（修订（十七）），卡片上不再有这个字段，补缺省这件事也就没有了对象。</p>
     *
     * <p><b>位置与顺序都原样保留</b>：坐标（{@code x}/{@code y}）与数组顺序（阅读顺序）
     * 都一个字节不动 —— 这里不排序、不补位、不校验重叠。服务端不持有排版知识
     * （{@code size} 只是个档位名，占几列几行由前端算），重排是前端的活。</p>
     *
     * <p>{@code config} 原样落库，不做「补齐缺省项」：缺省项由读取方解释
     * （{@code window} 缺省即不适用、{@code chart} 缺省即饼图），补空值会让「没设」和
     * 「设成了空」再也分不开（与 {@code DashboardLayoutCodec} 不补空值同口径）。</p>
     */
    private static List<DashboardWidget> normalized(List<DashboardWidget> widgets) {
        return widgets == null ? List.of() : widgets;
    }

    private static final String VERSION_CONFLICT =
            "layout has been modified by someone else, please reload";

    private static Uni<DashboardLayoutEntity> conflict() {
        return Uni.createFrom().failure(new IllegalArgumentException(VERSION_CONFLICT));
    }
}
