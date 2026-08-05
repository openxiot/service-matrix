package cc.openxiot.account.user.organization.member;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Date;
import java.util.Objects;

@RegisterForReflection
public class Member {

    @Schema(description = "用户 ID")
    public String userId;

    @Schema(description = "组织内显示名称")
    public String name;

    @Schema(description = "角色：admin（管理员）/ member（普通成员）")
    public String role;

    @Schema(description = "邮箱")
    public String email;

    @Schema(description = "最后更新时间")
    public Date update;

    public Member() {
    }

    public Member(String userId) {
        this(userId, "member");
    }

    public Member(String userId, String role) {
        this.userId = userId;
        this.role = role;
        this.update = new Date();
    }

    public Member(String userId, String role, String name, String email) {
        this.userId = userId;
        this.role = role;
        this.name = name;
        this.email = email;
        this.update = new Date();
    }

    public String userId() {
        return userId;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Member member)) return false;
        return Objects.equals(userId, member.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(userId);
    }
}
