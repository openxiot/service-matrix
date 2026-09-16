package cc.openxiot.matrix.service.orgcheck;

import cc.openxiot.account.user.organization.UserOrganization;
import cc.openxiot.account.user.organization.member.Member;
import cc.openxiot.account.user.organization.member.MemberRole;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.matrix.db.space.access.SpaceAccess;
import cc.openxiot.matrix.db.space.access.SpaceAccessRole;
import cc.openxiot.matrix.db.space.access.SpaceAccessType;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * 空间访问控制。根空间持有 accesses 列表（谁写进去见 {@link cc.openxiot.matrix.api.space.SpaceService}）：
 * **至多一条**组织条目（项目属于哪个组织），外加若干用户条目（role = admin / member）。
 *
 * <p><b>组织从空间自己读，不问调用方。</b>那条组织条目就是「这个项目属于谁」，所以判定用不着
 * 请求头里的 {@code X-Org-Id} —— 它既不是凭证、也不与空间绑定：任何登录账号都能把任意组织 id
 * 填进这个头，后端终究要拿 {@link #checkMember} / {@link #checkAdmin} 去真实组织里核对。
 * 它拦不住越权，**只会拦下忘了填的合法客户端**。</p>
 *
 * <p>历史上这里要求「请求头里的 orgId 命中空间的组织条目」。于是组织建的项目（创建者没有 user
 * 条目、只有组织条目 —— 见 {@code SpaceResource.addOne}）一旦客户端没带这个头，写操作就报
 * {@code OrgId does not match the space}，而那句话的字面意思（组织对不上）与实际原因（**没带**组织）
 * 是两回事。前端把组织存在一处 UI 状态里，禁用组织后这个头就消失了 —— 于是「项目管理员改不了
 * 自己的项目」。组织改从空间读之后，这条失败**在结构上不可能再发生**：不存在「对不上」，
 * 只剩「不是成员」「不是管理员」「组织没了」。</p>
 *
 * <p>读判成员、写判管理员；组织侧同样分成员 / 管理员（组织条目本身不定义具体用户，
 * 成员关系在 account 库的 {@code user-organizations} 里）。</p>
 */
@ApplicationScoped
public class OrgAccessService {

    /**
     * 取组织（含 members）。
     *
     * <p>{@code UserOrganization} 是 matrix 侧对 account 库 {@code user-organizations} 集合的
     * 投影，查它走 Panache 的静态方法。测试进程不连库（{@code quarkus.mongodb.devservices.enabled=false}），
     * 所以留这一个 {@code protected} 的口子让 {@code OrgAccessServiceTest} 顶掉 —— 那个测试因此
     * 不需要 Mongo，是纯内存的。改签名前先看那个测试。</p>
     */
    protected Uni<UserOrganization> findOrg(String orgId) {
        return UserOrganization.findById(orgId);
    }

    /** 组织成员（不分角色）。 */
    public Uni<Void> checkMember(String orgId, String accountId) {
        return findOrg(orgId)
                .map(org -> {
                    if (org == null) {
                        throw new IllegalArgumentException("Organization not found");
                    }

                    List<Member> members = org.members;
                    if (members == null || members.stream().noneMatch(m -> accountId.equals(m.userId))) {
                        throw new IllegalArgumentException("Not a member of the organization");
                    }

                    return null;
                });
    }

    /** 组织管理员。 */
    public Uni<Void> checkAdmin(String orgId, String accountId) {
        return findOrg(orgId)
                .map(org -> {
                    if (org == null) {
                        throw new IllegalArgumentException("Organization not found");
                    }

                    List<Member> members = org.members;
                    if (members == null || members.stream().noneMatch(m ->
                            accountId.equals(m.userId) && MemberRole.ADMIN.equals(m.role))) {
                        throw new IllegalArgumentException("Not an admin of the organization");
                    }

                    return null;
                });
    }

    /**
     * 空间只读访问：该空间的用户条目（任意角色），或该空间**所属组织**的成员。
     *
     * <p>成员管理（{@code /space/{rootId}/access}）与这里用的是同一个判定 —— 两者本来各有一份实现，
     * 差别只在「组织怎么挑」（那份看请求头、这份不看），已合并（见类的说明）。</p>
     */
    public Uni<Void> checkSpaceMember(SpaceEntity space, String accountId) {
        List<SpaceAccess> accesses = space.accesses;
        if (accesses == null || accesses.isEmpty()) {
            return missingAccesses();
        }

        if (isDirectMember(accesses, accountId)) {
            return Uni.createFrom().voidItem();
        }

        SpaceAccess orgEntry = orgAccess(accesses);
        if (orgEntry != null) {
            return checkMember(orgEntry.id, accountId);
        }

        return Uni.createFrom().failure(new IllegalArgumentException("No access to this space"));
    }

    /** 空间管理访问：该空间的用户管理员（user + role=admin），或该空间所属组织的管理员。 */
    public Uni<Void> checkSpaceAdmin(SpaceEntity space, String accountId) {
        List<SpaceAccess> accesses = space.accesses;
        if (accesses == null || accesses.isEmpty()) {
            return missingAccesses();
        }

        if (isDirectAdmin(accesses, accountId)) {
            return Uni.createFrom().voidItem();
        }

        SpaceAccess orgEntry = orgAccess(accesses);
        if (orgEntry != null) {
            return checkAdmin(orgEntry.id, accountId);
        }

        return Uni.createFrom().failure(new IllegalArgumentException("No access to this space"));
    }

    /**
     * accesses 压根没配（老数据 / 半截文档）。
     *
     * <p>是一条**失败 Uni**，不是同步抛 —— 与上面另外两条失败走同一条路。两个公开方法都声明返回
     * {@code Uni}，调用方因此合理地写成 {@code .chain(...).onFailure(...)}；原先这一条是普通方法
     * 调用直接抛，碰巧所有调用点都写在 {@code chain} 的 lambda 里（Mutiny 会把 lambda 里抛出来的
     * 接成失败），所以看不出差别 —— 哪天有人在链外调一次，那就是一个绕过 {@code onFailure} 的 500。</p>
     */
    private Uni<Void> missingAccesses() {
        return Uni.createFrom().failure(new IllegalArgumentException("Space accesses is not set"));
    }

    private boolean isDirectMember(List<SpaceAccess> accesses, String accountId) {
        return accesses.stream().anyMatch(o ->
                SpaceAccessType.USER.equals(o.type) && accountId.equals(o.id));
    }

    private boolean isDirectAdmin(List<SpaceAccess> accesses, String accountId) {
        return accesses.stream().anyMatch(o ->
                SpaceAccessType.USER.equals(o.type)
                        && accountId.equals(o.id)
                        && SpaceAccessRole.ADMIN.equals(o.role));
    }

    /**
     * 该空间所属的组织条目（没有则 null）。
     *
     * <p>取第一条即全部：组织条目只由 {@code SpaceService.createWithAccess} 在建**根空间**时写入、
     * 且只写一条；子空间整份继承父空间的 accesses；此后没有任何接口会再往 accesses 里加组织条目
     * （{@code SpaceService.addAccess} 只写 user 条目）。所以不存在「该按哪个组织判」的歧义 ——
     * 也正因为不存在，原先那个「用请求头指定按哪个组织判」的作用是空的。</p>
     *
     * <p>⚠️ 这条推理依赖「最多一条」。哪天允许一个项目挂第二个组织（比如把别的组织加为协作方），
     * 这里必须改成「凡是命中的组织条目，任一成立即可」—— 否则第二个组织的管理员会被无声拒绝
     * （他们会拿到「Not an admin of the organization」，看不出是被挑了另一个组织）。</p>
     */
    private SpaceAccess orgAccess(List<SpaceAccess> accesses) {
        return accesses.stream()
                .filter(o -> SpaceAccessType.ORGANIZATION.equals(o.type))
                .findFirst()
                .orElse(null);
    }
}
