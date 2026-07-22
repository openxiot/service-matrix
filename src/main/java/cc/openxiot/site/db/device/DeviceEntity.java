package cc.openxiot.site.db.device;

import io.quarkus.mongodb.panache.reactive.ReactivePanacheMongoEntityBase;
import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.bson.codecs.pojo.annotations.BsonDiscriminator;
import org.bson.codecs.pojo.annotations.BsonId;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Date;
import java.util.List;

@MongoEntity(database = "site", collection = "devices")
@BsonDiscriminator
@RegisterForReflection
@Schema(description = "设备")
public class DeviceEntity extends ReactivePanacheMongoEntityBase {

    @BsonId
    @Schema(description = "设备ID", required = true)
    public String did;

    @Schema(description = "DeviceType", required = true)
    public String type;

    @Schema(description = "在线与否", required = true)
    public boolean online;

    @Schema(description = "设备使用的协议", required = true)
    public String protocol;

    @Schema(description = "根设备ID", required = false)
    public String rootId;

    @Schema(description = "父设备ID", required = false)
    public String parentId;

    @Schema(description = "成员设备（仅对设备组有效）", required = false)
    public List<String> members;

    @Schema(description = "最后一次上线时间", required = false)
    public Date lastOnline;

    @Schema(description = "最后一次离线时间", required = false)
    public Date lastOffline;

    @Schema(description = "设备所在的空间", required = false)
    public DeviceSpace space;

    @Schema(description = "创建时间", required = false)
    public Date create;
}
