package cc.openxiot.matrix.api.member;

import cc.openxiot.common.util.JwtUtil;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * service-account 虚拟组织（项目成员）系统接口的封装。
 * 统一处理 X-System-Token 签发与 {"success":true/false,"message":...} 响应解析：
 * 业务失败抛 IllegalArgumentException，网络/HTTP 失败原样上抛。
 */
@ApplicationScoped
public class AccountService {

    @Inject
    @RestClient
    AccountClient client;

    @Inject
    @ConfigProperty(name = "openxiot.internal.secret")
    String systemSecret;

    /** 创建/更新虚拟组织（项目成员关系）。memberId/creatorId 为空表示仅改名同步。 */
    public Uni<Void> upsertVirtual(String rootSpaceId, String name, String memberId, String creatorId) {
        VirtualOrgRequest body = new VirtualOrgRequest();
        body.name = name;
        body.memberId = memberId;
        body.creatorId = creatorId;
        String systemToken = JwtUtil.createOwnerIdToken(rootSpaceId, systemSecret);
        return client.upsertVirtual(systemToken, body)
                .map(this::checkSuccess);
    }

    /** 移除项目成员 / 删除虚拟组织。memberId 为空表示整体删除。 */
    public Uni<Void> deleteVirtual(String rootSpaceId, String memberId) {
        String systemToken = JwtUtil.createOwnerIdToken(rootSpaceId, systemSecret);
        return client.deleteVirtual(systemToken, rootSpaceId, memberId)
                .map(this::checkSuccess);
    }

    private Void checkSuccess(String resp) {
        JsonObject json = new JsonObject(resp);
        if (!Boolean.TRUE.equals(json.getBoolean("success"))) {
            throw new IllegalArgumentException(json.getString("message", "Unknown error"));
        }
        return null;
    }
}
