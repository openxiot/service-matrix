package cc.openxiot.matrix.db.modbus.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Map;

/**
 * 设备类型（产品规范里两级选中的品类 DeviceType 及其多语文案快照）。
 *
 * <p>选择时把「产品规范（名字空间）」与「产品类型」的多语文案一并随 {@code type} 落库，
 * 供前端直接展示产品规范 / 产品类型描述，无需再回产品服务目录查询。
 * 存 {@link ModbusSlave#type}；同一值对象作 /config 请求体 {@code slave.type} 子对象、
 * 实体子文档与响应回显。</p>
 *
 * <p>文案取产品目录的对象描述 Map（如 {@code {"zh-CN":"智能楼宇","en-US":"Smart Building"}}）；
 * 存量旧文档只迁出 {@code type} URN，两个描述 Map 可能为 null（前端回退到按 URN 解析显示）。</p>
 *
 * @deprecated 已废弃不用（见 {@link ModbusSlave#type}）。代码暂留以便回滚与兼容存量文档，
 * 新代码不要再引用本类。字段与 {@code /config} 的 JSON 形状都保持不变。
 */
@Deprecated
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "设备类型：品类 DeviceType + 产品规范/产品类型多语文案快照——已废弃")
public class ModbusDeviceType {

    @Schema(description = "产品规范（名字空间）多语文案描述，如 {\"zh-CN\":\"智能楼宇\",\"en-US\":\"Smart Building\"}",
            examples = {"{\"zh-CN\":\"智能楼宇\"}"})
    public Map<String, String> specDescription;

    @Schema(description = "产品类型多语文案描述，如 {\"zh-CN\":\"冷水机组\",\"en-US\":\"Chiller\"}",
            examples = {"{\"zh-CN\":\"冷水机组\",\"en-US\":\"Chiller\"}"})
    public Map<String, String> typeDescription;

    @Schema(description = "品类 DeviceType（产品规范完整 URN），如 urn:xiot-spec:device:chiller:0000A005",
            required = true, examples = {"urn:xiot-spec:device:chiller:0000A005"})
    public String type;
}
