package cc.openxiot.matrix.api.dashboard.web.widget;

import cc.openxiot.matrix.api.space.SpaceService;
import cc.openxiot.matrix.api.statistics.StatisticsQueryService;
import cc.openxiot.matrix.db.dashboard.web.widget.DashboardWidget;
import cc.openxiot.matrix.db.device.DeviceEntity;
import cc.openxiot.matrix.db.device.DeviceRepository;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceField;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFieldBit;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFunction;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceRepository;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.matrix.db.space.SpaceOn;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 卡片配置里**需要查库的那部分**校验：引用的设备/服务是否属于这个空间，
 * {@code (serviceId, functionIndex, field)} 三元组是否真实存在。
 *
 * <p>与纯函数的 {@link WidgetStructureValidator} 分成两个类，是因为两者的失败语义不同：
 * 结构错误是「这份 body 根本不成形」，一次请求只能报一个；归属错误是「某一张卡指向了不存在
 * 或不属于本空间的东西」，**渲染时必须逐张隔离**（一张卡引用了一个已被删除的服务，
 * 只该黑掉那一张，不该让整屏看板都渲染不出来）。所以本类的主入口返回的是
 * {@code widgetId → 错误文案}，整份失败只是保存路径对它的一个包装
 * （那个包装在 {@code DashboardLayoutValidator}）。</p>
 *
 * <h3>为什么要在这里校验归属（而不是只靠 catalog 下拉）</h3>
 * <p>{@code serviceId} / {@code did} 是用户填的。同一个空间里，成员能看见的数据本来就属于他，
 * 所以「成员 A 用本空间的服务配卡片」不是越权；但**没有这一层，任何成员都能把别人的
 * serviceId 填进 body 里**：{@code render} 只按 id 查（既有的
 * {@code Modbus-History.md} 就是这么写的），于是拿到一个陌生 id 就能读走别人的数据。
 * {@code catalog} 只下发本空间候选，那是 UX 层的方便，拦不住手写的请求 ——
 * 两道一起才安全，这道是真正把关的那道。</p>
 *
 * <h3>归属的判定基准是「根空间的子树」，不是路径上的 spaceId</h3>
 * <p>布局按空间（项目）存，所以判定要从 {@code space.rootSpaceId()} 出发 —— 但**不能直接
 * 比某个 rootId 字段**。判据是：设备/服务的落点空间是否落在**根空间的子树里**
 * （{@link SpaceService#getDescendants}），与 {@link StatisticsQueryService#overview}
 * 取数时的范围逐字同源。子空间里的设备算本项目可用，所以子树而不是「恰好等于根」。</p>
 *
 * <p>为什么不用实体上现成的根 id 字段（本次修掉的一个 P1 缺陷）：设备文档上有**两个**不同的
 * 「根 id」，不是一回事 ——</p>
 * <ul>
 *   <li>{@code DeviceEntity.rootId} 是**设备组的根**（{@code DeviceEntityMapper} 从设备摘要
 *       写入，与 {@code parentId} / {@code members} 同属设备拓扑），设备组是空间内部的层级，
 *       与「哪个项目」无关；</li>
 *   <li>空间根是另一个字段：{@code device.space.spaceId} / {@code device.space.rootId}
 *       （{@code DeviceService.updateSpace} 写的落点副本），而 {@code space.rootId} 是
 *       **客户端写下的快照**，{@code DeviceService.checkSameRoot} 自己都不信它（其注释：
 *       「可能为空……或过期」），宁可现算。</li>
 * </ul>
 * <p>拿空间根去比 {@code device.rootId}，两个命名空间对不上，结果是**每一张设备卡都被判成
 * 「不属于本空间」**。旧写法只判服务是对的（{@code device.space.rootId} 确实是空间根），
 * 但它吃的是快照：设备搬迁后判定就过期了。两者现在统一成子树成员判定 —— 基准是活值，
 * 且与取数同源，不会再出现「卡片存得下、渲染却是空的」。</p>
 *
 * <h3>保存时校验存在性是有代价的，这个代价是明知故犯</h3>
 * <p>卡片引用的服务被删除后，这份布局就再也存不进去了，直到管理员把那张卡删掉。
 * 换句话说：一次删除会让旧布局「变脏」。之所以仍然这么做，是因为替代方案更糟 ——
 * 不在保存时报错，用户就只能在渲染时看到一张空卡，且没有任何提示告诉他
 * 「你选的字段早就没了」（§6.4 的原话：字段名写错的卡片要在保存时就报错，
 * 而不是渲染成空图）。报错文案带上 {@code widgets[i]} 下标与 widgetId，
 * 编辑器据此定位到那张卡，用户要做的只是删掉它。</p>
 */
@ApplicationScoped
public class WidgetOwnershipValidator {

    @Inject
    ModbusServiceRepository serviceRepo;

    @Inject
    DeviceRepository deviceRepo;

    @Inject
    SpaceService spaceService;

    /**
     * 渲染路径：逐卡片校验，返回 {@code widgetId → 错误文案}（无错则空 Map）。
     * **不整份失败**：调用方拿这张表把出错的卡单独标成 {@code success: false}。
     *
     * <p>读的都是提交上来的 body（含编辑器里尚未保存的草稿），所以对形状只做防御性读取、
     * 不重复结构校验 —— 该报的错由调用方先跑一遍
     * {@link WidgetStructureValidator} 负责。这里是「假定形状合法，只判归属」。</p>
     */
    public Uni<Map<String, String>> errorsByWidget(SpaceEntity space, List<DashboardWidget> widgets) {
        Map<String, String> errors = new LinkedHashMap<>();
        // 归属判定的基准：根空间（项目）。与布局的持久化、取数共用同一个
        // SpaceEntity.rootSpaceId()，三处必须一致
        String rootId = space == null ? null : space.rootSpaceId();
        if (rootId == null || widgets == null || widgets.isEmpty()) {
            return Uni.createFrom().item(errors);
        }

        List<ServiceRef> serviceRefs = new ArrayList<>();
        List<String> serviceIds = new ArrayList<>();
        Map<String, DeviceRef> deviceRefs = new LinkedHashMap<>();
        List<String> dids = new ArrayList<>();
        collect(widgets, serviceRefs, serviceIds, deviceRefs, dids);
        if (serviceIds.isEmpty() && dids.isEmpty()) {
            return Uni.createFrom().item(errors);
        }

        return spaceService.getDescendants(rootId)
                .chain(spaces -> {
                    Set<String> spaceIds = spaceIdsOf(spaces);
                    return Uni.combine().all()
                            .unis(loadServices(serviceIds), loadDevices(dids))
                            .asTuple()
                            .map(tuple -> {
                                Map<String, ModbusService> services = tuple.getItem1();
                                Map<String, DeviceEntity> devices = tuple.getItem2();
                                for (ServiceRef ref : serviceRefs) {
                                    String err = checkServiceRef(spaceIds, services, ref);
                                    // 一张卡若引用了多个字段，只报第一个错的：编辑器一行只放得下一个提示
                                    if (err != null) {
                                        errors.putIfAbsent(ref.widgetId(), prefix(ref) + err);
                                    }
                                }
                                for (DeviceRef ref : deviceRefs.values()) {
                                    DeviceEntity device = devices.get(ref.did());
                                    if (device == null) {
                                        errors.put(ref.widgetId(),
                                                prefix(ref) + "device not found: " + ref.did());
                                    } else if (!inSubtree(spaceIds, device.space)) {
                                        errors.put(ref.widgetId(), prefix(ref)
                                                + "device does not belong to this space: " + ref.did());
                                    }
                                }
                                return errors;
                            });
                });
    }

    /** 子树里的空间 id 集合（含根）。与 {@code StatisticsQueryService} 同一取法：{@code id.toString()}。 */
    private static Set<String> spaceIdsOf(List<SpaceEntity> spaces) {
        Set<String> ids = new LinkedHashSet<>();
        for (SpaceEntity space : spaces) {
            if (space != null && space.id != null) {
                ids.add(space.id.toString());
            }
        }
        return ids;
    }

    /**
     * 归属判据：落点空间在不在子树的 id 集合里。
     *
     * <p>设备文档与服务文档上的落点是**同一个 {@link SpaceOn} 类型**（{@code DeviceEntity.space}
     * 与 {@code DeviceOn.space}），字段路径也一样，所以两条判据能共用这一个函数 ——
     * 这也正是「两边口径必须一致」在代码上的落点。</p>
     *
     * <p>{@code space} 或 {@code spaceId} 为 null 时**直接算不属于**，不能交给
     * {@code Set.contains(null)} —— 那对 {@code Set.of} 一类的不可变集合会抛 NPE，
     * 而「设备还没有落点」在这里是正常输入（{@code DeviceService.updateSpace} 之前的设备
     * 就是这样），不是异常。</p>
     */
    private static boolean inSubtree(Set<String> spaceIds, SpaceOn space) {
        return space != null && space.spaceId != null && spaceIds.contains(space.spaceId);
    }

    /**
     * 收集需要查库的引用。同一个 serviceId / did 只查一次（去重交给 {@code loadXxx}
     * 的入参 List 由调用方保证，这里用 seen 集合保证）。
     */
    private static void collect(List<DashboardWidget> widgets,
                               List<ServiceRef> serviceRefs, List<String> serviceIds,
                               Map<String, DeviceRef> deviceRefs, List<String> dids) {
        for (int i = 0; i < widgets.size(); i++) {
            DashboardWidget widget = widgets.get(i);
            if (widget == null || widget.id == null || widget.config == null) {
                continue;
            }
            Map<String, Object> config = widget.config;
            switch (widget.type == null ? "" : widget.type) {
                case "service" -> {
                    String serviceId = WidgetConfigs.str(config.get("serviceId"));
                    Integer functionIndex = WidgetConfigs.intVal(config.get("functionIndex"));
                    if (serviceId == null || functionIndex == null) {
                        continue;
                    }
                    addServiceId(serviceIds, serviceId);
                    for (String field : WidgetConfigs.strList(config.get("fields"))) {
                        serviceRefs.add(new ServiceRef(i, widget.id, serviceId, functionIndex, field));
                    }
                }
                case "line" -> {
                    if (!"serviceField".equals(WidgetConfigs.str(config.get("source")))) {
                        continue;
                    }
                    String serviceId = WidgetConfigs.str(config.get("serviceId"));
                    Integer functionIndex = WidgetConfigs.intVal(config.get("functionIndex"));
                    String field = WidgetConfigs.str(config.get("field"));
                    if (serviceId == null || functionIndex == null || field == null) {
                        continue;
                    }
                    addServiceId(serviceIds, serviceId);
                    serviceRefs.add(new ServiceRef(i, widget.id, serviceId, functionIndex, field));
                }
                case "device" -> {
                    String did = WidgetConfigs.str(config.get("did"));
                    if (did == null || deviceRefs.containsKey(widget.id)) {
                        continue;
                    }
                    deviceRefs.put(widget.id, new DeviceRef(i, widget.id, did));
                    if (!dids.contains(did)) {
                        dids.add(did);
                    }
                }
                default -> {
                    // stat / distribution：只读本空间的聚合统计，没有用户填的 id，无需归属校验
                }
            }
        }
    }

    private static void addServiceId(List<String> serviceIds, String serviceId) {
        if (!serviceIds.contains(serviceId)) {
            serviceIds.add(serviceId);
        }
    }

    /**
     * 按 id 批量查服务。**key 用的是请求里的原字符串**，不是 {@code service.id.toHexString()}：
     * ObjectId 的十六进制大小写不敏感，客户端提交大写 hex 时两种写法不相等，
     * 按实体 id 建索引会让一个完全合法的引用被判成「不存在」。
     */
    private Uni<Map<String, ModbusService>> loadServices(List<String> ids) {
        Map<String, ModbusService> byId = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return Uni.createFrom().item(byId);
        }
        return Uni.join()
                .all(ids.stream().map(serviceRepo::findById).toList())
                .andFailFast()
                .map(services -> {
                    for (int i = 0; i < ids.size(); i++) {
                        ModbusService service = services.get(i);
                        if (service != null) {
                            byId.put(ids.get(i), service);
                        }
                    }
                    return byId;
                });
    }

    /** 同上：key 是请求里的 did 原文（设备 {@code _id} 就是 did，不存在大小写问题，但保持同形）。 */
    private Uni<Map<String, DeviceEntity>> loadDevices(List<String> dids) {
        Map<String, DeviceEntity> byDid = new LinkedHashMap<>();
        if (dids.isEmpty()) {
            return Uni.createFrom().item(byDid);
        }
        return deviceRepo.findByDeviceIds(dids).map(devices -> {
            for (DeviceEntity device : devices) {
                if (device != null && device.did != null) {
                    byDid.put(device.did, device);
                }
            }
            return byDid;
        });
    }

    private static String checkServiceRef(Set<String> spaceIds, Map<String, ModbusService> services,
                                          ServiceRef ref) {
        ModbusService service = services.get(ref.serviceId());
        if (service == null) {
            return "service not found: " + ref.serviceId();
        }
        // 服务的落点与设备同一个字段路径（创建服务时一并写下的副本），所以两条判据同源
        if (!inSubtree(spaceIds, service.device == null ? null : service.device.space)) {
            return "service does not belong to this space: " + ref.serviceId();
        }
        ModbusServiceFunction function = findFunction(service, ref.functionIndex());
        if (function == null) {
            return "function not found: " + ref.functionIndex();
        }
        if (!hasField(function, ref.field())) {
            return "field not found: " + ref.field();
        }
        return null;
    }

    private static ModbusServiceFunction findFunction(ModbusService service, Integer index) {
        if (service.functions == null) {
            return null;
        }
        for (ModbusServiceFunction function : service.functions) {
            if (function != null && index.equals(function.index)) {
                return function;
            }
        }
        return null;
    }

    /**
     * 字段名要认两类：功能码 {@code response} 里直接定义的字段，以及**位区里每个位自己的名字**
     * （{@code bit-list[].field}）。
     *
     * <p>位是独立点位（见方案 §5.7 与 {@code ModbusService.ts} 的说明）：一次 01/02 调用返回的是
     * 整个位掩码，但每个具名位各自成为一路 0/1 的值、各自有自己的历史。所以「用户在下拉里
     * 选了一个位」是完全正常的配置，漏掉这一条会让这类卡片永远存不进去。</p>
     */
    private static boolean hasField(ModbusServiceFunction function, String field) {
        if (function.response == null) {
            return false;
        }
        for (ModbusServiceField candidate : function.response) {
            if (candidate == null) {
                continue;
            }
            if (field.equals(candidate.field)) {
                return true;
            }
            if (candidate.bitList != null) {
                for (ModbusServiceFieldBit bit : candidate.bitList) {
                    if (bit != null && field.equals(bit.field)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 报错前缀。带下标是为了让「保存失败」的提示能定位到卡片，带 id 是因为编辑器的
     * 卡片视图按 id 索引（下标会随拖拽变化，id 不会）。
     */
    private static String prefix(ServiceRef ref) {
        return prefix(ref.index(), ref.widgetId());
    }

    private static String prefix(DeviceRef ref) {
        return prefix(ref.index(), ref.widgetId());
    }

    private static String prefix(int index, String widgetId) {
        return "widgets[" + index + "](" + widgetId + "): ";
    }

    /** 一次 (serviceId, functionIndex, field) 引用；服务卡片的每个字段各一条。 */
    private record ServiceRef(int index, String widgetId, String serviceId, Integer functionIndex, String field) {
    }

    /** 一张设备卡片的引用。 */
    private record DeviceRef(int index, String widgetId, String did) {
    }
}
