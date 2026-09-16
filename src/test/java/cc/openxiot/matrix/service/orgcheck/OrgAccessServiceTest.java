package cc.openxiot.matrix.service.orgcheck;

import cc.openxiot.account.user.organization.UserOrganization;
import cc.openxiot.account.user.organization.member.Member;
import cc.openxiot.account.user.organization.member.MemberRole;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.matrix.db.space.access.SpaceAccess;
import cc.openxiot.matrix.db.space.access.SpaceAccessRole;
import cc.openxiot.matrix.db.space.access.SpaceAccessType;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OrgAccessService} 的判定：谁是这个空间的成员 / 管理员。
 *
 * <p><b>为什么不带 orgId</b>：判定用的组织从**空间自己的 accesses** 里读，不看请求头。这一条是本测试
 * 的重点 —— 下面每一条「组织管理员过、普通成员过一半」的用例都**没有**任何参数能表达「我是哪个组织的」，
 * 因为没有那个参数，也不可能传错（历史上正是「传了但没传」导致的 {@code OrgId does not match the space}：
 * 组织建的项目，创建者只有组织条目，客户端一不带 {@code X-Org-Id}，管理员就被拒了）。</p>
 *
 * <p><b>纯内存</b>：不连 Mongo、不开 Quarkus。唯一的库调用是 {@code UserOrganization.findById}，
 * 被 {@link FakeOrgs} 顶掉了（这就是那个 {@code protected findOrg} 存在的理由）。</p>
 */
class OrgAccessServiceTest {

    private static final String ME = "me";
    private static final String OTHER = "someone-else";
    private static final String ORG = "org-1";

    // ===== 组织建的项目：创建者只有组织条目，没有 user 条目 =====

    @Test
    void orgAdmin_passesAdminCheck_withoutPassingAnyOrgId() {
        FakeOrgs orgs = new FakeOrgs().with(ORG, admin(ME));
        SpaceEntity space = orgSpace(ORG);

        assertPasses(orgs.checkSpaceAdmin(space, ME));
        assertPasses(orgs.checkSpaceMember(space, ME));
    }

    @Test
    void orgPlainMember_passesMemberCheck_butNotAdminCheck() {
        FakeOrgs orgs = new FakeOrgs().with(ORG, member(ME));
        SpaceEntity space = orgSpace(ORG);

        assertPasses(orgs.checkSpaceMember(space, ME));
        assertEquals("Not an admin of the organization", failureMessage(orgs.checkSpaceAdmin(space, ME)));
    }

    /** 空间属于 org-1，我是 org-2 的管理员 —— 别人的组织管不了这个项目。 */
    @Test
    void adminOfAnotherOrg_isRejected() {
        FakeOrgs orgs = new FakeOrgs()
                .with(ORG, member(OTHER))
                .with("org-2", admin(ME));
        SpaceEntity space = orgSpace(ORG);

        assertEquals("Not a member of the organization", failureMessage(orgs.checkSpaceMember(space, ME)));
        assertEquals("Not an admin of the organization", failureMessage(orgs.checkSpaceAdmin(space, ME)));
    }

    @Test
    void notInTheOrgAtAll_isRejected() {
        FakeOrgs orgs = new FakeOrgs().with(ORG, member(OTHER));
        SpaceEntity space = orgSpace(ORG);

        assertEquals("Not a member of the organization", failureMessage(orgs.checkSpaceMember(space, ME)));
        assertEquals("Not an admin of the organization", failureMessage(orgs.checkSpaceAdmin(space, ME)));
    }

    /** 组织被删掉之后，它的项目没有归属 —— 报「组织没了」，而不是「你不是成员」。 */
    @Test
    void orgDeleted_isReportedAsMissingOrg_notAsMissingMembership() {
        FakeOrgs orgs = new FakeOrgs();     // 一张空表：org-1 查不到
        SpaceEntity space = orgSpace(ORG);

        assertEquals("Organization not found", failureMessage(orgs.checkSpaceMember(space, ME)));
        assertEquals("Organization not found", failureMessage(orgs.checkSpaceAdmin(space, ME)));
    }

    /** members 为 null（老数据 / 半截文档）不能让判定 NPE 崩成 500，要当成「不是成员」。 */
    @Test
    void orgWithNullMembers_isTreatedAsNoMembers() {
        FakeOrgs orgs = new FakeOrgs().withNullMembers(ORG);
        SpaceEntity space = orgSpace(ORG);

        assertEquals("Not a member of the organization", failureMessage(orgs.checkSpaceMember(space, ME)));
        assertEquals("Not an admin of the organization", failureMessage(orgs.checkSpaceAdmin(space, ME)));
    }

    // ===== 个人建的项目：只有 user 条目 =====

    @Test
    void directUserAdmin_passesBothChecks() {
        FakeOrgs orgs = new FakeOrgs();
        SpaceEntity space = space(user(ME, SpaceAccessRole.ADMIN));

        assertPasses(orgs.checkSpaceAdmin(space, ME));
        assertPasses(orgs.checkSpaceMember(space, ME));
    }

    @Test
    void directUserMember_passesMemberCheck_butNotAdminCheck() {
        FakeOrgs orgs = new FakeOrgs();
        SpaceEntity space = space(user(ME, SpaceAccessRole.MEMBER));

        assertPasses(orgs.checkSpaceMember(space, ME));
        assertEquals("No access to this space", failureMessage(orgs.checkSpaceAdmin(space, ME)));
    }

