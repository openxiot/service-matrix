package cc.openxiot.matrix.api.member;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * 添加项目成员的请求体。
 */
public class MemberAddRequest {

    @Schema(description = "项目成员账号 ID", required = true)
    public String memberId;

    @Schema(description = "角色：admin（管理员）/ member（普通成员），为空默认 member")
    public String role;

    @Schema(description = "备注")
    public String remark;
}
