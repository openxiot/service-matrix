package cc.openxiot.account.organization.member;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Date;
import java.util.Objects;

@RegisterForReflection
public class Member {

    @Schema(description = "开发者 ID")
    public String developerId;
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

    public Member(String developerId) {
        this(developerId, "member");
    }

    public Member(String developerId, String role) {
        this.developerId = developerId;
        this.role = role;
        this.update = new Date();
    }

    public Member(String developerId, String role, String name, String email) {
        this.developerId = developerId;
        this.role = role;
        this.name = name;
        this.email = email;
        this.update = new Date();
    }

    public String developerId() {
        return developerId;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Member member)) return false;
        return Objects.equals(developerId, member.developerId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(developerId);
    }
}
