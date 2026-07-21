package cc.openxiot.account.organization;

import cc.openxiot.account.organization.member.Member;
import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.mongodb.panache.reactive.ReactivePanacheMongoEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.bson.codecs.pojo.annotations.BsonId;

import java.util.List;

@MongoEntity(database = "account", collection = "account-organizations")
@RegisterForReflection
public class Organization extends ReactivePanacheMongoEntityBase {

    @BsonId
    public String code;

    public List<Member> members;
}
