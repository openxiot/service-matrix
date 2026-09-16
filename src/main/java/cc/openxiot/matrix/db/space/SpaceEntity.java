package cc.openxiot.matrix.db.space;

import cc.openxiot.matrix.db.ModelAnchor;
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

    /**
     * 这个空间在 3D 模型里的位置。
     *
     * <p><b>只能经专用端点写</b>（{@code PUT /matrix/v1/space/one/{spaceId}/anchor}），
     * 不要加进 {@link cc.openxiot.matrix.api.space.SpaceService#update} 的复制列表 ——
     * 那个接口是整体语义，不认识本字段的老客户端（比如安卓）改一次空间名就会把它抹成 null。</p>
     */
    @Schema(description = "在 3D 模型中的位置", required = false)
    public ModelAnchor anchor;

    @Schema(description = "创建时间", required = true)
    public LocalDateTime createTime;

    @Schema(description = "更新时间", required = true)
    public LocalDateTime updateTime;

    /**
     * 根空间 ID —— 设备归属（manipulation 的 {@code ownerId}）用的就是它。
     *
     * <p>{@link #ancestors} 是**从根往下**排的（{@code SpaceService.create}：{@code parent.ancestors + [parentId]}），
     * 根空间自己没有祖先（{@code parentId = null}、{@code ancestors = []}），所以取第一个就是根，
     * 不需要额外查库、也不需要额外维护一个字段。</p>
     *
     * <p>原先那个 {@code rootId} 字段删掉正是因为它「额外维护」：服务端从不计算也不写它，
     * 全靠客户端在创建子空间时自己填 —— 填对了才和 {@code ancestors[0]} 一致，漏填的调用方
     * （服务端建的空间、别的客户端）就会把设备归属带成空。{@link #parentId} 与 {@link #level} /
     * {@link #ancestors} 不一样：那三个是服务端读 {@code parentId} 之后自己算出来写的，
     * 服务端是权威，所以它们可以并存；靠调用方自觉维持的副本才是不该留的那个。</p>
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
