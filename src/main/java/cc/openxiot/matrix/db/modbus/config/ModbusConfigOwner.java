package cc.openxiot.matrix.db.modbus.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Modbus 点表配置的归属主体：一条点表可能属于一个组织，也可能属于某个人。
 *
 * <p>{@code type} 区分主体类型（organization 组织 / user 个人），{@code id} 是对应主体的 ID
 * （组织编码或账号 ID），{@code name} 是创建时的名称快照（展示用，不随改名回写）。
 * 三种身份复用：{@code ModbusConfigResource.createOne} 请求体中的 {@code owner} 子对象、
 * 落库在 {@link ModbusConfig#owner} 的子文档、响应 {@code owner} 对象的原样回显。</p>
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "点表归属主体（organization 组织 / user 个人）")
public class ModbusConfigOwner {

    /** 主体类型：组织。 */
    public static final String TYPE_ORGANIZATION = "organization";
    /** 主体类型：个人。 */
    public static final String TYPE_USER = "user";

    @Schema(description = "主体 ID（组织编码 或 账号 ID）", required = true)
    public String id;

    @Schema(description = "主体类型：organization 组织 / user 个人", examples = {"organization"}, required = true)
    public String type;

    @Schema(description = "名称快照（创建时取，展示用）", required = false)
    public String name;

    public static ModbusConfigOwner ofOrg(String id, String name) {
        return of(id, TYPE_ORGANIZATION, name);
    }

    public static ModbusConfigOwner ofUser(String id, String name) {
        return of(id, TYPE_USER, name);
    }

    private static ModbusConfigOwner of(String id, String type, String name) {
        ModbusConfigOwner owner = new ModbusConfigOwner();
        owner.id = id;
        owner.type = type;
        owner.name = name;
        return owner;
    }
}