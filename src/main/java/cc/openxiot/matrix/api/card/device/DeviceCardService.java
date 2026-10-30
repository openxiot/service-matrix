package cc.openxiot.matrix.api.card.device;

import cc.openxiot.common.person.Person;
import cc.openxiot.matrix.api.card.device.params.DeviceCardDto;
import cc.openxiot.matrix.api.device.DeviceService;
import cc.openxiot.matrix.api.space.SpaceService;
import cc.openxiot.matrix.db.card.device.DeviceCard;
import cc.openxiot.matrix.db.card.device.DeviceCardRepository;
import cc.openxiot.matrix.db.device.DeviceEntity;
import cc.openxiot.matrix.db.space.SpaceEntity;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 设备卡片配置的读写：查（没有文档 = 未配置）、存（整体替换）、恢复默认（删文档）。
 *
 * <p><b>鉴权不在这里</b>：读是空间成员、写是空间管理员，由资源层的 {@link cc.openxiot.matrix.api.dashboard.DashboardAccess}
 * 判。本类只负责「设备确实属于这个项目」「配置形状合法」「怎么落库」—— 与看板布局的
 * 权限 / 校验 / 持久化三层分工一致。</p>
 *
 * <p><b>归属校验按根空间</b>：路径上的 {@code spaceId} 可能是子空间，配置却按
 * {@code space.rootSpaceId()} 存。设备挂在子空间下也允许配置（页面就是从子空间点进来的），
 * 判定的是「设备所在空间的根 == 路径空间的根」，而不是 id 逐字相等。</p>
 *
 * <p><b>不做「服务/属性存在性」校验</b>：那要读产品实例定义，而定义会变 —— 校验通过之后
 * 定义一改，库里就成了「合法但不存在的 id」。展示层（前端影子）本来就要容忍「配置里有、影子没有」
 * 的服务并跳过，所以这里只保证结构合法（正整数、去重、数量有上限），不替展示层做决定。</p>
 */
@ApplicationScoped
public class DeviceCardService {

    /** 单台设备最多选多少个服务：兜底防滥用，不是展示上限。 */
    static final int MAX_SERVICES = 64;

    /** 单个服务最多选多少个属性：同上。 */
    static final int MAX_PIIDS_PER_SERVICE = 128;

    @Inject
    DeviceCardRepository repo;

    @Inject
    DeviceService deviceService;

    @Inject
    SpaceService spaceService;

    /** 建索引开关：测试进程关掉它，免得启动就连库（与 {@code dashboard.index.enabled} 同口径）。 */
    @ConfigProperty(name = "card.index.enabled", defaultValue = "true")
    boolean indexEnabled;

    /**
     * 启动时补 {@code spaceId} 索引、清掉上一版的复合唯一索引。建索引失败只留告警，不阻断启动 ——
     * 没有索引只是慢，起不来是另一回事。
     */
    void init(@Observes StartupEvent event) {
        if (!indexEnabled) {
            return;
        }
        repo.ensureIndexes().await().indefinitely();
    }

    /** 项目下所有已配置的设备卡片。未配置的设备不在列表里，由前端走缺省规则。 */
    public Uni<List<DeviceCard>> list(SpaceEntity space) {
        return repo.findBySpaceId(space.rootSpaceId());
    }

    /** 单台设备的配置；没有文档时返回 {@code null}（不是空配置）。 */
    public Uni<DeviceCard> load(SpaceEntity space, String did) {
        return requireDeviceInProject(space.rootSpaceId(), did)
                .chain(ignored -> repo.findByDid(did));
    }

    /**
     * 保存（整体替换）。不做乐观锁：配置是「一台设备一小份」，冲突只可能来自同一台设备的两次
     * 并发编辑，最后一次保存胜出即可 —— 与看板布局（整屏共享、改一次牵动一片）不同，
     * 不值得让每个编辑者都被版本冲突打断。
     */
    public Uni<DeviceCard> save(SpaceEntity space, String did, DeviceCardDto body, Person who) {
        String spaceId = space.rootSpaceId();
        return requireDeviceInProject(spaceId, did)
                .chain(ignored -> {
                    DeviceCard card = new DeviceCard();
                    card.spaceId = spaceId;
                    card.did = did;
                    card.services = normalized(body == null ? null : body.services);
                    return persist(card, who);
                });
    }

