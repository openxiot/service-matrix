package cc.openxiot.matrix.db.modbus.service;

import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.mongodb.panache.reactive.ReactivePanacheMongoEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.bson.codecs.pojo.annotations.BsonId;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@MongoEntity(database = "modbus", collection = "services")
@RegisterForReflection
@Schema(description = "Modbus服务（将Modbus点表映射成服务）")
public class ModbusService extends ReactivePanacheMongoEntityBase {

    @BsonId
    @Schema(description = "服务ID（服务端生成）", required = true)
    public String id;


}
