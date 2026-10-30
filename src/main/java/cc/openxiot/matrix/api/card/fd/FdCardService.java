package cc.openxiot.matrix.api.card.fd;

import cc.openxiot.common.person.Person;
import cc.openxiot.matrix.api.card.fd.params.FdCardDto;
import cc.openxiot.matrix.api.modbus.fd.FieldDeviceService;
import cc.openxiot.matrix.api.space.SpaceService;
import cc.openxiot.matrix.db.card.fd.FdCard;
import cc.openxiot.matrix.db.card.fd.FdCardRepository;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.db.modbus.fd.FieldDevice;
import cc.openxiot.matrix.db.space.SpaceEntity;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 现场设备卡片配置的读写：查（没有文档 = 未配置）、存（整体替换）、恢复默认（删文档）。
 *
 * <p>鉴权在资源层（成员读、管理员写，见 {@link cc.openxiot.matrix.api.dashboard.DashboardAccess}）；
 * 本类负责「现场设备确实属于这个项目」「选的寄存器确实可读」「怎么落库」。</p>
 *
 * <p><b>选的寄存器必须存在且可读</b>（{@code access} 为 R 或 RW）：与设备卡片不同，寄存器点表
 * **就在现场设备的 {@code configSnapshot} 里**，校验它不需要回查产品定义，也不会因为定义改版而
 * 失效 —— 快照是创建服务时冻结的。所以这里可以、也应该硬校验：一个不存在的序号若被存下来，
 * 前端只能静默跳过，用户看到的是「勾了却不显示」，比直接报错更难查。</p>
 *
 * <p><b>归属校验按根空间</b>：现场设备落在哪由 {@code device.space.spaceId} 记录，
 * 判定「所在空间的根 == 路径空间的根」，与设备卡片、看板归属同一条口径。</p>
 */
@ApplicationScoped
public class FdCardService {

    /** 单台现场设备最多选多少个寄存器：兜底防滥用，不是展示上限。 */
    static final int MAX_REGISTERS = 256;

    @Inject
    FdCardRepository repo;

    @Inject
    FieldDeviceService fieldDeviceService;

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

    /** 项目下所有已配置的现场设备卡片。未配置的不在列表里，由前端走缺省规则。 */
    public Uni<List<FdCard>> list(SpaceEntity space) {
        return repo.findBySpaceId(space.rootSpaceId());
    }

    /** 单台现场设备的配置；没有文档时返回 {@code null}（不是空配置）。 */
    public Uni<FdCard> load(SpaceEntity space, String fdid) {
        return requireFdInProject(space.rootSpaceId(), fdid)
                .chain(fd -> repo.findByFdid(fdid));
    }

    /**
     * 保存（整体替换）。不做乐观锁：同一台现场设备的两次并发编辑，最后一次保存胜出即可
     * —— 与设备卡片、看板布局的取舍一致（小份配置不值得让每个编辑者都被版本冲突打断）。
     */
    public Uni<FdCard> save(SpaceEntity space, String fdid, FdCardDto body, Person who) {
        String spaceId = space.rootSpaceId();
        return requireFdInProject(spaceId, fdid)
                .chain(fd -> {
                    FdCard card = new FdCard();
                    card.spaceId = spaceId;
                    card.fdid = fdid;
                    card.registers = normalized(fd, body == null ? null : body.registers);
                    return persist(card, who);
                });
    }

    /** 恢复默认：删掉配置文档。没有文档时也成功（幂等）。 */
    public Uni<Void> reset(SpaceEntity space, String fdid) {
        return requireFdInProject(space.rootSpaceId(), fdid)
                .chain(fd -> repo.deleteByFdid(fdid))
                .replaceWithVoid();
    }

    /**
     * 现场设备必须存在，且落在本项目的（任意子空间）下。
     *
     * <p>没有落点（{@code device.space} 为空）时也拒绝：判不出属于哪个项目，
     * 「全局现场设备」的卡片配置不该出现在某个项目里。</p>
     */
    private Uni<FieldDevice> requireFdInProject(String rootSpaceId, String fdid) {
        return fieldDeviceService.get(fdid)
                .chain(fd -> {
                    if (fd.device == null || fd.device.space == null || fd.device.space.spaceId == null) {
                        throw new IllegalArgumentException("Field device not found in this project: " + fdid);
                    }
                    // 校验完把现场设备继续往下传：保存时要用它的点表校验寄存器序号
                    return requireSpaceInProject(rootSpaceId, fd, fdid).replaceWith(fd);
                });
    }

    private Uni<Void> requireSpaceInProject(String rootSpaceId, FieldDevice fd, String fdid) {
        return spaceService.getById(fd.device.space.spaceId)
                .map(fdSpace -> {
                    if (fdSpace == null || !rootSpaceId.equals(fdSpace.rootSpaceId())) {
                        throw new IllegalArgumentException("Field device not found in this project: " + fdid);
                    }
                    return (Void) null;
                });
    }

    /**
     * 落库：按主键 upsert，写进去的 {@code spaceId} 是本次按现场设备落点现算的根空间，**整份覆盖
     * 旧值** —— 即使库里那份曾经脏过，一次保存就自愈。
     *
     * <p>先读一次只为保住 {@code creator}。两个管理员同时首次保存时，两次 upsert 仍可能撞出
     * 11000（Mongo upsert 的已知竞态）—— 那不是错误，重试一次即可。</p>
     */
    private Uni<FdCard> persist(FdCard card, Person who) {
        return persistOnce(card, who)
                .onFailure(FdCardRepository::isDuplicateKey)
                .recoverWithUni(() -> persistOnce(card, who));
    }

    private Uni<FdCard> persistOnce(FdCard card, Person who) {
        return repo.findByFdid(card.fdid).chain(current -> {
            // creator 不动：这份配置是谁建的，不该因为别人改了选择就变成别人建的
            card.creator = current == null ? who : current.creator;
            card.updater = who;
            return repo.upsert(card).replaceWith(card);
        });
    }

    /**
     * 规范化：每个序号必须是本现场设备点表里**可读**的寄存器，去重并保序。
     *
     * <p>去重而不是报错：重复只可能来自并发保存的合并或手写请求，收敛比报错更有用。
     * 不存在的序号 / 只写寄存器仍然报错 —— 那说明调用方选了卡片上永远不会出现的东西。</p>
     */
    static List<Integer> normalized(FieldDevice fd, List<Integer> input) {
        Set<Integer> readable = new LinkedHashSet<>();
        if (fd.configSnapshot != null && fd.configSnapshot.registries != null) {
            for (ModbusRegistry registry : fd.configSnapshot.registries) {
                if (registry != null && registry.index != null && readable(registry)) {
                    readable.add(registry.index);
                }
            }
        }
        Set<Integer> registers = new LinkedHashSet<>();
        if (input != null) {
            for (Integer index : input) {
                if (index == null || index < 1) {
                    throw new IllegalArgumentException("register index must be a positive integer");
                }
                if (!readable.contains(index)) {
                    throw new IllegalArgumentException(
                            "register " + index + " is not a readable register of this field device");
                }
                registers.add(index);
            }
        }
        if (registers.size() > MAX_REGISTERS) {
            throw new IllegalArgumentException("Too many registers on one card (max " + MAX_REGISTERS + ")");
        }
        return List.copyOf(registers);
    }

    private static boolean readable(ModbusRegistry registry) {
        return "R".equals(registry.access) || "RW".equals(registry.access);
    }
}
