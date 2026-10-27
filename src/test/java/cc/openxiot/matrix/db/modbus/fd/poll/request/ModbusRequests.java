package cc.openxiot.matrix.db.modbus.fd.poll.request;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试期的请求定义工厂。
 *
 * <p>结构化之前，各处测试都硬写着一整串帧（{@code function.request = "010300000001840A"}）——
 * 那串 16 进制里到底表达了什么（从站几、fc 几、从哪读几个）只有把它解回字节才看得出来。
 * 现在按字段写，测试读起来就是它断言的东西。</p>
 */
public final class ModbusRequests {

    private ModbusRequests() {
    }

    /** 一条读请求：从站 1、起始 {@code start}、数量 {@code quantity}（fc 01/02 是位数、03/04 是寄存器数）。 */
    public static ModbusRequest read(String fc, int start, int quantity) {
        ModbusRequest request = new ModbusRequest();
        request.slaveId = 1;
        request.fc = fc;
        request.start = start;
        request.quantity = quantity;
        return request;
    }

    /**
     * 最常用的那条读请求：fc 03、起始 0、读 1 个寄存器。
     * 组出的帧就是 {@code 010300000001840A}（组帧测试里的定点回归值）。
     */
    public static ModbusRequest holding() {
        return read("03", 0, 1);
    }

    /** 一条写请求：从站 1、起始 {@code start}、给定的写入字段。 */
    public static ModbusRequest write(String fc, int start, ModbusRequestField... fields) {
        ModbusRequest request = new ModbusRequest();
        request.slaveId = 1;
        request.fc = fc;
        request.start = start;
        request.fields = new ArrayList<>(List.of(fields));
        return request;
    }

    /** 一个写入字段（05/06：帧里只有单值，偏移不填）。 */
    public static ModbusRequestField field(int index, String name, String format, Object value) {
        return field(index, name, null, format, null, value);
    }

    /** 一个写入字段（0F/10：{@code offset} 是位偏移 / 寄存器偏移，跨度不大于 1 个寄存器）。 */
    public static ModbusRequestField field(int index, String name, int offset, String format, Object value) {
        return field(index, name, offset, format, null, value);
    }

    /** 一个写入字段（10：32 位格式要一并给字节序）。 */
    public static ModbusRequestField field(int index, String name, int offset, String format,
                                           String byteOrder, Object value) {
        return field(index, name, Integer.valueOf(offset), format, byteOrder, value);
    }

    private static ModbusRequestField field(int index, String name, Integer offset, String format,
                                            String byteOrder, Object value) {
        ModbusRequestField field = new ModbusRequestField();
        field.index = index;
        field.field = name;
        field.offset = offset;
        field.format = format;
        field.byteOrder = byteOrder;
        field.value = value;
        return field;
    }
}
