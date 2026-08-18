package cc.openxiot.matrix.db.space;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * 空间拥有者：组织 ID 或个人用户 ID。
 * <p>
 * 组织空间由组织拥有（type = organization）；个人空间由个人用户拥有（type = user，
 * 访问时忽略 X-Org-Id，直接校验 JWT 用户）。
 */
@RegisterForReflection
public class SpaceOwner {

    @Schema(description = "拥有者ID（组织ID或个人ID）", required = true)
    public String id;

    @Schema(description = "拥有者类型：organization / user", required = true)
    public String type;

    public SpaceOwner() {
    }

    public SpaceOwner(String id, String type) {
        this.id = id;
        this.type = type;
    }
}
