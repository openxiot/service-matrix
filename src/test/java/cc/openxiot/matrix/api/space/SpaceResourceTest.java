package cc.openxiot.matrix.api.space;

import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.device.DeviceService;
import cc.openxiot.matrix.service.orgcheck.OrgAccessService;
import cc.openxiot.matrix.db.ModelAnchor;
import cc.openxiot.matrix.db.space.access.SpaceAccessRole;
import cc.openxiot.matrix.db.space.access.SpaceAccessType;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.matrix.db.space.access.SpaceAccess;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.smallrye.mutiny.Uni;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class SpaceResourceTest {

    @InjectMock
    SpaceService spaceService;

    @InjectMock
    DeviceService deviceService;

    @InjectMock
    OrgAccessService orgAccessService;

    private static SpaceEntity orgSpace(String orgId) {
        SpaceEntity space = new SpaceEntity();
        space.accesses = List.of(new SpaceAccess(orgId, SpaceAccessType.ORGANIZATION, SpaceAccessRole.ADMIN));
        return space;
    }

    @Test
    void hello() {
        given()
                .when().get("/matrix/v1/space")
                .then()
                .statusCode(200)
                .body("success", is(true));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void getOne_returnsSpace() {
        SpaceEntity space = orgSpace("org-1");
        space.name = "test-space";

        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().item(space));
        when(orgAccessService.checkSpaceMember(any(), eq("org-1"), eq("test-user")))
                .thenReturn(Uni.createFrom().voidItem());

        given()
                .header("X-Org-Id", "org-1")
                .when().get("/matrix/v1/space/one/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(true))
                .body("data.name", is("test-space"));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void getOne_notFound_returnsError() {
        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().nullItem());

        given()
                .header("X-Org-Id", "org-1")
                .when().get("/matrix/v1/space/one/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void getOne_wrongOrgId_returnsError() {
        SpaceEntity space = orgSpace("org-2");
        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().item(space));
        when(orgAccessService.checkSpaceMember(any(), eq("org-1"), eq("test-user")))
                .thenReturn(Uni.createFrom().failure(new IllegalArgumentException("OrgId does not match the space")));

        given()
                .header("X-Org-Id", "org-1")
                .when().get("/matrix/v1/space/one/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void getAll_returnsRoots() {
        SpaceEntity root = orgSpace("org-1");
        root.name = "root";

        when(orgAccessService.checkMember("org-1", "test-user"))
                .thenReturn(Uni.createFrom().voidItem());
        when(spaceService.getRootsByAccess("org-1", SpaceAccessType.ORGANIZATION))
                .thenReturn(Uni.createFrom().item(List.of(root)));

        given()
                .header("X-Org-Id", "org-1")
                .when().get("/matrix/v1/space/all")
                .then()
                .statusCode(200)
                .body("success", is(true))
                .body("data.size()", is(1));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void getAll_noOrgId_returnsAccessibleRoots() {
        SpaceEntity root = new SpaceEntity();
        root.name = "root";
        root.accesses = List.of(new SpaceAccess("test-user", SpaceAccessType.USER, SpaceAccessRole.ADMIN));

        when(spaceService.getRootsByAccess("test-user", SpaceAccessType.USER))
                .thenReturn(Uni.createFrom().item(List.of(root)));

        given()
                .when().get("/matrix/v1/space/all")
                .then()
                .statusCode(200)
                .body("success", is(true))
                .body("data.size()", is(1));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void getAll_notMember_returnsError() {
        when(orgAccessService.checkMember("org-1", "test-user"))
                .thenReturn(Uni.createFrom().failure(
                        new IllegalArgumentException("Not a member of the organization")));

        given()
                .header("X-Org-Id", "org-1")
                .when().get("/matrix/v1/space/all")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void addOne_rootSpace_returnsCreated() {
        SpaceEntity created = new SpaceEntity();
        created.name = "new-space";
        created.level = 0;

        when(orgAccessService.checkAdmin("org-1", "test-user"))
                .thenReturn(Uni.createFrom().voidItem());
        when(spaceService.createWithAccess(any(), any()))
                .thenReturn(Uni.createFrom().item(created));

        given()
                .header("X-Org-Id", "org-1")
                .contentType(ContentType.JSON)
                .body("{\"name\":\"new-space\"}")
                .when().post("/matrix/v1/space/one")
                .then()
                .statusCode(200)
                .body("success", is(true))
                .body("data.name", is("new-space"));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void addOne_rootSpace_noOrgId_createsPersonalSpace() {
        SpaceEntity created = new SpaceEntity();
        created.name = "new-space";
        created.level = 0;
        created.accesses = List.of(new SpaceAccess("test-user", SpaceAccessType.USER, SpaceAccessRole.ADMIN));

        when(spaceService.createWithAccess(any(), any()))
                .thenReturn(Uni.createFrom().item(created));

        given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"new-space\"}")
                .when().post("/matrix/v1/space/one")
                .then()
                .statusCode(200)
                .body("success", is(true))
                .body("data.name", is("new-space"))
                .body("data.accesses.size()", is(1));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void addOne_rootSpace_notAdmin_returnsError() {
        when(orgAccessService.checkAdmin("org-1", "test-user"))
                .thenReturn(Uni.createFrom().failure(
                        new IllegalArgumentException("Not an admin of the organization")));

        given()
                .header("X-Org-Id", "org-1")
                .contentType(ContentType.JSON)
                .body("{\"name\":\"new-space\"}")
                .when().post("/matrix/v1/space/one")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void addOne_childSpace_withoutOrgId_returnsError() {
        SpaceEntity parent = orgSpace("org-1");

        when(spaceService.getById("parent-id")).thenReturn(Uni.createFrom().item(parent));
        when(orgAccessService.checkSpaceAdmin(any(), isNull(), eq("test-user")))
                .thenReturn(Uni.createFrom().failure(new IllegalArgumentException("X-Org-Id header is required")));

        given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"child\",\"parentId\":\"parent-id\"}")
                .when().post("/matrix/v1/space/one")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void addOne_childSpaceWithoutParentAccess_returnsError() {
        when(spaceService.getById("parent-id")).thenReturn(Uni.createFrom().nullItem());

        given()
                .header("X-Org-Id", "org-1")
                .contentType(ContentType.JSON)
                .body("{\"name\":\"child\",\"parentId\":\"parent-id\"}")
                .when().post("/matrix/v1/space/one")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void deleteOne_removesSpace() {
        SpaceEntity space = orgSpace("org-1");
        space.id = new ObjectId("507f1f77bcf86cd799439011");

        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().item(space));
        when(orgAccessService.checkSpaceAdmin(any(), eq("org-1"), eq("test-user")))
                .thenReturn(Uni.createFrom().voidItem());
        when(spaceService.delete("sid"))
                .thenReturn(Uni.createFrom().item(true));

        given()
                .header("X-Org-Id", "org-1")
                .when().delete("/matrix/v1/space/one/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(true));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void deleteOne_withChildren_returnsError() {
        SpaceEntity space = orgSpace("org-1");

        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().item(space));
        when(orgAccessService.checkSpaceAdmin(any(), eq("org-1"), eq("test-user")))
                .thenReturn(Uni.createFrom().voidItem());
        when(spaceService.delete("sid"))
                .thenReturn(Uni.createFrom().failure(
                        new IllegalArgumentException("Cannot delete space with children")));

        given()
                .header("X-Org-Id", "org-1")
                .when().delete("/matrix/v1/space/one/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void updateOne_updatesSpace() {
        SpaceEntity space = orgSpace("org-1");
        space.id = new ObjectId("507f1f77bcf86cd799439011");

        when(spaceService.getById("507f1f77bcf86cd799439011")).thenReturn(Uni.createFrom().item(space));
        when(orgAccessService.checkSpaceAdmin(any(), eq("org-1"), eq("test-user")))
                .thenReturn(Uni.createFrom().voidItem());
        when(spaceService.update(any()))
                .thenReturn(Uni.createFrom().item(space));

        given()
                .header("X-Org-Id", "org-1")
                .contentType(ContentType.JSON)
                .body("{\"id\":\"507f1f77bcf86cd799439011\",\"name\":\"renamed\"}")
                .when().put("/matrix/v1/space/one")
                .then()
                .statusCode(200)
                .body("success", is(true));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void getTree_returnsDescendants() {
        SpaceEntity root = orgSpace("org-1");
        root.name = "root";

        when(spaceService.getById("root-id")).thenReturn(Uni.createFrom().item(root));
        when(orgAccessService.checkSpaceMember(any(), eq("org-1"), eq("test-user")))
                .thenReturn(Uni.createFrom().voidItem());
        when(spaceService.getDescendants("root-id"))
                .thenReturn(Uni.createFrom().item(List.of(root)));

        given()
                .header("X-Org-Id", "org-1")
                .when().get("/matrix/v1/space/tree/{rootId}", "root-id")
                .then()
                .statusCode(200)
                .body("success", is(true))
                .body("data.size()", is(1));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void getGraph_succeeds() {
        SpaceEntity root = orgSpace("org-1");
        root.id = new ObjectId();
        root.name = "root";

        when(spaceService.getById("root-id")).thenReturn(Uni.createFrom().item(root));
        when(orgAccessService.checkSpaceMember(any(), eq("org-1"), eq("test-user")))
                .thenReturn(Uni.createFrom().voidItem());
        when(spaceService.getDescendants("root-id"))
                .thenReturn(Uni.createFrom().item(List.of(root)));
        when(deviceService.getBySpaceId(any()))
                .thenReturn(Uni.createFrom().item(List.of()));

        given()
                .header("X-Org-Id", "org-1")
                .when().get("/matrix/v1/space/graph/{rootId}", "root-id")
                .then()
                .statusCode(200)
                .body("success", is(true))
                .body("data.spaces.size()", is(1))
                .body("data.devices.size()", is(0));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void updateAccessRole_succeeds() {
        SpaceEntity root = orgSpace("org-1");
        root.id = new ObjectId();

        when(spaceService.getById("root-id")).thenReturn(Uni.createFrom().item(root));
        when(orgAccessService.checkRootSpaceAdmin(any(), eq("test-user")))
                .thenReturn(Uni.createFrom().voidItem());
        when(spaceService.updateAccessRole("root-id", "m1", "admin"))
                .thenReturn(Uni.createFrom().voidItem());

        given()
                .header("X-Org-Id", "org-1")
                .contentType(ContentType.JSON)
                .body("{\"memberId\": \"m1\", \"role\": \"admin\"}")
                .when().put("/matrix/v1/space/{rootId}/access", "root-id")
                .then()
                .statusCode(200)
                .body("success", is(true));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void updateAccessRole_notAdmin_returnsError() {
        SpaceEntity root = orgSpace("org-1");
        root.id = new ObjectId();

        when(spaceService.getById("root-id")).thenReturn(Uni.createFrom().item(root));
        when(orgAccessService.checkRootSpaceAdmin(any(), eq("test-user")))
                .thenThrow(new IllegalArgumentException("No access"));

        given()
                .header("X-Org-Id", "org-1")
                .contentType(ContentType.JSON)
                .body("{\"memberId\": \"m1\", \"role\": \"admin\"}")
                .when().put("/matrix/v1/space/{rootId}/access", "root-id")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void updateAccessRole_missingMemberId_returnsError() {
        SpaceEntity root = orgSpace("org-1");
        root.id = new ObjectId();

        when(spaceService.getById("root-id")).thenReturn(Uni.createFrom().item(root));
        when(orgAccessService.checkRootSpaceAdmin(any(), eq("test-user")))
                .thenReturn(Uni.createFrom().voidItem());

        given()
                .header("X-Org-Id", "org-1")
                .contentType(ContentType.JSON)
                .body("{\"role\": \"admin\"}")
                .when().put("/matrix/v1/space/{rootId}/access", "root-id")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void updateAccessRemark_succeeds() {
        SpaceEntity root = orgSpace("org-1");
        root.id = new ObjectId();

        when(spaceService.getById("root-id")).thenReturn(Uni.createFrom().item(root));
        when(orgAccessService.checkRootSpaceAdmin(any(), eq("test-user")))
                .thenReturn(Uni.createFrom().voidItem());
        when(spaceService.updateAccessRemark("root-id", "m1", "对接人"))
                .thenReturn(Uni.createFrom().voidItem());

        given()
                .header("X-Org-Id", "org-1")
                .contentType(ContentType.JSON)
                .body("{\"memberId\": \"m1\", \"remark\": \"对接人\"}")
                .when().put("/matrix/v1/space/{rootId}/access", "root-id")
                .then()
                .statusCode(200)
                .body("success", is(true));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void removeAccess_selfExit_succeeds() {
        SpaceEntity root = orgSpace("org-1");
        root.id = new ObjectId();

        // memberId == 当前用户 → 走成员权限（checkRootSpaceMember）
        when(spaceService.getById("root-id")).thenReturn(Uni.createFrom().item(root));
        when(orgAccessService.checkRootSpaceMember(any(), eq("root-id"), eq("test-user")))
                .thenReturn(Uni.createFrom().voidItem());
        when(spaceService.removeAccess("root-id", "test-user"))
                .thenReturn(Uni.createFrom().voidItem());

        given()
                .header("X-Org-Id", "org-1")
                .queryParam("memberId", "test-user")
                .when().delete("/matrix/v1/space/{rootId}/access", "root-id")
                .then()
                .statusCode(200)
                .body("success", is(true));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void removeAccess_otherMember_requiresAdmin() {
        SpaceEntity root = orgSpace("org-1");
        root.id = new ObjectId();

        // memberId != 当前用户 → 走管理员权限
        when(spaceService.getById("root-id")).thenReturn(Uni.createFrom().item(root));
        when(orgAccessService.checkRootSpaceAdmin(any(), eq("test-user")))
                .thenThrow(new IllegalArgumentException("No access"));

        given()
                .header("X-Org-Id", "org-1")
                .queryParam("memberId", "m1")
                .when().delete("/matrix/v1/space/{rootId}/access", "root-id")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    /* ------------------------------------------------------------------------------------------
     * 3D 锚点
     * ------------------------------------------------------------------------------------------*/

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void setAnchor_succeeds() {
        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().item(orgSpace("org-1")));
        when(orgAccessService.checkSpaceAdmin(any(), eq("org-1"), eq("test-user")))
                .thenReturn(Uni.createFrom().voidItem());
        when(spaceService.setAnchor(eq("sid"), any()))
                .thenReturn(Uni.createFrom().voidItem());

        given()
                .contentType(ContentType.JSON)
                .header("X-Org-Id", "org-1")
                .body("{\"model\":\"001\",\"rev\":\"001.1\",\"x\":1.5,\"y\":2.25,\"z\":-3.0}")
                .when().put("/matrix/v1/space/one/{spaceId}/anchor", "sid")
                .then()
                .statusCode(200)
                .body("success", is(true));

        ArgumentCaptor<ModelAnchor> captor = ArgumentCaptor.forClass(ModelAnchor.class);
        verify(spaceService).setAnchor(eq("sid"), captor.capture());
        ModelAnchor sent = captor.getValue();
        assertEquals("001", sent.model);
        assertEquals("001.1", sent.rev);
        assertEquals(1.5, sent.x);
        assertEquals(2.25, sent.y);
        assertEquals(-3.0, sent.z);
        // 没传就是 null，不是 0 —— 0 会被当成「真的朝正北」，而「没朝向」和「朝北」是两回事
        assertNull(sent.ry);
    }

    /** model / rev 缺一不可：它们是判定锚点在模型重导出后是否还有效的唯一依据。 */
    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void setAnchor_missingRev_returnsError() {
        given()
                .contentType(ContentType.JSON)
                .header("X-Org-Id", "org-1")
                .body("{\"model\":\"001\",\"x\":1,\"y\":2,\"z\":3}")
                .when().put("/matrix/v1/space/one/{spaceId}/anchor", "sid")
                .then()
                .statusCode(200)
                .body("success", is(false));

        // 校验没过就不该继续往下走，更不该写库
        verify(spaceService, never()).setAnchor(anyString(), any());
    }

    /** 空白 rev 跟缺字段一样拦掉 —— 前端塞 `""` 比漏字段更常见。 */
    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void setAnchor_blankModel_returnsError() {
        given()
                .contentType(ContentType.JSON)
                .header("X-Org-Id", "org-1")
                .body("{\"model\":\"  \",\"rev\":\"001.1\",\"x\":1,\"y\":2,\"z\":3}")
                .when().put("/matrix/v1/space/one/{spaceId}/anchor", "sid")
                .then()
                .statusCode(200)
                .body("success", is(false));

        verify(spaceService, never()).setAnchor(anyString(), any());
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void setAnchor_spaceNotFound_returnsError() {
        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().nullItem());

        given()
                .contentType(ContentType.JSON)
                .header("X-Org-Id", "org-1")
                .body("{\"model\":\"001\",\"rev\":\"001.1\",\"x\":1,\"y\":2,\"z\":3}")
                .when().put("/matrix/v1/space/one/{spaceId}/anchor", "sid")
                .then()
                .statusCode(200)
                .body("success", is(false));

        verify(spaceService, never()).setAnchor(anyString(), any());
    }

    /** 不是管理员就写不了锚点（普通成员点「标注空间」会走到这里）。 */
    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void setAnchor_notAdmin_returnsError() {
        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().item(orgSpace("org-1")));
        when(orgAccessService.checkSpaceAdmin(any(), eq("org-1"), eq("test-user")))
                .thenReturn(Uni.createFrom().failure(new IllegalArgumentException("No access")));

        given()
                .contentType(ContentType.JSON)
                .header("X-Org-Id", "org-1")
                .body("{\"model\":\"001\",\"rev\":\"001.1\",\"x\":1,\"y\":2,\"z\":3}")
                .when().put("/matrix/v1/space/one/{spaceId}/anchor", "sid")
                .then()
                .statusCode(200)
                .body("success", is(false));

        verify(spaceService, never()).setAnchor(anyString(), any());
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void clearAnchor_succeeds() {
        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().item(orgSpace("org-1")));
        when(orgAccessService.checkSpaceAdmin(any(), eq("org-1"), eq("test-user")))
                .thenReturn(Uni.createFrom().voidItem());
        when(spaceService.clearAnchor("sid")).thenReturn(Uni.createFrom().voidItem());

        given()
                .header("X-Org-Id", "org-1")
                .when().delete("/matrix/v1/space/one/{spaceId}/anchor", "sid")
                .then()
                .statusCode(200)
                .body("success", is(true));

        verify(spaceService).clearAnchor("sid");
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void clearAnchor_spaceNotFound_returnsError() {
        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().nullItem());

        given()
                .header("X-Org-Id", "org-1")
                .when().delete("/matrix/v1/space/one/{spaceId}/anchor", "sid")
                .then()
                .statusCode(200)
                .body("success", is(false));

        verify(spaceService, never()).clearAnchor(anyString());
    }
}