    /** 恢复默认：删掉配置文档。没有文档时也成功（幂等）。 */
    public Uni<Void> reset(SpaceEntity space, String did) {
        return requireDeviceInProject(space.rootSpaceId(), did)
                .chain(ignored -> repo.deleteByDid(did))
                .replaceWithVoid();
    }

    /**
     * 设备必须存在，且落在本项目的（任意子空间）下。
     *
     * <p>设备没有落点（{@code space} 为空）时也拒绝：没有落点就判不出属于哪个项目，
     * 「全局设备」的卡片配置不该出现在某个项目里。</p>
     */
    private Uni<Void> requireDeviceInProject(String rootSpaceId, String did) {
        return deviceService.getByDeviceId(did)
                .chain(device -> {
                    if (device == null || device.space == null || device.space.spaceId == null) {
                        throw new IllegalArgumentException("Device not found in this project: " + did);
                    }
                    return requireSpaceInProject(rootSpaceId, device, did);
                });
    }

    private Uni<Void> requireSpaceInProject(String rootSpaceId, DeviceEntity device, String did) {
        return spaceService.getById(device.space.spaceId)
                .map(deviceSpace -> {
                    if (deviceSpace == null || !rootSpaceId.equals(deviceSpace.rootSpaceId())) {
                        throw new IllegalArgumentException("Device not found in this project: " + did);
                    }
                    return (Void) null;
                });
    }

    /**
     * 落库：按主键 upsert，写进去的 {@code spaceId} 是本次按设备现算的根空间，**整份覆盖旧值**
     * —— 即使库里那份曾经脏过，一次保存就自愈。
     *
     * <p>先读一次只为保住 {@code creator}。两个管理员同时首次保存时，两次 upsert 仍可能撞出
     * 11000（Mongo upsert 的已知竞态）—— 那不是错误，重试一次即可。</p>
     */
    private Uni<DeviceCard> persist(DeviceCard card, Person who) {
        return persistOnce(card, who)
                .onFailure(DeviceCardRepository::isDuplicateKey)
                .recoverWithUni(() -> persistOnce(card, who));
    }

    private Uni<DeviceCard> persistOnce(DeviceCard card, Person who) {
        return repo.findByDid(card.did).chain(current -> {
            // creator 不动：这份配置是谁建的，不该因为别人改了选择就变成别人建的
            card.creator = current == null ? who : current.creator;
            card.updater = who;
            return repo.upsert(card).replaceWith(card);
        });
    }

    /**
     * 规范化：去重、保序、限数量。
     *
     * <p>同一个服务出现多次就合并 {@code piids}（按首次出现的位置）；属性重复只留第一次。
     * 去重而不是报错：前端复选框本来不会重复，重复只可能来自并发保存的合并或手写请求，
     * 悄悄收敛比让用户面对一条他无从修起的错误更有用。非法 id（空 / 非正）仍然报错 ——
     * 那说明调用方拼错了，静默丢掉只会让「我选了它却没显示」更难查。</p>
     */
    static List<DeviceCard.ServiceSelection> normalized(List<DeviceCardDto.ServiceSelection> input) {
        Map<Integer, Set<Integer>> bySiid = new LinkedHashMap<>();
        if (input != null) {
            for (DeviceCardDto.ServiceSelection selection : input) {
                if (selection == null) {
                    continue;
                }
                if (selection.siid == null || selection.siid < 1) {
                    throw new IllegalArgumentException("siid must be a positive integer");
                }
                Set<Integer> piids = bySiid.computeIfAbsent(selection.siid, key -> new LinkedHashSet<>());
                if (selection.piids == null) {
                    continue;
                }
                for (Integer piid : selection.piids) {
                    if (piid == null || piid < 1) {
                        throw new IllegalArgumentException("piid must be a positive integer");
                    }
                    piids.add(piid);
                }
            }
        }
        if (bySiid.size() > MAX_SERVICES) {
            throw new IllegalArgumentException("Too many services on one card (max " + MAX_SERVICES + ")");
        }
        List<DeviceCard.ServiceSelection> services = new ArrayList<>();
        bySiid.forEach((siid, piids) -> {
            if (piids.size() > MAX_PIIDS_PER_SERVICE) {
                throw new IllegalArgumentException(
                        "Too many properties on one service (max " + MAX_PIIDS_PER_SERVICE + ")");
            }
            DeviceCard.ServiceSelection service = new DeviceCard.ServiceSelection();
            service.siid = siid;
            service.piids = new ArrayList<>(piids);
            services.add(service);
        });
        return services;
    }
}
