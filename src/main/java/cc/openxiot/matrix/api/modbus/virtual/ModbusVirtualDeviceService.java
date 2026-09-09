package cc.openxiot.matrix.api.modbus.virtual;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import cc.openxiot.common.person.Person;
import cc.openxiot.matrix.api.device.DeviceService;
import cc.openxiot.matrix.api.modbus.config.ModbusConfigService;
import cc.openxiot.matrix.db.device.DeviceEntity;
import cc.openxiot.matrix.db.device.DeviceSpace;
import cc.openxiot.matrix.db.modbus.config.ModbusCommand;
import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.virtual.ModbusVirtualDevice;
import cc.openxiot.matrix.db.modbus.virtual.ModbusMapping;
import cc.openxiot.matrix.db.modbus.virtual.ModbusVirtualDeviceRepository;
import cn.geekcity.xiot.spec.codec.vertx.instance.DeviceInstanceCodec;
import cn.geekcity.xiot.spec.constant.Spec;
import cn.geekcity.xiot.spec.definition.property.Access;
import cn.geekcity.xiot.spec.definition.property.data.DataFormat;
import cn.geekcity.xiot.spec.definition.urn.ActionType;
import cn.geekcity.xiot.spec.definition.urn.DeviceType;
import cn.geekcity.xiot.spec.definition.urn.PropertyType;
import cn.geekcity.xiot.spec.definition.urn.ServiceType;
import cn.geekcity.xiot.spec.instance.Action;
import cn.geekcity.xiot.spec.instance.Argument;
import cn.geekcity.xiot.spec.instance.DeviceInstance;
import cn.geekcity.xiot.spec.instance.Property;
import cn.geekcity.xiot.spec.instance.Service;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Modbus 虚拟设备生成：把一条 Modbus 点表配置虚拟成一个挂在父设备下的子设备，并落库为矩阵设备。
 *
 * <p>流程 = 生成并持久化虚拟设备文档（定义注册表，见 {@link ModbusVirtualDevice}），再以同一 did / 派生类型
 * 创建矩阵 {@link DeviceEntity}（挂在父设备 {@code mapping.did} 下、复制其所在 space，parentId=父 did，
 * rootId=父所在树的根），使虚拟子设备出现在父设备所在空间的设备列表。createOne 返回该 DeviceEntity。</p>
 *
 * <p>虚拟设备 DeviceType 继承点表配置的 {@code type}（品类 DeviceType），只追加 vendor（父 did）、
 * model（点表配置 id）、version=1，即 8 段 XIOT 实例类型。</p>
 *
 * <p>实例定义用 xiot-spec 模型代码构造 {@link DeviceInstance}（单个 modbus Service），再经
 * {@link DeviceInstanceCodec} 编码为 JSON 文本落库，不手拼 JSON。读命令（fc 01/02/03/04）产出一个
 * 只读 backing property + 带 {@code out} 参数的 action；写命令（fc 05/06/0F/10）仅产出无参 action。
 * 定义留档在 {@code virtual-devices}，供后续真正的 Modbus 接入（实例注册 / I/O 桥接）使用。</p>
 *
 * <p>挂载描述（源配置 + 父设备挂载点）即 {@link ModbusMapping}：同对象作请求体 / 实体 {@code mapping}
 * 子文档复用，不在实体中平铺重复字段。</p>
 */
@ApplicationScoped
public class ModbusVirtualDeviceService {

    /** 读功能码。 */
    private static final List<String> READ_FCS = List.of("01", "02", "03", "04");

    @Inject
    Logger logger;

    @Inject
    ModbusConfigService configService;

    @Inject
    ModbusVirtualDeviceRepository repo;

    @Inject
    DeviceService deviceService;

