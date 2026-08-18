package cc.openxiot.matrix.db.space;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.bson.codecs.pojo.annotations.BsonProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * 空间访问条目（主体 + 角色）：组织 ID 或个人用户 ID。
 * <p>
 * 一个根空间可以有多个条目（List&lt;SpaceAccess&gt;，见 {@link SpaceEntity#accesses}）：
 * <ul>
 *     <li>type = organization：该组织是根空间的管理员（组织管理员可管理，组织成员可读）；</li>
 *     <li>type = user：该用户是根空间的管理员（role = admin）或普通成员（role = member）。</li>
 * </ul>
 */
@RegisterForReflection
public class SpaceAccess {

    @BsonProperty("id")
    @Schema(description = "主体ID（组织ID或个人ID）", required = true)
    public String id;

    @Schema(description = "主体类型：organization / user", required = true)
    public String type;

    @Schema(description = "角色：admin（管理员）/ member（普通成员）", required = false)
    public String role;

    @Schema(description = "名称快照（组织名或用户名，写库时从 account 解析，改名不追）", required = false)
    public String name;

    @Schema(description = "备注", required = false)
    public String remark;

    public SpaceAccess() {
    }

    public SpaceAccess(String id, String type, String role) {
        this.id = id;
        this.type = type;
        this.role = role;
    }

    public SpaceAccess(String id, String type, String role, String name, String remark) {
        this.id = id;
        this.type = type;
        this.role = role;
        this.name = name;
        this.remark = remark;
    }
}
