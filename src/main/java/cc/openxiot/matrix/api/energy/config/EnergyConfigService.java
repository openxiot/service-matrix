package cc.openxiot.matrix.api.energy.config;

import cc.openxiot.common.person.Person;
import cc.openxiot.matrix.db.energy.EnergyConfig;
import cc.openxiot.matrix.db.energy.EnergyConfigRepository;
import cc.openxiot.matrix.db.energy.EnergyPointStateRepository;
import cc.openxiot.matrix.db.energy.EnergyRole;
import cc.openxiot.matrix.db.energy.EnergyStatRepository;
import cc.openxiot.matrix.db.energy.EnergyType;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Date;
import java.util.List;

/**
 * 能源配置的读写。鉴权（成员/管理员）在资源层做完再进来；这一层只管「配置属于哪个项目」与审计字段。
 *
 * <p>{@code spaceId} 一律是**项目根空间**：页面在一个项目里看能源，点位可以挂在子空间的设备上，
 * 但配置本身只属于根。资源层把路径上的空间归一成根再调这里。</p>
 *
 * <p><b>编辑配置会清掉累计基线</b>：点位来源/指标/口径改了，旧基线继续用会算出一次错误的差值
 * （例如把「累计电量」换成「增量电量」，下一次读数就会被当成一整个月的用量）。清掉基线的代价只是
 * 编辑到下一次采样之间那一小段增量缺失，比算出错数好。</p>
 */
@ApplicationScoped
public class EnergyConfigService {

    @Inject
    EnergyConfigRepository configRepo;

    @Inject
    EnergyStatRepository statRepo;

    @Inject
    EnergyPointStateRepository stateRepo;

    /** 项目下的配置；role / type 可空（页面按标签页时两个都给）。 */
    public Uni<List<EnergyConfig>> list(String spaceId, EnergyRole role, EnergyType type) {
        if (role == null || type == null) {
            return configRepo.listBySpace(spaceId);
        }
        return configRepo.listBySpace(spaceId, role, type);
    }

    public Uni<EnergyConfig> create(String spaceId, Person who, EnergyConfig config) {
        config.spaceId = spaceId;
        String error = EnergyConfigValidator.validate(config);
        if (error != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(error));
        }
        Date now = new Date();
        config.creator = who;
        config.updater = who;
        config.create = now;
        config.update = now;
        return configRepo.insert(config).replaceWith(config);
    }

    public Uni<EnergyConfig> update(String spaceId, String id, Person who, EnergyConfig patch) {
        return loadOwned(spaceId, id)
                .onItem().transformToUni(existing -> {
                    patch.id = existing.id;
                    patch.spaceId = spaceId;
                    String error = EnergyConfigValidator.validate(patch);
                    if (error != null) {
                        return Uni.createFrom().failure(new IllegalArgumentException(error));
                    }
                    patch.creator = existing.creator;
                    patch.create = existing.create;
                    patch.updater = who;
                    patch.update = new Date();
                    return stateRepo.deleteByConfig(id)
                            .chain(ignored -> configRepo.replace(patch))
                            .onItem().transformToUni(replaced -> {
                                if (!Boolean.TRUE.equals(replaced)) {
                                    return Uni.createFrom().failure(
                                            new IllegalArgumentException("energy config not found: " + id));
                                }
                                return Uni.createFrom().item(patch);
                            });
                });
    }

    public Uni<Long> delete(String spaceId, String id) {
        return loadOwned(spaceId, id)
                .onItem().transformToUni(existing -> Uni.combine().all().unis(
                                configRepo.delete(id),
                                statRepo.deleteByConfig(id),
                                stateRepo.deleteByConfig(id))
                        .asTuple()
                        .map(tuple -> tuple.getItem1()));
    }

    /** 取配置并校验它属于这个项目；不存在与不属于都按 not found（不泄漏别的项目有没有这个 id）。 */
    private Uni<EnergyConfig> loadOwned(String spaceId, String id) {
        if (id == null || id.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("id is required"));
        }
        return configRepo.findById(id)
                .onItem().transformToUni(config -> {
                    if (config == null || !spaceId.equals(config.spaceId)) {
                        return Uni.createFrom().failure(
                                new IllegalArgumentException("energy config not found: " + id));
                    }
                    return Uni.createFrom().item(config);
                });
    }
}
