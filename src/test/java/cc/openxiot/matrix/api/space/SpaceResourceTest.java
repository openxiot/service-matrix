package cc.openxiot.matrix.api.space;

import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.device.DeviceService;
import cc.openxiot.matrix.service.orgcheck.OrgAccessService;
import cc.openxiot.matrix.db.space.SpaceEntity;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.smallrye.mutiny.Uni;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@QuarkusTest
class SpaceResourceTest {

    @InjectMock
    SpaceService spaceService;

    @InjectMock
    DeviceService deviceService;

    @InjectMock
    OrgAccessService orgAccessService;

    @Test
    void hello() {
        given()
                .when().get("/v1/space")
                .then()
                .statusCode(200)
                .body("success", is(true));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void getOne_returnsSpace() {
        SpaceEntity space = new SpaceEntity();
        space.name = "test-space";
        space.organizationId = "org-1";

        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().item(space));
        when(orgAccessService.checkMember("org-1", "test-user"))
                .thenReturn(Uni.createFrom().voidItem());

        given()
                .header("X-Org-Id", "org-1")
                .when().get("/v1/space/one/{spaceId}", "sid")
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
                .when().get("/v1/space/one/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void getOne_wrongOrgId_returnsError() {
        SpaceEntity space = new SpaceEntity();
        space.organizationId = "org-2";
        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().item(space));

        given()
                .header("X-Org-Id", "org-1")
                .when().get("/v1/space/one/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void getAll_returnsRoots() {
        SpaceEntity root = new SpaceEntity();
        root.name = "root";
        root.organizationId = "org-1";

        when(orgAccessService.checkMember("org-1", "test-user"))
                .thenReturn(Uni.createFrom().voidItem());
        when(spaceService.getRootsByOrganizationId("org-1"))
                .thenReturn(Uni.createFrom().item(List.of(root)));

        given()
                .header("X-Org-Id", "org-1")
                .when().get("/v1/space/all")
                .then()
                .statusCode(200)
                .body("success", is(true))
                .body("data.size()", is(1));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void getAll_missingOrgId_returnsError() {
        given()
                .when().get("/v1/space/all")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void getAll_notMember_returnsError() {
        when(orgAccessService.checkMember("org-1", "test-user"))
                .thenReturn(Uni.createFrom().failure(
                        new IllegalArgumentException("Not a member of the organization")));

        given()
                .header("X-Org-Id", "org-1")
                .when().get("/v1/space/all")
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
        when(spaceService.create(any()))
                .thenReturn(Uni.createFrom().item(created));

        given()
                .header("X-Org-Id", "org-1")
                .contentType(ContentType.JSON)
                .body("{\"name\":\"new-space\"}")
                .when().post("/v1/space/one")
                .then()
                .statusCode(200)
                .body("success", is(true))
                .body("data.name", is("new-space"));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void addOne_rootSpace_missingOrgId_returnsError() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"new-space\"}")
                .when().post("/v1/space/one")
                .then()
                .statusCode(200)
                .body("success", is(false));
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
                .when().post("/v1/space/one")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void addOne_childSpace_withoutOrgId_returnsError() {
        SpaceEntity parent = new SpaceEntity();
        parent.organizationId = "org-1";

        when(spaceService.getById("parent-id")).thenReturn(Uni.createFrom().item(parent));
        when(orgAccessService.checkAdmin("org-1", "test-user"))
                .thenReturn(Uni.createFrom().voidItem());

        given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"child\",\"parentId\":\"parent-id\"}")
                .when().post("/v1/space/one")
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
                .when().post("/v1/space/one")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void deleteOne_removesSpace() {
        SpaceEntity space = new SpaceEntity();
        space.organizationId = "org-1";

        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().item(space));
        when(orgAccessService.checkAdmin("org-1", "test-user"))
                .thenReturn(Uni.createFrom().voidItem());
        when(spaceService.delete("sid"))
                .thenReturn(Uni.createFrom().item(true));

        given()
                .header("X-Org-Id", "org-1")
                .when().delete("/v1/space/one/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(true));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void deleteOne_withChildren_returnsError() {
        SpaceEntity space = new SpaceEntity();
        space.organizationId = "org-1";

        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().item(space));
        when(orgAccessService.checkAdmin("org-1", "test-user"))
                .thenReturn(Uni.createFrom().voidItem());
        when(spaceService.delete("sid"))
                .thenReturn(Uni.createFrom().failure(
                        new IllegalArgumentException("Cannot delete space with children")));

        given()
                .header("X-Org-Id", "org-1")
                .when().delete("/v1/space/one/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void updateOne_updatesSpace() {
        SpaceEntity space = new SpaceEntity();
        space.organizationId = "org-1";

        when(spaceService.getById("507f1f77bcf86cd799439011")).thenReturn(Uni.createFrom().item(space));
        when(orgAccessService.checkAdmin("org-1", "test-user"))
                .thenReturn(Uni.createFrom().voidItem());
        when(spaceService.update(any()))
                .thenReturn(Uni.createFrom().item(space));

        given()
                .header("X-Org-Id", "org-1")
                .contentType(ContentType.JSON)
                .body("{\"id\":\"507f1f77bcf86cd799439011\",\"name\":\"renamed\"}")
                .when().put("/v1/space/one")
                .then()
                .statusCode(200)
                .body("success", is(true));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void getTree_returnsDescendants() {
        SpaceEntity root = new SpaceEntity();
        root.name = "root";
        root.organizationId = "org-1";

        when(spaceService.getById("root-id")).thenReturn(Uni.createFrom().item(root));
        when(orgAccessService.checkMember("org-1", "test-user"))
                .thenReturn(Uni.createFrom().voidItem());
        when(spaceService.getDescendants("root-id"))
                .thenReturn(Uni.createFrom().item(List.of(root)));

        given()
                .header("X-Org-Id", "org-1")
                .when().get("/v1/space/tree/{rootId}", "root-id")
                .then()
                .statusCode(200)
                .body("success", is(true))
                .body("data.size()", is(1));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void getGraph_succeeds() {
        SpaceEntity root = new SpaceEntity();
        root.id = new ObjectId();
        root.name = "root";
        root.organizationId = "org-1";

        when(spaceService.getById("root-id")).thenReturn(Uni.createFrom().item(root));
        when(orgAccessService.checkMember("org-1", "test-user"))
                .thenReturn(Uni.createFrom().voidItem());
        when(spaceService.getDescendants("root-id"))
                .thenReturn(Uni.createFrom().item(List.of(root)));
        when(deviceService.getBySpaceId(any()))
                .thenReturn(Uni.createFrom().item(List.of()));

        given()
                .header("X-Org-Id", "org-1")
                .when().get("/v1/space/graph/{rootId}", "root-id")
                .then()
                .statusCode(200)
                .body("success", is(true))
                .body("data.spaces.size()", is(1))
                .body("data.devices.size()", is(0));
    }
}
