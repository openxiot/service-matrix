package cc.openxiot.site.db.space;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.bson.codecs.pojo.annotations.BsonDiscriminator;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@MongoEntity(collection = "spaces")
@BsonDiscriminator
@RegisterForReflection
@Schema(description = "空间")
public class SpaceEntity extends PanacheMongoEntity {

    @Schema(description = "租户ID", required = true)
    public String tenantId;

    @Schema(description = "空间名称", required = true)
    public String name;

    @Schema(description = "空间类型", required = true)
    public String type;

    @Schema(description = "类型别名", required = false)
    public String typeAlias;

    @Schema(description = "父级空间ID", required = false)
    public String parentId;

    @Schema(description = "根级空间ID", required = false)
    public String rootId;

    @Schema(description = "层级深度", required = true)
    public int level;

    @Schema(description = "祖先ID列表", required = false)
    public List<String> ancestors;

    @Schema(description = "排序序号", required = true)
    public int sortOrder;

//    @Schema(description = "状态", required = true)
//    public String status;

    @Schema(description = "地理位置", required = false)
    public SpaceLocation location;

    @Schema(description = "扩展属性", required = false)
    public Map<String, Object> attributes;

    @Schema(description = "空间用户", required = false)
    public List<SpaceUser> users;

    @Schema(description = "创建时间", required = true)
    public LocalDateTime createTime;

    @Schema(description = "更新时间", required = true)
    public LocalDateTime updateTime;

    public void update() {
        this.updateTime = LocalDateTime.now();
        super.update();
    }

    public void persist() {
        if (this.createTime == null) {
            this.createTime = LocalDateTime.now();
        }
        this.updateTime = LocalDateTime.now();
        super.persist();
    }
}