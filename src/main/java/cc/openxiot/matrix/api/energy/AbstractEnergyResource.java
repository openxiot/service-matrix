package cc.openxiot.matrix.api.energy;

import cc.openxiot.common.resource.AbstractResource;
import cc.openxiot.matrix.api.space.SpaceService;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.matrix.service.orgcheck.OrgAccessService;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.ZoneId;
import java.util.Locale;

/**
 * 能源 API 的共用部分：按空间鉴权与查询参数解析。
 *
 * <p>四个资源类按模块分在 {@code config} / {@code report} / {@code status} / {@code summary}
 * 子包里，都继承本类，鉴权口径保持一致：读需空间成员、写需空间管理员。路径上的
 * {@code spaceId} 可以是项目下任意一级空间，服务端统一归一成**根空间** ——
 * 能源按项目统计，配置也只属于项目（点位照样可以挂在子空间的设备上）。</p>
 */
public abstract class AbstractEnergyResource extends AbstractResource {

    @Inject
    protected Logger logger;

    @Inject
    protected OrgAccessService orgAccessService;

    @Inject
    protected SpaceService spaceService;

    /** 空间成员（读）。空间不存在按「无权限」处理，不区分 404（与 DeviceResource 同口径）。 */
    protected Uni<SpaceEntity> requireSpaceMember(String spaceId) {
        return spaceService.getById(spaceId)
                .chain(space -> {
                    if (space == null) {
                        throw new IllegalArgumentException("No access to this space");
                    }
                    return orgAccessService.checkSpaceMember(space, getUserId())
                            .map(ignored -> space);
                });
    }

    /** 空间管理员（写）。 */
    protected Uni<SpaceEntity> requireSpaceAdmin(String spaceId) {
        return spaceService.getById(spaceId)
                .chain(space -> {
                    if (space == null) {
                        throw new IllegalArgumentException("No access to this space");
                    }
                    return orgAccessService.checkSpaceAdmin(space, getUserId())
                            .map(ignored -> space);
                });
    }

    protected static <E extends Enum<E>> Uni<E> required(Class<E> type, String value, String name) {
        if (value == null || value.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException(name + " is required"));
        }
        return Uni.createFrom().item(parse(type, value, name));
    }

    protected static <E extends Enum<E>> E optional(Class<E> type, String value) {
        return value == null || value.isBlank() ? null : parse(type, value, type.getSimpleName());
    }

    protected static <E extends Enum<E>> E parse(Class<E> type, String value, String name) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid " + name + ": " + value);
        }
    }

    protected static Uni<ZoneId> zone(String value) {
        if (value == null || value.isBlank()) {
            return Uni.createFrom().item(ZoneId.systemDefault());
        }
        try {
            return Uni.createFrom().item(ZoneId.of(value.trim()));
        } catch (Exception e) {
            return Uni.createFrom().failure(new IllegalArgumentException("invalid zone: " + value));
        }
    }
}
