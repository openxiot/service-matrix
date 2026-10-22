package cc.openxiot.account.db;

import cc.openxiot.account.db.account.user.User;
import cc.openxiot.account.user.organization.UserOrganization;
import com.mongodb.client.model.Filters;
import io.quarkus.mongodb.reactive.ReactiveMongoClient;
import io.quarkus.mongodb.reactive.ReactiveMongoCollection;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;

/**
 * service-account 库的只读快照解析（vendored，仅读 account-users / user-organizations）。
 * 集合绑定在此，vendored 的 {@link User}/{@link UserOrganization} 实体不携带存储注解。
 */
@ApplicationScoped
public class AccountUserRepository {

    @Inject
    ReactiveMongoClient mongoClient;

    private ReactiveMongoCollection<User> users() {
        return mongoClient.getDatabase("account").getCollection("account-users", User.class);
    }

    private ReactiveMongoCollection<UserOrganization> orgs() {
        return mongoClient.getDatabase("account").getCollection("user-organizations", UserOrganization.class);
    }

    public Uni<User> findUserById(ObjectId id) {
        return users().find(Filters.eq("_id", id)).collect().first();
    }

    public Uni<UserOrganization> findOrgById(String code) {
        return orgs().find(Filters.eq("_id", code)).collect().first();
    }
}