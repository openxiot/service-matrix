package cc.openxiot.matrix.api.energy;

import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.space.SpaceService;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.matrix.service.orgcheck.OrgAccessService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.smallrye.mutiny.Uni;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** 摘要接口的鉴权口径：成员可读，非成员 / 不存在的空间都是 HTTP 200 + success:false。 */
@QuarkusTest
class EnergySummaryResourceTest {

    @InjectMock
    EnergySummaryService summaryService;

    @InjectMock
    SpaceService spaceService;

    @InjectMock
    OrgAccessService orgAccessService;

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void memberGetsSummary() {
        SpaceEntity space = new SpaceEntity();
        space.id = new ObjectId();
        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().item(space));
        when(orgAccessService.checkSpaceMember(any(), eq("test-user")))
                .thenReturn(Uni.createFrom().voidItem());
        when(summaryService.summary(eq(space.id.toString()), any(), any(), any()))
                .thenReturn(Uni.createFrom().item(summary()));

        given()
                .queryParam("from", 1_704_038_400_000L)
                .queryParam("to", 1_704_045_600_000L)
                .queryParam("zone", "Asia/Shanghai")
                .when().get("/matrix/v1/energy/summary/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(true))
                .body("data.totals[0].key", is("SOLAR"))
                .body("data.granularity", is("HOUR"));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void nonMemberGetsError() {
        SpaceEntity space = new SpaceEntity();
        space.id = new ObjectId();
        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().item(space));
        when(orgAccessService.checkSpaceMember(any(), eq("test-user")))
                .thenReturn(Uni.createFrom().failure(new IllegalArgumentException("Not a member")));

        given()
                .queryParam("from", 1L)
                .when().get("/matrix/v1/energy/summary/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void unknownSpaceGetsError() {
        when(spaceService.getById("sid")).thenReturn(Uni.createFrom().nullItem());

        given()
                .queryParam("from", 1L)
                .when().get("/matrix/v1/energy/summary/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    @Test
    @TestSecurity(user = "test-user", roles = {OxRole.USER})
    void invalidZoneGetsError() {
        given()
                .queryParam("from", 1L)
                .queryParam("zone", "Not/AZone")
                .when().get("/matrix/v1/energy/summary/{spaceId}", "sid")
                .then()
                .statusCode(200)
                .body("success", is(false));
    }

    private static EnergySummary summary() {
        return new EnergySummary(
                "sid",
                1_704_038_400_000L,
                1_704_045_600_000L,
                "Asia/Shanghai",
                EnergyGranularity.HOUR,
                List.of(
                        new EnergySummary.Total(EnergySummary.Key.SOLAR, 1.0, "kWh", null),
                        new EnergySummary.Total(EnergySummary.Key.BATTERY, null, "kWh", null),
                        new EnergySummary.Total(EnergySummary.Key.GRID, 2.0, "kWh", null),
                        new EnergySummary.Total(EnergySummary.Key.GAS, null, "m³", null),
                        new EnergySummary.Total(EnergySummary.Key.WATER, null, "m³", null)),
                new EnergySummary.Series(List.of(), List.of(), List.of(), List.of(), List.of()),
                null,
                null,
                null);
    }
}
