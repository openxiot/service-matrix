package cc.openxiot.matrix.api.dashboard.web.catalog;

import cc.openxiot.matrix.api.dashboard.widget.DashboardWidgetOwnershipValidator;
import cc.openxiot.matrix.api.space.SpaceService;
import cc.openxiot.matrix.db.device.DeviceRepository;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceRepository;
import cc.openxiot.matrix.db.space.SpaceEntity;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * 看板编辑器要的候选清单：本项目的设备与服务。
 *
 * <h3>为什么要有这个接口（而不是让编辑器复用别的清单）</h3>
 * <p>编辑器的三级级联（服务 → 方法 → 字段）要的是**服务定义本身**
 * （{@code functions[].response[]} 里的 {@code format} / {@code unit} / {@code valueList} /
 * {@code bitList}），设备卡要的是设备型号。这些都在实体上，而前端拿不到实体 ——
 * 没有这个接口，编辑器只能让用户手抄 serviceId，或者把一个空的卡片存下去。</p>
 *
 * <h3>口径必须与归属校验、取数**逐字一致**</h3>
 * <p>三处都按「根空间的**子树**」：{@code spaceService.getDescendants(rootId)} 得到空间 id 清单，
 * 再按 {@code space.spaceId} / {@code device.space.spaceId} 取。
 * 不一致的表现是最难查的那种 ——「编辑器里选得到，保存报『不属于本空间』」，
 * 或者更糟「存得下、渲染永远是空的」。所以这里刻意复用
 * {@link DashboardWidgetOwnershipValidator} 判归属时用的同一个字段路径，不另算一套。</p>
 *
 * <p>可见性粒度就是「空间成员看到本项目子树的全部设备/服务」：{@code DeviceResource}
 * 那边也只做一次 {@code requireSpaceMember}，没有更细的口径可用。</p>
 */
@ApplicationScoped
public class WebDashboardCatalogService {

    @Inject
    SpaceService spaceService;

    @Inject
    DeviceRepository deviceRepo;

    @Inject
    ModbusServiceRepository serviceRepo;

    /** 本项目的候选设备与服务，一次子树查询两路并发。 */
    public Uni<JsonObject> catalog(SpaceEntity space) {
        String rootId = space == null ? null : space.rootSpaceId();
        if (rootId == null) {
            return Uni.createFrom().item(WebDashboardCatalogCodec.encode(List.of(), List.of()));
        }
        return spaceService.getDescendants(rootId)
                .chain(spaces -> {
                    List<String> spaceIds = spaces.stream()
                            .filter(s -> s != null && s.id != null)
                            .map(s -> s.id.toString())
                            .toList();
                    // 空子树也要给一份成形（空数组）的响应：编辑器据此显示「没有可选项」，
                    // 而不是因为拿不到 devices/services 键就把级联画成坏掉的样子
                    if (spaceIds.isEmpty()) {
                        return Uni.createFrom()
                                .item(WebDashboardCatalogCodec.encode(List.of(), List.of()));
                    }
                    return Uni.combine().all()
                            .unis(deviceRepo.findBySpaceIds(spaceIds),
                                    serviceRepo.listBySpaceIds(spaceIds))
                            .asTuple()
                            .map(tuple -> WebDashboardCatalogCodec.encode(tuple.getItem1(), tuple.getItem2()));
                });
    }
}
