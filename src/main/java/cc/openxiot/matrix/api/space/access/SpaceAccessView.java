package cc.openxiot.matrix.api.space.access;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * 根空间用户访问条目视图（根空间 accesses 中 type = user 的条目）。
 */
public class SpaceAccessView {

    @Schema(description = "成员账号 ID")
    public String userId;

    @Schema(description = "成员名称快照")
    public String name;

    @Schema(description = "角色：admin（管理员）/ member（普通成员）")
    public String role;

    @Schema(description = "备注")
    public String remark;
}
