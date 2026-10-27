package cc.openxiot.matrix.typedef.modbus.failure;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * 一次 function 执行失败的**分类**：前端按它做聚合（落库的是名字，见
 * {@code ModbusServiceFailure.type}）。
 *
 * <p><b>分类必须在抛出的地方定</b>，不能事后从 message 文本里反推
 * —— 只有抛出点知道是 CRC 不符还是从站异常码、异常码是几号。
 * 所以失败点一律抛 {@link ModbusInvokeException}，记录端只做透传。</p>
 *
 * <p>分这么细，是因为**每一类对应不同的处置方向**，合并了就丢失排查线索。
 * 真要粗粒度的看板，按下面的分组在前端映射即可（分组的边界会变，落库的粒度不会）。</p>
 *
 * <ul>
 *   <li><b>设备侧</b>：{@link #NO_RESPONSE} / {@link #SLAVE_EXCEPTION} / {@link #DEVICE_ERROR}
 *       —— 去查现场：设备在不在线、点表地址对不对。</li>
 *   <li><b>报文侧</b>：{@link #CRC_MISMATCH} / {@link #FRAME_MISMATCH} / {@link #INVALID_FRAME}
 *       —— 去查链路质量与点表声明。</li>
 *   <li><b>定义侧</b>：{@link #CONFIG_ERROR} / {@link #FIELD_DEFINITION_ERROR}
 *       —— 去改服务定义，设备本身没问题。</li>
 *   <li><b>平台侧</b>：{@link #TRANSPORT_ERROR} —— 与设备无关，查服务间调用。</li>
 * </ul>
 */
@Schema(description = "现场设备执行失败的分类")
public enum ModbusFailureType {

    /** 设备没回：DTU / 从站离线、超时，或父设备的应答里根本没有帧。 */
    NO_RESPONSE,

    /**
     * 从站返回异常码（功能码最高位置 1）。{@code remoteCode} 即 Modbus 异常码：
     * 1 非法功能 / 2 非法数据地址 / 3 非法数据值 / 4 从站设备故障 / 5 确认 / 6 从站忙 /
     * 8 存储奇偶校验错 / 10 网关路径不可用 / 11 网关目标设备无应答。
     */
    SLAVE_EXCEPTION,

    /** 父设备（DTU）侧报错，{@code remoteCode} 即其状态码（非 Modbus 异常码）。 */
    DEVICE_ERROR,

    /** CRC16 校验失败：现场干扰、接线、波特率不对。设备回了，但报文不可信。 */
    CRC_MISMATCH,

    /** 应答帧的数据字节数与点表声明对不上：点表配错，或回帧的从站不是目标从站。 */
    FRAME_MISMATCH,

    /** 帧本身不成形：太短、非十六进制、结构非法。多半是半帧或截断。 */
    INVALID_FRAME,

    /** 字段定义与应答对不上：不支持的 format、bit 偏移越界。 */
    FIELD_DEFINITION_ERROR,

    /** 服务定义里的调用坐标不全（device 的 did / siid / aiid / argument）。 */
    CONFIG_ERROR,

    /** 调用链路本身失败：manipulation 服务不可用、请求发不出去。 */
    TRANSPORT_ERROR,

    /** 兜底：没归入以上任何一类（非本模块抛出的异常）。宁可说「不知道」，也不猜一个会误导排查方向的分类。 */
    UNKNOWN
}
