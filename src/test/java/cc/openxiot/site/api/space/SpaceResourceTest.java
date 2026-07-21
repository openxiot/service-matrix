package cc.openxiot.site.api.space;

import cc.openxiot.common.role.OxRole;
import cc.openxiot.site.api.device.DeviceService;
import cc.openxiot.site.db.device.DeviceEntity;
import cc.openxiot.site.db.space.SpaceEntity;
import cc.openxiot.site.db.space.SpaceUser;
import cc.openxiot.site.role.SiteRole;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@QuarkusTest
class SpaceResourceTest {

    @InjectMock
    SpaceService spaceService;

    @InjectMock
    DeviceService deviceService;

    @Test
    void hello() {
        given()
                .when().get("/v1/space")
                .then()
                .statusCode(200)
                .body("success", is(true));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.SITE_MANAGER})
    void getOne_returnsSpace() {
        SpaceEntity space = new SpaceEntity();
        space.name = "test-space";
        space.users = List.of(user("test-user"));

        when(spaceService.noAccess(eq("sid"), any()))
                .thenReturn(Uni.createFrom().item(false));
        when(spaceService.getById("sid"))
                .thenReturn(Uni.createFrom().item(space));

        given()
                .when().get("/v1/space/one/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(true))
                .body("data.name", is("test-space"));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.SITE_MANAGER})
    void getOne_notFound_returnsError() {
        when(spaceService.noAccess(eq("sid"), any()))
                .thenReturn(Uni.createFrom().item(false));
        when(spaceService.getById("sid"))
                .thenReturn(Uni.createFrom().nullItem());

        given()
                .when().get("/v1/space/one/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.SITE_MANAGER})
    void getAll_returnsRoots() {
        SpaceEntity root = new SpaceEntity();
        root.name = "root";
        when(spaceService.getRootsByUserId("test-user"))
                .thenReturn(Uni.createFrom().item(List.of(root)));

        given()
                .when().get("/v1/space/all")
                .then()
                .statusCode(200)
                .body("success", is(true))
                .body("data.size()", is(1));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.SITE_MANAGER})
    void addOne_rootSpace_returnsCreated() {
        SpaceEntity input = new SpaceEntity();
        input.name = "new-space";

        SpaceEntity created = new SpaceEntity();
        created.name = "new-space";
        created.level = 0;

        when(spaceService.create(any(), eq("test-user")))
                .thenReturn(Uni.createFrom().item(created));

        given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"new-space\"}")
                .when().post("/v1/space/one")
                .then()
                .statusCode(200)
                .body("success", is(true))
                .body("data.name", is("new-space"));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.SITE_MANAGER})
    void addOne_childSpaceWithoutParentAccess_returnsError() {
        when(spaceService.noAccess(eq("parent-id"), any()))
                .thenReturn(Uni.createFrom().item(true));

        given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"child\",\"parentId\":\"parent-id\"}")
                .when().post("/v1/space/one")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.SITE_MANAGER})
    void deleteOne_removesSpace() {
        when(spaceService.noAccess(eq("sid"), any()))
                .thenReturn(Uni.createFrom().item(false));
        when(spaceService.delete("sid"))
                .thenReturn(Uni.createFrom().item(true));

        given()
                .when().delete("/v1/space/one/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(true));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.SITE_MANAGER})
    void deleteOne_withChildren_returnsError() {
        when(spaceService.noAccess(eq("sid"), any()))
                .thenReturn(Uni.createFrom().item(false));
        when(spaceService.delete("sid"))
                .thenReturn(Uni.createFrom().failure(
                        new IllegalArgumentException("Cannot delete space with children")));

        given()
                .when().delete("/v1/space/one/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.SITE_MANAGER})
    void updateOne_updatesSpace() {
        SpaceEntity updated = new SpaceEntity();
        updated.name = "renamed";

        when(spaceService.noAccess(any(), any()))
                .thenReturn(Uni.createFrom().item(false));
        when(spaceService.update(any()))
                .thenReturn(Uni.createFrom().item(updated));

        given()
                .contentType(ContentType.JSON)
                .body("{\"id\":\"507f1f77bcf86cd799439011\",\"name\":\"renamed\"}")
                .when().put("/v1/space/one")
                .then()
                .statusCode(200)
                .body("success", is(true));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.SITE_MANAGER})
    void getTree_returnsDescendants() {
        SpaceEntity root = new SpaceEntity();
        root.name = "root";
        when(spaceService.noAccess(eq("root-id"), any()))
                .thenReturn(Uni.createFrom().item(false));
        when(spaceService.getDescendants("root-id"))
                .thenReturn(Uni.createFrom().item(List.of(root)));

        given()
                .when().get("/v1/space/tree/{rootId}", "root-id")
                .then()
                .statusCode(200)
                .body("success", is(true))
                .body("data.size()", is(1));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.SITE_MANAGER})
    void getGraph_succeeds() {
        SpaceEntity root = new SpaceEntity();
        root.id = new ObjectId();
        root.name = "root";
        when(spaceService.noAccess(eq("root-id"), any()))
                .thenReturn(Uni.createFrom().item(false));
        when(spaceService.getDescendants("root-id"))
                .thenReturn(Uni.createFrom().item(List.of(root)));
        when(deviceService.getBySpaceId(any()))
                .thenReturn(Uni.createFrom().item(List.of()));

        given()
                .when().get("/v1/space/graph/{rootId}", "root-id")
                .then()
                .statusCode(200)
                .body("success", is(true))
                .body("data.spaces.size()", is(1))
                .body("data.devices.size()", is(0));
    }

    private static SpaceUser user(String id) {
        SpaceUser u = new SpaceUser();
        u.userId = id;
        return u;
    }
}
