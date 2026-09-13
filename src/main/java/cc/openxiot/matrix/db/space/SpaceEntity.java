package cc.openxiot.matrix.db.space;

import cc.openxiot.matrix.db.space.access.SpaceAccess;
import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.mongodb.panache.reactive.ReactivePanacheMongoEntity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.bson.codecs.pojo.annotations.BsonDiscriminator;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@MongoEntity(database = "matrix", collection = "spaces")
@BsonDiscriminator
@RegisterForReflection
@Schema(description = "空间")
public class SpaceEntity extends ReactivePanacheMongoEntity {

    @Schema(description = "空间名称", required = true)
    public String name;

    @Schema(description = "空间类型", required = true)
    public String type;

    @Schema(description = "类型别名", required = false)
    public String typeAlias;

    @Schema(description = "父级空间ID", required = false)
    public String parentId;

    @Schema(description = "层级深度", required = true)
    public int level;

    @Schema(description = "祖先ID列表", required = false)
    public List<String> ancestors;

    @Schema(description = "排序序号", required = true)
    public int sortOrder;

    @Schema(description = "地理位置", required = false)
    public SpaceLocation location;

    @Schema(description = "访问条目列表（组织条目为管理员；用户条目角色可为 admin / member）", required = false)
    public List<SpaceAccess> accesses;

    @Schema(description = "扩展属性", required = false)
    public Map<String, Object> attributes;

    @Schema(description = "创建时间", required = true)
    public LocalDateTime createTime;

    @Schema(description = "更新时间", required = true)
    public LocalDateTime updateTime;

    /**
     * 根空间 ID —— 设备归属（manipulation 的 {@code ownerId}）用的就是它。
     *
     * <p>{@link #ancestors} 是**从根往下**排的（{@code SpaceService.create}：{@code parent.ancestors + [parentId]}），
     * 根空间自己没有祖先（{@code parentId = null}、{@code ancestors = []}），所以取第一个就是根，
     * 不需要额外查库、也不需要额外维护一个字段（原来那个 {@code rootId} 字段就是这么烂掉的：没人写）。</p>
     *
     * <p>兜底：历史数据或手工改库可能让子空间的 {@code ancestors} 缺失，这时退到 {@code parentId} ——
     * 认成上一级总比认成自己好（认成自己会让归属落到子空间上，跨子空间就不共享了）。</p>
     */
    public String rootSpaceId() {
        if (parentId == null || parentId.isBlank()) {
            return id == null ? null : id.toString();
        }
        return (ancestors == null || ancestors.isEmpty()) ? parentId : ancestors.getFirst();
    }
}
