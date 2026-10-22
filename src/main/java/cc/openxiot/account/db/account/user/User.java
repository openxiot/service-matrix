package cc.openxiot.account.db.account.user;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.bson.codecs.pojo.annotations.BsonId;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * service-account 的账号用户表（vendored 到本服务，只读）。
 * 用于解析空间拥有者/成员的名称快照（写库时解析，改名不追）。
 */
@RegisterForReflection
public class User {

    @BsonId
    public ObjectId id;

    @Schema(description = "邮箱")
    public String email;

    @Schema(description = "开发者用户名")
    public String username;
}
