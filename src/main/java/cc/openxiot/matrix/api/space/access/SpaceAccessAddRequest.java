package cc.openxiot.matrix.api.space.access;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * 添加根空间用户访问条目（成员）的请求体。
 * native 镜像下需注册反射供 Jackson 反序列化。
 */
@RegisterForReflection
public class SpaceAccessAddRequest {

    @Schema(description = "成员账号 ID", required = true)
    public String memberId;

    @Schema(description = "角色：admin（管理员）/ member（普通成员），为空默认 member")
    public String role;

    @Schema(description = "备注")
    public String remark;
}
