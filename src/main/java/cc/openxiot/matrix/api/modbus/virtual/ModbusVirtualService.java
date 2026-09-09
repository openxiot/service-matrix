package cc.openxiot.matrix.api.modbus.virtual;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import cc.openxiot.common.person.Person;
import cc.openxiot.matrix.api.modbus.config.ModbusConfigService;
import cc.openxiot.matrix.api.modbus.virtual.params.CreateModbusVirtualDeviceRequest;
import cc.openxiot.matrix.db.modbus.config.ModbusCommand;
import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.virtual.ModbusVirtual;
import cc.openxiot.matrix.db.modbus.virtual.ModbusVirtualRepository;
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
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Modbus 虚拟设备生成：把一条 Modbus 点表配置虚拟成一个挂在父设备下的子设备实例定义。
 *
 * <p>范围 = 仅生成定义并落库（不注册真实设备）。虚拟设备 DeviceType 继承点表配置的 {@code type}（品类 DeviceType），
 * 只追加 vendor（父 did）、model（点表配置 id）、version=1，即 8 段 XIOT 实例类型。</p>
 *
 * <p>实例定义用 xiot-spec 模型代码构造 {@link DeviceInstance}（单个 modbus Service），再经
 * {@link DeviceInstanceCodec} 编码为 JSON 文本落库，不手拼 JSON。读命令（fc 01/02/03/04）产出一个
 * 只读 backing property + 带 {@code out} 参数的 action；写命令（fc 05/06/0F/10）仅产出无参 action。</p>
 */
@ApplicationScoped
public class ModbusVirtualService {

    /** 读功能码。 */
    private static final List<String> READ_FCS = List.of("01", "02", "03", "04");

    @Inject
    Logger logger;

    @Inject
    ModbusConfigService configService;

    @Inject
    ModbusVirtualRepository repo;

    /**
     * 校验 → 取点表配置 → 构造 DeviceInstance 并编码 → 落库 → 返回摘要数据。
     */
    public Uni<JsonObject> create(String orgId, CreateModbusVirtualDeviceRequest request, Person who) {
        String err = validate(request);
        if (err != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(err));
        }
        return configService.get(orgId, request.modbusConfigId)
                .onItem().transformToUni(cfg -> {
                    try {
                        ModbusVirtual v = build(cfg, request, orgId, who);
                        return repo.insert(v).map(ignored -> toData(v));
                    } catch (IllegalArgumentException e) {
                        return Uni.createFrom().failure(e);
                    }
                });
    }

    // ===== 校验 =====

    private String validate(CreateModbusVirtualDeviceRequest request) {
        if (request == null) {
            return "body is required";
        }
        if (request.modbusConfigId == null || request.modbusConfigId.isBlank()) {
            return "modbusConfigId is required";
        }
        if (request.did == null || request.did.isBlank()) {
            return "did is required";
        }
        if (request.did.contains(":")) {
            return "invalid did: " + request.did + " (must not contain ':')";
        }
        if (request.siid < 1) {
            return "invalid siid: " + request.siid + " (must be > 0)";
        }
        if (request.aiid < 1) {
            return "invalid aiid: " + request.aiid + " (must be > 0)";
        }
        return null;
    }

    // ===== 生成 =====

    private ModbusVirtual build(ModbusConfig cfg, CreateModbusVirtualDeviceRequest request, String orgId, Person who) {
        DeviceType base = parseDeviceType(cfg.type);
        String vendor = request.did.toLowerCase(Locale.ROOT);
        String model = request.modbusConfigId.toLowerCase(Locale.ROOT);

        // 复用配置里的 ns/name/value 重建实例类型头部（避免 base 已是 8 段带 org/model 时重复追加）。
        String headValue = String.format("%08x", ((long) base.value()) & 0x7FFFFFFFL);
        String deviceType = DeviceType.parse(String.format(
                "urn:%s:device:%s:%s:%s:%s:1", base.ns(), base.name(), headValue, vendor, model)).toString();

        DeviceInstance device = new DeviceInstance(DeviceType.parse(deviceType));
        if (cfg.description != null && !cfg.description.isBlank()) {
            device.description(Map.of(Spec.DESCRIPTION_EN_US, cfg.description));
        }

        String ns = base.ns();
        Service svc = new Service(1, ServiceType.parse(subUrn(ns, "service", "modbus", cfg.id + ":svc", vendor, model)));
        List<Property<Object>> props = new ArrayList<>();
        List<Action> actions = new ArrayList<>();
        List<ModbusCommand> commands = cfg.commands == null ? List.of() : cfg.commands;
        for (int i = 0; i < commands.size(); i++) {
            ModbusCommand cmd = commands.get(i);
            if (cmd == null) {
                continue;
            }
            boolean read = READ_FCS.contains(cmd.fc);
            String actionUrn = subUrn(ns, "action", "a" + i, cfg.id + ":action:" + i, vendor, model);
            Action action = new Action(i + 1, ActionType.parse(actionUrn));
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

        ModbusVirtual v = new ModbusVirtual();
        v.id = UUID.randomUUID().toString();
        // 虚拟设备归属当前操作组织（跨组织公开点表可被本组织虚拟化）
        v.orgId = orgId;
        v.modbusConfigId = request.modbusConfigId;
        v.did = request.did;
        v.siid = request.siid;
        v.aiid = request.aiid;
        v.type = deviceType;
        v.description = cfg.description;
        v.definition = DeviceInstanceCodec.encode(device).toString();
        v.creator = who;
        return v;
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

    // ===== 回显 =====

    /** 落库后的摘要：虚拟设备形状对齐矩阵设备 + 映射信息原样回显。 */
    private JsonObject toData(ModbusVirtual v) {
        JsonObject summary = new JsonObject()
                .put("did", v.id)
                .put("type", v.type)
                .put("online", false)
                .put("protocol", "modbus")
                .put("rootId", v.did)
                .put("parentId", v.did);
        JsonObject mapping = new JsonObject()
                .put("modbusConfigId", v.modbusConfigId)
                .put("did", v.did)
                .put("siid", v.siid)
                .put("aiid", v.aiid);
        JsonObject o = new JsonObject()
                .put("virtualDeviceId", v.id)
                .put("deviceSummary", summary)
                .put("mapping", mapping);
        if (v.description != null) {
            o.put("description", v.description);
        }
        o.put("definition", new JsonObject(v.definition));
        return o;
    }
}
