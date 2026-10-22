package cc.openxiot.account.user.organization;

import cc.openxiot.account.user.organization.member.Member;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.bson.codecs.pojo.annotations.BsonId;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@RegisterForReflection
public class UserOrganization {

    @BsonId
    public String code;

    @Schema(description = "组织名称")
    public String name;

    @Schema(description = "虚拟组织：项目成员关系（code 为根空间 ID）")
    public boolean virtual;

    public List<Member> members;

    public boolean isVirtual() {
        return virtual;
    }
}