    @Test
    void onlyOtherUsersAreListed_isRejected() {
        FakeOrgs orgs = new FakeOrgs();
        SpaceEntity space = space(user(OTHER, SpaceAccessRole.ADMIN));

        assertEquals("No access to this space", failureMessage(orgs.checkSpaceMember(space, ME)));
        assertEquals("No access to this space", failureMessage(orgs.checkSpaceAdmin(space, ME)));
    }

    // ===== 短路：能判就不查库 =====

    /**
     * 有 user 条目就不去问组织 —— 哪怕空间同时挂着一条**已经查不到**的组织条目。
     *
     * <p>这条钉的是「短路的顺序」，不只是「结果对」：顺序反过来的话，一个只有组织条目失效的项目
     * 会把它的用户管理员一起关在门外。</p>
     */
    @Test
    void directUserEntryWins_orgIsNotEvenLookedUp() {
        FakeOrgs orgs = new FakeOrgs();     // 空间上的组织条目查不到
        SpaceEntity space = space(user(ME, SpaceAccessRole.ADMIN), org(ORG));

        assertPasses(orgs.checkSpaceAdmin(space, ME));
        assertTrue(orgs.lookups.isEmpty(), "有 user 条目时不该去查组织，实际查了：" + orgs.lookups);
    }

    // ===== accesses 本身没配好 =====

    @Test
    void missingAccesses_isRejected() {
        FakeOrgs orgs = new FakeOrgs();
        SpaceEntity space = new SpaceEntity();     // accesses = null

        assertEquals("Space accesses is not set", failureMessage(orgs.checkSpaceMember(space, ME)));
        assertEquals("Space accesses is not set", failureMessage(orgs.checkSpaceAdmin(space, ME)));
    }

    @Test
    void emptyAccesses_isRejected() {
        FakeOrgs orgs = new FakeOrgs();
        SpaceEntity space = space();

        assertEquals("Space accesses is not set", failureMessage(orgs.checkSpaceMember(space, ME)));
        assertEquals("Space accesses is not set", failureMessage(orgs.checkSpaceAdmin(space, ME)));
    }

    // ===== 已知的局限，写下来免得它悄悄变成 bug =====

    /**
     * 一个空间挂**两条**组织条目时，只有第一条参与判定（{@code orgAccess} 取 findFirst）。
     *
     * <p>今天走不到这里：组织条目只由 {@code SpaceService.createWithAccess} 在建根空间时写一条，
     * 子空间整份继承，{@code addAccess} 只写 user 条目。哪天允许一个项目挂第二个组织（把别的组织
     * 加为协作方），这里必须改成「任一命中即成立」—— 第二个组织的管理员现在会被无声拒绝。这条用例
     * 因此是**现状的记录**，不是期望的行为。</p>
     */
    @Test
    void twoOrgEntries_onlyTheFirstOneCounts_knownLimitation() {
        FakeOrgs orgs = new FakeOrgs()
                .with(ORG, member(OTHER))
                .with("org-2", admin(ME));
        SpaceEntity space = space(org("org-1"), org("org-2"));

        assertEquals("Not an admin of the organization", failureMessage(orgs.checkSpaceAdmin(space, ME)));
        assertTrue(orgs.lookups.contains(ORG) && !orgs.lookups.contains("org-2"),
                "应该只问了第一条组织条目，实际：" + orgs.lookups);
    }

    // ===== 夹具 =====

    /** 顶掉 {@code findOrg} 的假组织表：只认登记过的 orgId，查不到就是 null（= 组织不存在）。 */
    private static class FakeOrgs extends OrgAccessService {

        private final Map<String, UserOrganization> orgs = new HashMap<>();

        /** 记录问过哪几个组织 —— 用来断言「该查的查了、不该查的没查」。 */
        private final List<String> lookups = new ArrayList<>();

        FakeOrgs with(String orgId, Member... members) {
            UserOrganization org = new UserOrganization();
            org.code = orgId;
            org.members = List.of(members);
            orgs.put(orgId, org);
            return this;
        }

        FakeOrgs withNullMembers(String orgId) {
            UserOrganization org = new UserOrganization();
            org.code = orgId;
            org.members = null;
            orgs.put(orgId, org);
            return this;
        }

        @Override
        protected Uni<UserOrganization> findOrg(String orgId) {
            lookups.add(orgId);
            return Uni.createFrom().item(orgs.get(orgId));
        }
    }

    private static SpaceEntity space(SpaceAccess... accesses) {
        SpaceEntity space = new SpaceEntity();
        space.accesses = new ArrayList<>(List.of(accesses));
        return space;
    }

    /** 组织建的项目：只有一条组织条目。 */
    private static SpaceEntity orgSpace(String orgId) {
        return space(org(orgId));
    }

    private static SpaceAccess org(String orgId) {
        return new SpaceAccess(orgId, SpaceAccessType.ORGANIZATION, SpaceAccessRole.ADMIN);
    }

    private static SpaceAccess user(String userId, String role) {
        return new SpaceAccess(userId, SpaceAccessType.USER, role);
    }

    private static Member admin(String userId) {
        return new Member(userId, MemberRole.ADMIN);
    }

    private static Member member(String userId) {
        return new Member(userId, MemberRole.MEMBER);
    }

    // ===== 断言 =====

    /** 通过即返回；失败会把原始异常抛出来（挂掉的用例直接看得见原因）。 */
    private static void assertPasses(Uni<Void> check) {
        check.await().indefinitely();
    }

    /** 失败信息；**通过时返回 null** —— 于是「本该失败的却通过了」会以「expected: <...> but was: <null>」暴露。 */
    private static String failureMessage(Uni<Void> check) {
        try {
            check.await().indefinitely();
        } catch (Throwable t) {
            Throwable cause = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
            return cause.getMessage();
        }
        return null;
    }
}
