package cc.openxiot.matrix.db.modbus.service.transaction.response;

import cc.openxiot.matrix.db.modbus.service.transaction.response.field.ModbusResponseField;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试期的应答定义工厂。
 *
 * <p>{@code response} 是对象（{@code response.fields[]}）之后，各处测试都写
 * {@code function.response = new ModbusTransactionResponse(); function.response.fields = List.of(...)}
 * 就太吵了 —— 与 {@code ModbusTransactionRequests} 同一个理由：测试该读起来像它断言的东西。</p>
 *
 * <p>给的列表是**可变**的（{@code ArrayList}）：有几处测试要往既有应答里再塞一个字段
 * （验重名、验多字段对账），用 {@code List.of} 会在 {@code add} 上炸。</p>
 */
public final class ModbusResponses {

    private ModbusResponses() {
    }

    /** 一份应答定义：按给定顺序切分数据区。不传字段就是一张空表（写方法的样子）。 */
    public static ModbusResponse of(ModbusResponseField... fields) {
        return of(List.of(fields));
    }

    /** 同上，字段表已经攒在手里时用这个。 */
    public static ModbusResponse of(List<ModbusResponseField> fields) {
        ModbusResponse response = new ModbusResponse();
        response.fields = new ArrayList<>(fields);
        return response;
    }
}
