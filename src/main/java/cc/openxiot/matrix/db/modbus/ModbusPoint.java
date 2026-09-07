package cc.openxiot.matrix.db.modbus;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Modbus 一个寄存器/点位（字段词表对齐品牌网关工程配置 tags[]）。
 */
@RegisterForReflection
@Schema(description = "Modbus 寄存器点位")
public class ModbusPoint {

    @Schema(description = "点位名称，如 CHW_LEAVING_TEMP", required = true)
    public String name;

    @Schema(description = "寄存器区：input / holding / coil", examples = {"holding"})
    public String area;

    @Schema(description = "寄存器地址（原始/偏移地址，品牌相关）", examples = {"0", "16384", "30001"})
    public Integer address;

    @Schema(description = "逻辑地址（1 基址，如 40001）", examples = {"40001"})
    public Integer logicalAddress;

    @Schema(description = "数据类型：int16/uint16/int32/uint32/float32/string 等", examples = {"uint16"}, required = true)
    public String dataType;

    @Schema(description = "访问权限：r 只读 / w 只写 / rw 读写", examples = {"rw"})
    public String rw;

    @Schema(description = "缩放系数", examples = {"0.1"})
    public Double scale;

    @Schema(description = "单位，如 ℃ / °F / % / kPa", examples = {"℃"})
    public String unit;

    @Schema(description = "描述（可选）")
    public String description;
}
