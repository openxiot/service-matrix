package cc.openxiot.matrix.db.modbus.service.polling.response;

import cc.openxiot.matrix.db.modbus.service.polling.response.field.ModbusTransactionResponseField;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * 一个方法的应答定义：目前只有 {@link #fields}（应答数据区怎么切）。
 *
 * <p>包一层而不是把字段表直接挂在 {@code ModbusTransaction} 上，两个理由：一是与 {@code request} 对称 ——
 * 帧的两侧都是对象、各有一处放自己的属性（{@code request.fields[]} / {@code response.fields[]}）；
 * 二是给<b>应答级</b>的设定留位子：异常码到文案的映射、缺省字节序、期望帧长这类
 * 「整条应答共用一次」的东西都该落在这一层，将来加它们不用再动 {@code function} 的形状。</p>
 *
 * <p>这里刻意<b>不</b>做成 {@code List<ModbusTransactionResponse>}：一帧请求只对一帧应答
 * （见 {@code ModbusTransaction} 的注释），消费端要的也始终是「<b>一个</b>有序切片表覆盖<b>一个</b>数据区」
 * （{@code ModbusResponseParser.parse(fields, frame)}）—— 套一层 List 之后那边只能 flatten 或取 [0]，
 * 外面那层永远只有一个元素，却给每一条校验文案加一个恒定的 {@code [0]}。真要支持「一次调用读写多个
 * 不连续区段」，该加的是 {@code requests[]} / {@code responses[]} 这一对，那是另一个特性。</p>
 *
 * <p>写方法没有应答定义（应答是请求回显，没有读值），这个键整段不出现。</p>
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Modbus 服务方法的应答定义")
public class ModbusTransactionResponse {

    @Schema(description = "应答解析规则：按 index 顺序、以 bytes 依次累加偏移切分应答数据区；"
            + "读方法必须非空，写方法没有")
    public List<ModbusTransactionResponseField> fields;
}
