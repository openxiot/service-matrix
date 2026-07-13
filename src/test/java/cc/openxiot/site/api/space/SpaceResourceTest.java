package cc.openxiot.site.api.space;

import cc.openxiot.common.response.OxData;
import cc.openxiot.common.response.OxError;
import cc.openxiot.site.api.device.DeviceService;
import cc.openxiot.site.db.space.SpaceEntity;
import cc.openxiot.site.db.space.SpaceUser;
import cc.openxiot.site.role.SiteRole;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.common.mapper.TypeRef;
import io.restassured.http.ContentType;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

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
    @TestSecurity(user = "test-user", roles = {SiteRole.MANAGER})
    void getOne_returnsSpace() {
        SpaceEntity space = new SpaceEntity();
        space.name = "test-space";
        space.users = List.of(user("test-user"));

        when(spaceService.noAccess(eq("sid"), any())).thenReturn(false);
        when(spaceService.getById("sid")).thenReturn(space);

        given()
                .when().get("/v1/space/one/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(true))
                .body("data.name", is("test-space"));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {SiteRole.MANAGER})
    void getOne_notFound_returnsError() {
        when(spaceService.noAccess(eq("sid"), any())).thenReturn(false);
        when(spaceService.getById("sid")).thenReturn(null);

        given()
                .when().get("/v1/space/one/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {SiteRole.MANAGER})
    void getAll_returnsRoots() {
        SpaceEntity root = new SpaceEntity();
        root.name = "root";
        when(spaceService.getRootsByUserId("test-user")).thenReturn(List.of(root));

        given()
                .when().get("/v1/space/all")
                .then()
                .statusCode(200)
                .body("success", is(true))
                .body("data.size()", is(1));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {SiteRole.MANAGER})
    void addOne_rootSpace_returnsCreated() {
        SpaceEntity input = new SpaceEntity();
        input.name = "new-space";

        SpaceEntity created = new SpaceEntity();
        created.name = "new-space";
        created.level = 0;

        when(spaceService.create(any(), eq("test-user"))).thenReturn(created);

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
    @TestSecurity(user = "test-user", roles = {SiteRole.MANAGER})
    void addOne_childSpaceWithoutParentAccess_returnsError() {
        when(spaceService.noAccess(eq("parent-id"), any())).thenReturn(true);

        given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"child\",\"parentId\":\"parent-id\"}")
                .when().post("/v1/space/one")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {SiteRole.MANAGER})
    void deleteOne_removesSpace() {
        when(spaceService.noAccess(eq("sid"), any())).thenReturn(false);
        doNothing().when(spaceService).delete("sid");

        given()
                .when().delete("/v1/space/one/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(true));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {SiteRole.MANAGER})
    void deleteOne_withChildren_returnsError() {
        when(spaceService.noAccess(eq("sid"), any())).thenReturn(false);
        doThrow(new IllegalArgumentException("Cannot delete space with children"))
                .when(spaceService).delete("sid");

        given()
                .when().delete("/v1/space/one/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {SiteRole.MANAGER})
    void updateOne_updatesSpace() {
        SpaceEntity updated = new SpaceEntity();
        updated.name = "renamed";

        when(spaceService.noAccess(any(), any())).thenReturn(false);
        when(spaceService.update(any())).thenReturn(updated);

        given()
                .contentType(ContentType.JSON)
                .body("{\"id\":\"507f1f77bcf86cd799439011\",\"name\":\"renamed\"}")
                .when().put("/v1/space/one")
                .then()
                .statusCode(200)
                .body("success", is(true));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {SiteRole.MANAGER})
    void getTree_returnsDescendants() {
        SpaceEntity root = new SpaceEntity();
        root.name = "root";
        when(spaceService.noAccess(eq("root-id"), any())).thenReturn(false);
        when(spaceService.getDescendants("root-id")).thenReturn(List.of(root));

        given()
                .when().get("/v1/space/tree/{rootId}", "root-id")
                .then()
                .statusCode(200)
                .body("success", is(true))
                .body("data.size()", is(1));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {SiteRole.MANAGER})
    void getGraph_succeeds() {
        SpaceEntity root = new SpaceEntity();
        root.id = new ObjectId();
        root.name = "root";
        when(spaceService.noAccess(eq("root-id"), any())).thenReturn(false);
        when(spaceService.getDescendants("root-id")).thenReturn(List.of(root));
        when(deviceService.getBySpaceId(any())).thenReturn(List.of());

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
