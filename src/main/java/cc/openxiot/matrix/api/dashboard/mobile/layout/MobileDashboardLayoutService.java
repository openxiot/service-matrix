package cc.openxiot.matrix.api.dashboard.mobile.layout;

import cc.openxiot.common.person.Person;
import cc.openxiot.matrix.api.dashboard.mobile.params.MobileDashboardLayoutDto;
import cc.openxiot.matrix.db.dashboard.mobile.MobileDashboardWidget;
import cc.openxiot.matrix.db.dashboard.mobile.MobileDashboardLayoutEntity;
import cc.openxiot.matrix.db.dashboard.mobile.MobileDashboardLayoutRepository;
import cc.openxiot.matrix.db.space.SpaceEntity;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * 移动端看板布局的读写：查（没有就给预置）、存（乐观锁）、取预置（只读，给「恢复默认」当草稿起点）。
 *
 * <p>镜像 {@code WebDashboardLayoutService}：同一套「鉴权不在这里（在资源层）」「布局按根空间存」
 * 「乐观锁」约定。差别只在实体/仓库/预置换成移动端的（{@code Full/Half} 档位、无坐标），
 * 以及<b>没有坐标要原样保留</b> —— 移动端布局的阅读顺序就是数组顺序，一份字节不动地收存。</p>
 *
 * <p><b>鉴权不在这里</b>：读写权限（成员 / 管理员）由资源层的 {@code DashboardAccess.spaceMember} /
 * {@code spaceAdmin} 判，本类只管「怎么读怎么写」。</p>
 */
@ApplicationScoped
public class MobileDashboardLayoutService {

    @Inject
    MobileDashboardLayoutRepository repo;

    /** 建索引开关：测试进程关掉它，免得启动就连库（与 Web 布局同口径）。 */
    @ConfigProperty(name = "dashboard.index.enabled", defaultValue = "true")
    boolean indexEnabled;

    /** 启动时确保 {@code spaceId} 唯一索引存在。建索引失败只留告警，不阻断启动。 */
    void init(@Observes StartupEvent event) {
        if (!indexEnabled) {
            return;
        }
        repo.ensureIndexes().await().indefinitely();
    }

    /**
     * 读布局：没有文档时返回**预置布局**（不落库、不报 404）。
     * 预置布局的 {@code version} 是 0，而 0 恰好也是「首次保存要提交的版本号」。
     */
    public Uni<MobileDashboardLayoutEntity> load(SpaceEntity space) {
        String spaceId = space.rootSpaceId();
        return repo.findBySpaceId(spaceId)
                .map(stored -> stored != null ? stored : MobileDashboardPresetLayouts.build(spaceId));
    }

    /**
     * 保存（整体替换）。乐观锁的语义与 Web 一致：
     * <ul>
     *   <li>没有文档时，提交的 {@code version} 必须是 {@code 0}（预置布局的版本），否则报冲突；
     *       成功后版本变 1</li>
     *   <li>有文档时，提交的版本必须与库里一致，否则报冲突；成功后版本 +1</li>
     * </ul>
     */
    public Uni<MobileDashboardLayoutEntity> save(SpaceEntity space, MobileDashboardLayoutDto body, Person who) {
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

    /** 预置布局（只读）。「恢复默认」拿它当草稿起点 —— 由前端装进草稿、标脏，再点保存才落库。 */
    public MobileDashboardLayoutEntity preset(SpaceEntity space) {
        return MobileDashboardPresetLayouts.build(space.rootSpaceId());
    }

    private Uni<MobileDashboardLayoutEntity> create(String spaceId, MobileDashboardLayoutDto body, Person who) {
        if (body.version != 0L) {
            return conflict();
        }
        MobileDashboardLayoutEntity layout = new MobileDashboardLayoutEntity();
        layout.spaceId = spaceId;
        layout.version = 1L;
        layout.widgets = normalized(body.widgets);
        layout.creator = who;
        layout.updater = who;
        return repo.insert(layout)
                // 两个管理员同时创建第一份布局时，唯一索引会让后一个拿到 11000：
                // 这与「版本不一致」是同一件事（别人先写成功了），报冲突而不是 500
                .onFailure(MobileDashboardLayoutRepository::isDuplicateKey)
                .transform(failure -> new IllegalArgumentException(VERSION_CONFLICT))
                .replaceWith(layout);
    }

    private Uni<MobileDashboardLayoutEntity> update(MobileDashboardLayoutEntity current,
                                                    MobileDashboardLayoutDto body, Person who) {
        long expected = current.version;
        current.version = expected + 1L;
        current.widgets = normalized(body.widgets);
        // creator 不动：这份看板是谁建的，不该因为别人改了布局就变成别人建的
        current.updater = who;
        return repo.replaceIfVersion(current, expected).map(written -> {
            if (!written) {
                throw new IllegalArgumentException(VERSION_CONFLICT);
            }
            return current;
        });
    }

    /** 落库前的规范化：把 {@code null} 的清单当成空清单。顺序（阅读顺序）原样保留，不排序不补位。 */
    private static List<MobileDashboardWidget> normalized(List<MobileDashboardWidget> widgets) {
        return widgets == null ? List.of() : widgets;
    }

    private static final String VERSION_CONFLICT =
            "layout has been modified by someone else, please reload";

    private static Uni<MobileDashboardLayoutEntity> conflict() {
        return Uni.createFrom().failure(new IllegalArgumentException(VERSION_CONFLICT));
    }
}