    /**
     * 校验 → 取点表配置 → 构造 DeviceInstance 并编码落库（定义注册表）→ 创建矩阵 DeviceEntity 子设备 → 返回该设备。
     */
    public Uni<DeviceEntity> create(String orgId, ModbusMapping mapping, Person who) {
        String err = validate(mapping);
        if (err != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(err));
        }
        return configService.get(orgId, mapping.configId)
                .onItem().transformToUni(cfg -> {
                    ModbusVirtualDevice v;
                    try {
                        v = build(cfg, mapping, orgId, who);
                    } catch (IllegalArgumentException e) {
                        return Uni.createFrom().failure(e);
                    }
                    // 虚拟子设备要挂在真实存在的父设备下：取父设备（matrix DeviceEntity）以复制其所在 space / 树根
                    return deviceService.getByDeviceId(mapping.did)
                            .onItem().transformToUni(parent -> {
                                if (parent == null) {
                                    return Uni.createFrom().failure(
                                            new IllegalArgumentException("parent device not found: " + mapping.did));
                                }
                                // 先落定义注册表（失败仅多一条孤文档、不会出现“半注册”设备），
                                // 再创建并持久化矩阵子设备，最终返回该 DeviceEntity
                                return repo.insert(v)
                                        .chain(ignored -> deviceService.save(buildDeviceEntity(v, parent)));
                            });
                });
    }

    // ===== 校验 =====

    private String validate(ModbusMapping mapping) {
        if (mapping == null) {
            return "body is required";
        }
        if (mapping.configId == null || mapping.configId.isBlank()) {
            return "configId is required";
        }
        if (mapping.did == null || mapping.did.isBlank()) {
            return "did is required";
        }
        if (mapping.did.contains(":")) {
            return "invalid did: " + mapping.did + " (must not contain ':')";
        }
        if (mapping.siid < 1) {
            return "invalid siid: " + mapping.siid + " (must be > 0)";
        }
        if (mapping.aiid < 1) {
            return "invalid aiid: " + mapping.aiid + " (must be > 0)";
        }
        return null;
    }

    // ===== 生成 =====

    private ModbusVirtualDevice build(ModbusConfig cfg, ModbusMapping mapping, String orgId, Person who) {
        DeviceType base = parseDeviceType(cfg.slave == null || cfg.slave.type == null ? null : cfg.slave.type.type);
        String vendor = mapping.did.toLowerCase(Locale.ROOT);
        String model = mapping.configId.toLowerCase(Locale.ROOT);

        // 复用配置里的 ns/name/value 重建实例类型头部（避免 base 已是 8 段带 org/model 时重复追加）。
        String headValue = String.format("%08x", ((long) base.value()) & 0x7FFFFFFFL);
        String deviceType = DeviceType.parse(String.format(
                "urn:%s:device:%s:%s:%s:%s:1", base.ns(), base.name(), headValue, vendor, model)).toString();

        DeviceInstance device = new DeviceInstance(DeviceType.parse(deviceType));
        if (cfg.slave != null && cfg.slave.description != null && !cfg.slave.description.isBlank()) {
            device.description(Map.of(Spec.DESCRIPTION_EN_US, cfg.slave.description));
        }

        String ns = base.ns();
        Service svc = new Service(1, ServiceType.parse(subUrn(ns, "service", "modbus", cfg.id + ":svc", vendor, model)));
        List<Property<Object>> props = new ArrayList<>();
        List<Action> actions = new ArrayList<>();
        // 排序与 iid 均依赖 ModbusCommand.index（1 起自然数）：按 index 升序生成，数组序不再影响输出
        List<ModbusCommand> commands = new ArrayList<>(cfg.commands == null ? List.of() : cfg.commands);
        commands.sort(Comparator.comparingInt(cmd -> cmd == null || cmd.index == null ? Integer.MAX_VALUE : cmd.index));
        for (int i = 0; i < commands.size(); i++) {
            ModbusCommand cmd = commands.get(i);
            if (cmd == null) {
                continue;
            }
            boolean read = READ_FCS.contains(cmd.fc);
            String actionUrn = subUrn(ns, "action", "a" + i, cfg.id + ":action:" + i, vendor, model);
            // action iid = command.index（1 起自然数）；存量文档未回填 index 时兜底用排序位置
            int iid = cmd.index == null ? i + 1 : cmd.index;
            Action action = new Action(iid, ActionType.parse(actionUrn));
            if (cmd.name != null && !cmd.name.isBlank()) {
                action.description(Map.of(Spec.DESCRIPTION_EN_US, cmd.name));
            }
            if (read) {
                int piid = props.size() + 1;
                String propUrn = subUrn(ns, "property", "v" + (props.size()), cfg.id + ":prop:" + (props.size()), vendor, model);
                Property<Object> prop = new Property<>(
                        piid, PropertyType.parse(propUrn), new Access(false, true, false), dataFormat(cmd));
                if (cmd.unit != null && !cmd.unit.isBlank()) {
                    prop.unit(cmd.unit);
                }
                props.add(prop);
                action.out(List.of(new Argument(piid)));
            }
            actions.add(action);
        }
        svc.properties(props);
        svc.actions(actions);
        device.services().put(1, svc);

        ModbusVirtualDevice v = new ModbusVirtualDevice();
        v.did = UUID.randomUUID().toString();
        // 虚拟设备归属当前操作组织（跨组织公开点表可被本组织虚拟化）
        v.orgId = orgId;
        v.mapping = mapping;
        v.instance.type = deviceType;
        v.instance.description = cfg.slave == null ? null : cfg.slave.description;
        v.instance.definition = DeviceInstanceCodec.encode(device).toString();
        v.creator = who;
        return v;
    }

    /**
     * 由虚拟设备定义生成矩阵 {@link DeviceEntity} 子设备：
     * did=虚拟设备 id、type=派生 DeviceType、protocol=modbus、离线起步；挂在父设备 {@code mapping.did} 下
     * （parentId=父 did，rootId=父所在树的根：父为根时即父 did），并复制父设备所在 space，让子设备同空间可见。
     */
    private DeviceEntity buildDeviceEntity(ModbusVirtualDevice v, DeviceEntity parent) {
        DeviceEntity child = new DeviceEntity();
        child.did = v.did;
        child.type = v.instance.type;
        child.online = false;
        child.protocol = "modbus";
        child.parentId = parent.did;
        child.rootId = parent.rootId == null || parent.rootId.isBlank() ? parent.did : parent.rootId;
        if (parent.space != null) {
            DeviceSpace space = new DeviceSpace();
            space.spaceId = parent.space.spaceId;
            space.rootId = parent.space.rootId;
            child.space = space;
        }
        return child;
    }

    /** 品类 DeviceType（SPEC/XIOT 均可）解析失败时给出引导文案。 */
    private DeviceType parseDeviceType(String type) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("modbus device config type is not set, please select a device type first");
        }
        try {
            return DeviceType.parse(type);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid modbus device config type: " + type);
        }
    }

    /**
     * 组一个 8 段 XIOT 子类型 URN（vendor=父 did、model=点表配置 id，version=1）。
     *
     * @param kind     device/service/action/property
     * @param name     名称（统一小写）
     * @param hexSeed  用于确定性生成 8 位 value 段
     */
    private String subUrn(String ns, String kind, String name, String hexSeed, String vendor, String model) {
        String raw = String.format("urn:%s:%s:%s:%s:%s:%s:1",
                ns, kind, name.toLowerCase(Locale.ROOT), hex8(hexSeed), vendor, model);
        return switch (kind) {
            case "service" -> ServiceType.parse(raw).toString();
            case "action" -> ActionType.parse(raw).toString();
            case "property" -> PropertyType.parse(raw).toString();
            default -> throw new IllegalArgumentException("unknown urn kind: " + kind);
        };
    }

    /** 稳定确定性 8 位小写十六进制（同一点表重复生成同一套类型）。只保留 < 0x80000000 保证可被 Urn 解析。 */
    private static String hex8(String seed) {
        return String.format("%08x", seed.hashCode() & 0x7FFFFFFF);
    }

    /** 读命令 backing property 的 DataFormat：fc 01/02 → bool；03/04 → 按 dataType 映射。 */
    private static DataFormat dataFormat(ModbusCommand cmd) {
        if (cmd.fc == null || cmd.fc.equals("01") || cmd.fc.equals("02")) {
            return DataFormat.BOOL;
        }
        String type = cmd.dataType;
        if (type == null) {
            return DataFormat.STRING;
        }
        return switch (type) {
            case "int16" -> DataFormat.INT16;
            case "uint16" -> DataFormat.UINT16;
            case "int32" -> DataFormat.INT32;
            case "uint32" -> DataFormat.UINT32;
            case "float32" -> DataFormat.FLOAT;
            default -> DataFormat.STRING;
        };
    }

}
