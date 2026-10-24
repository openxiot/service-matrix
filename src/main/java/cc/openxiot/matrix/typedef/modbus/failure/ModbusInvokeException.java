package cc.openxiot.matrix.typedef.modbus.failure;

/**
 * 一次 function 执行失败，带上**分类**（{@link ModbusFailureType}）与远端的原始码。
 *
 * <p>分类定在抛出的地方：只有那里知道是 CRC 不符还是从站异常码、异常码是几号。
 * 事后从 message 文本里反推分类，等于把协议语义压成了字符串匹配 —— 这正是引入本异常要避免的。
 * {@code ModbusResponseParser} 与 {@code ModbusServiceService} 的失败点一律抛本异常，
 * 记录端只做透传（见 {@code ModbusHistoryWriteService.recordFailure}）。</p>
 *
 * <p><b>继承 {@link IllegalArgumentException}</b>：资源层按
 * {@code onFailure(IllegalArgumentException.class)} 把失败转成错误响应，继承它就不必动资源层，
 * 对调用方暴露的错误响应与引入本异常之前完全一致。否则一次「设备没回」会从 400 变成 500。</p>
 *
 * <p>用工厂方法而不是重载构造器：`(type, code, message)` 与 `(type, message, cause)` 都是三参，
 * 碰上 `null` 或 `int` 实参时重载解析会挑错人（`code` 被当成 message、`cause` 被当成 message）。</p>
 */
public class ModbusInvokeException extends IllegalArgumentException {

    private final ModbusFailureType type;
    private final Integer remoteCode;

    private ModbusInvokeException(ModbusFailureType type, Integer remoteCode, String message, Throwable cause) {
        super(message, cause);
        this.type = type;
        this.remoteCode = remoteCode;
    }

    /** 无远端码、无上游异常。 */
    public static ModbusInvokeException of(ModbusFailureType type, String message) {
        return new ModbusInvokeException(type, null, message, null);
    }

    /** 带上游异常（链路失败、帧原语的实现异常）。 */
    public static ModbusInvokeException of(ModbusFailureType type, String message, Throwable cause) {
        return new ModbusInvokeException(type, null, message, cause);
    }

    /** 带远端的原始码：从站异常码，或父设备状态码。 */
    public static ModbusInvokeException remote(ModbusFailureType type, Integer remoteCode, String message) {
        return new ModbusInvokeException(type, remoteCode, message, null);
    }

    public ModbusFailureType type() {
        return type;
    }

    /**
     * 远端返回的原始码：{@link ModbusFailureType#SLAVE_EXCEPTION} 时是 Modbus 异常码，
     * {@link ModbusFailureType#DEVICE_ERROR} 时是父设备状态码；其余分类为 null。
     */
    public Integer remoteCode() {
        return remoteCode;
    }
}
