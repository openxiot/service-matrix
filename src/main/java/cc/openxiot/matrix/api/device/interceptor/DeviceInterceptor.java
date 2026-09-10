package cc.openxiot.matrix.api.device.interceptor;

import cc.openxiot.matrix.api.device.DeviceManipulator;
import cc.openxiot.matrix.api.device.interceptor.modbus.ModbusFrameCodec;
import cc.openxiot.matrix.api.device.interceptor.product.ProductService;
import cc.openxiot.matrix.api.device.manipulation.ManipulationService;
import cc.openxiot.matrix.db.device.DeviceRepository;
import cc.openxiot.matrix.db.modbus.config.ModbusCommand;
import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.config.ModbusConfigRepository;
import cc.openxiot.matrix.db.modbus.virtual.ModbusMapping;
import cc.openxiot.matrix.db.modbus.virtual.ModbusVirtualDevice;
import cc.openxiot.matrix.db.modbus.virtual.ModbusVirtualDeviceRepository;
import cn.geekcity.xiot.spec.codec.vertx.instance.DeviceInstanceCodec;
import cn.geekcity.xiot.spec.codec.vertx.operation.ActionOperationCodec;
import cn.geekcity.xiot.spec.instance.Action;
import cn.geekcity.xiot.spec.instance.Argument;
import cn.geekcity.xiot.spec.instance.DeviceInstance;
import cn.geekcity.xiot.spec.instance.Service;
import cn.geekcity.xiot.spec.operation.ActionOperation;
import cn.geekcity.xiot.spec.operation.ArgumentOperation;
import cn.geekcity.xiot.spec.status.Status;
import cn.geekcity.xiot.spec.xid.ActionID;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 设备操作拦截器：{@link DeviceManipulator} 里面向设备的操作在执行前先经过它。
 *
 * <p>目前只拦截「执行方法」（invokeActions），且只加工 modbus 虚拟设备：虚拟设备的 action 由点表命令生成
 * （action iid = {@link ModbusCommand#index}），它自身没有通信能力，需要翻译成父设备（DTU）的“发送”action——
 * 把点表命令编成 Modbus RTU 帧（16 进制字符串）交给父设备透传到 RS485 总线，再把父设备回传的应答帧解回
 * 虚拟 action 的 out 参数（即读命令那个 backing property 的值）。</p>
 *
 * <p>读影子、读属性、写属性暂无拦截逻辑：{@link DeviceManipulator} 里那三处拦截点仍是直接透传。</p>
 */
@ApplicationScoped
public class DeviceInterceptor {

    private static final Logger logger = Logger.getLogger(DeviceInterceptor.class);

    @Inject
    ProductService productService;

    @Inject
    ManipulationService manipulationService;

    @Inject
    ModbusVirtualDeviceRepository deviceRepository;

    @Inject
    ModbusConfigRepository configRepository;

    /** 父设备在矩阵里的设备记录：只为取它的 DeviceType，再去产品中心换实例定义 */
    @Inject
    DeviceRepository deviceEntityRepository;

    /**
     * 拦截一台 modbus 虚拟设备的一批方法调用：逐条翻译成父设备的“发送”调用。
     *
     * <p>逐条串行（RS485 半双工，一次只跑一帧），返回结果与入参一一对应、同序；
     * 单条失败只把该条标成错误状态，不牵连同批其余方法。</p>
     */
    public Uni<List<ActionOperation>> invokeActions(String orgId, String did, List<ActionOperation> actions) {
        List<ActionOperation> results = new ArrayList<>(actions.size());

        Uni<List<ActionOperation>> pipeline = Uni.createFrom().item(results);
        for (ActionOperation action : actions) {
            pipeline = pipeline.chain(list -> invokeAction(orgId, did, action)
                    .map(result -> {
                        list.add(result);
                        return list;
                    }));
        }

        return pipeline;
    }

    /** 单个虚拟 action：查虚拟设备 → 查点表 → 翻译成父设备调用一次往返。 */
    private Uni<ActionOperation> invokeAction(String orgId, String did, ActionOperation action) {
        return deviceRepository.findByIdAndOrg(did, orgId)
                .chain(device -> {
                    if (device == null) {
                        return Uni.createFrom().item(error(action, Status.DEVICE_ID_NOT_EXIST,
                                "modbus virtual device not found: " + did));
                    }

                    return configRepository.findVisibleById(device.mapping.configId, orgId)
                            .chain(config -> config == null
                                    ? Uni.createFrom().item(error(action, Status.PROPERTY_NOT_FOUND,
                                            "modbus config not found: " + device.mapping.configId))
                                    : invokeParentAction(orgId, device, config, action));
                })
                // 单条出错只影响这一条：其余方法照常执行
                .onFailure().recoverWithItem(e -> error(action, Status.INTERNAL_ERROR, message(e)));
    }

    /**
     * 虚拟 action → 父设备“发送”action：请求帧由点表命令编出，父 action 的入参 piid 由父设备的
     * 产品实例定义给出（不同 DTU 型号 piid 不同，不能写死），拿到应答后再翻译回虚拟 action 的返回值。
     */
    private Uni<ActionOperation> invokeParentAction(String orgId, ModbusVirtualDevice device,
                                                    ModbusConfig config, ActionOperation action) {
        // action 的 aiid 就是点表里那条命令的 index
        ModbusCommand command = findCommand(config, action.aid().aiid());
        if (command == null) {
            return Uni.createFrom().item(error(action, Status.ACTION_NOT_FOUND,
                    "modbus command not found: index=" + action.aid().aiid()));
        }

        String request = createCommandRequest(config, command.index);

        return deviceEntityRepository.findByDeviceId(device.mapping.did)
                .chain(parent -> {
                    if (parent == null || parent.type == null || parent.type.isBlank()) {
                        return Uni.createFrom().item(error(action, Status.DEVICE_ID_NOT_EXIST,
                                "parent device not found: " + device.mapping.did));
                    }

                    return productService.getInstance(parent.type)
                            .map(instance -> buildParentAction(device, instance, request));
                })
                .chain(parentAction -> parentAction == null
                        ? Uni.createFrom().item(error(action, Status.ACTION_NOT_FOUND,
                                "parent action not found (or it has no in argument): "
                                        + device.mapping.did + "." + device.mapping.siid + "." + device.mapping.aiid))
                        : callParent(orgId, parentAction, action, device, command));
    }

    /**
     * 构造父设备的“发送”action 调用：in 参数取父设备实例定义中该 action 声明的 piid，
     * 值统一是本次要发送的 16 进制数据帧。实例定义取不到（或该 action 没有入参）时返回 null，由调用方报错。
     */
    private static ActionOperation buildParentAction(ModbusVirtualDevice device, DeviceInstance parentInstance,
                                                     String request) {
        if (parentInstance == null) {
            return null;
        }

        ModbusMapping mapping = device.mapping;
        Service service = parentInstance.services().get(mapping.siid);
        if (service == null) {
            return null;
        }

        Action action = service.actions().get(mapping.aiid);
        if (action == null || action.in().isEmpty()) {
            return null;
        }

        ActionOperation parent = new ActionOperation(new ActionID(mapping.did, mapping.siid, mapping.aiid));
        for (Argument argument : action.in().values()) {
            parent.in().put(argument.piid(), new ArgumentOperation(argument.piid(), request));
        }

        return parent;
    }

    /** 调用父设备 action，并把应答翻译回虚拟 action 的返回值。 */
    private Uni<ActionOperation> callParent(String orgId, ActionOperation parentAction, ActionOperation action,
                                            ModbusVirtualDevice device, ModbusCommand command) {
        String body = ActionOperationCodec.QUERY.encode(List.of(parentAction)).toString();

        return manipulationService.invokeActions(orgId, body)
                .map(ActionOperationCodec.RESULT::decode)
                .map(results -> results.isEmpty() ? null : results.get(0))
                .map(result -> translate(action, device, command, result));
    }

    /**
     * 父设备应答 → 虚拟 action 的返回值：读命令把应答帧解成读值放进 out 参数（piid = backing property 的 piid），
     * 写命令只要父设备应答成功即算完成（应答是请求回显，没有读值）。
     */
    private static ActionOperation translate(ActionOperation action, ModbusVirtualDevice device,
                                             ModbusCommand command, ActionOperation parentResult) {
        if (parentResult == null) {
            return error(action, Status.INTERNAL_ERROR, "no response from parent device");
        }

        if (parentResult.isError()) {
            return error(action, parentResult.status(), parentResult.description());
        }

        ActionOperation result = new ActionOperation(action.aid());
        if (!ModbusFrameCodec.isRead(command.fc)) {
            return result;
        }

        Object value = ModbusFrameCodec.decodeResponse(command, frameOf(parentResult));

        List<Integer> piids = outPiids(device, command.index);
        if (piids.isEmpty()) {
            logger.warnv("no out argument for modbus virtual action: did={0}, index={1}", device.did, command.index);
        }
        for (int piid : piids) {
            result.out().put(piid, new ArgumentOperation(piid, value));
        }

        return result;
    }

    /** 从父设备 action 的出参里取回传的数据帧（DTU 只有一个出参，即应答帧）。 */
    private static String frameOf(ActionOperation parentResult) {
        for (ArgumentOperation argument : parentResult.out().values()) {
            if (!argument.values().isEmpty() && argument.values().get(0) != null) {
                return argument.values().get(0).toString();
            }
        }

        throw new IllegalArgumentException("no frame in parent device response, status=" + parentResult.status());
    }

    /** 虚拟 action 的出参 piid（读命令 = 该 action 对应的 backing property），取自虚拟设备自身的实例定义。 */
    private static List<Integer> outPiids(ModbusVirtualDevice device, int actionIid) {
        if (device.instance == null || device.instance.definition == null || device.instance.definition.isBlank()) {
            return List.of();
        }

        DeviceInstance instance = DeviceInstanceCodec.decode(new JsonObject(device.instance.definition));
        for (Service service : instance.services().values()) {
            Action action = service.actions().get(actionIid);
            if (action != null) {
                return new ArrayList<>(action.out().keySet());
            }
        }

        return List.of();
    }

    /** 从配置中取指令, action 的 aid，就是 ModbusCommand 的 index，然后将这个命令的请求帧转成16进制字符串。 */
    private static String createCommandRequest(ModbusConfig config, int commandIndex) {
        ModbusCommand command = findCommand(config, commandIndex);
        if (command == null) {
            throw new IllegalArgumentException("modbus command not found: index=" + commandIndex);
        }

        return ModbusFrameCodec.encodeRequest(config.slave == null ? null : config.slave.slaveId, command);
    }

    /** 按命令 index（= action 的 aiid）在点表里找命令。 */
    private static ModbusCommand findCommand(ModbusConfig config, int commandIndex) {
        if (config.commands == null) {
            return null;
        }

        return config.commands.stream()
                .filter(command -> command.index != null && command.index == commandIndex)
                .findFirst()
                .orElse(null);
    }

    /** 用与请求相同的 aid 造一个只带错误的返回值。 */
    private static ActionOperation error(ActionOperation action, int status, String description) {
        return new ActionOperation(action.aid()).error(status, description);
    }

    private static String message(Throwable e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
