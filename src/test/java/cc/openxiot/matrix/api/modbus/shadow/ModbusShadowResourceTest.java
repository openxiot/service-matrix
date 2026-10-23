package cc.openxiot.matrix.api.modbus.shadow;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.modbus.service.ModbusServiceService;
import cc.openxiot.matrix.api.space.SpaceService;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.matrix.db.space.access.SpaceAccess;
import cc.openxiot.matrix.db.space.access.SpaceAccessRole;
import cc.openxiot.matrix.db.space.access.SpaceAccessType;
import cc.openxiot.matrix.service.orgcheck.OrgAccessService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

/**
 * Modbus 影子（寄存器缓存网格）接口的空间口径：路径上的 {@code spaceId} 是鉴权锚点，
 * 查询要该空间的**成员**（与 {@code ModbusServiceResource} / {@code DeviceResource} 同口径）。
 *
 * <p>全部走 {@code @InjectMock}，不连 Mongo。</p>
 */
@QuarkusTest
class ModbusShadowResourceTest {

    private static final String SPACE_ID = "6512f3a4b5c6d7e8f9a0b1c3";
    private static final String SVC1 = "65f0c0ffee0123456789abcd";
    private static final String SVC2 = "65f0c0ffee0123456789abcf";

    @InjectMock
    ModbusShadowService shadowService;

    @InjectMock
    ModbusServiceService service;

    @InjectMock
    OrgAccessService orgAccessService;

    @InjectMock
    SpaceService spaceService;

    private SpaceEntity space() {
        SpaceEntity s = new SpaceEntity();
        s.id = new ObjectId(SPACE_ID);
        s.name = "测试项目";
        s.accesses = List.of(new SpaceAccess("org1", SpaceAccessType.ORGANIZATION, SpaceAccessRole.MEMBER));
        return s;
    }

    private void allowSpaceMember() {
        when(spaceService.getById(SPACE_ID)).thenReturn(Uni.createFrom().item(space()));
        when(orgAccessService.checkSpaceMember(any(), eq("tester")))
                .thenReturn(Uni.createFrom().voidItem());
    }

    private void denySpaceMember() {
        when(spaceService.getById(SPACE_ID)).thenReturn(Uni.createFrom().item(space()));
        when(orgAccessService.checkSpaceMember(any(), any()))
                .thenReturn(Uni.createFrom().failure(new IllegalArgumentException("Not a member of the space")));
    }

    /** 一个服务一张网格：进水温度 current，压力 no-data（寄存器条目键序同 {@code /shadows} 编码：name/area?/unit?/access/pollingIndex?/value?/status）。 */
    private Map<String, List<JsonObject>> grid() {
        JsonObject temp = new JsonObject()
                .put("name", "进水温度")
                .put("area", "holding")
                .put("access", "R")
                .put("pollingIndex", 1)
                .put("value", 61.5)
                .put("status", "current");
        JsonObject pressure = new JsonObject()
                .put("name", "压力")
                .put("area", "input")
                .put("access", "R")
                .put("status", "no-data");
        return Map.of(SVC1, List.of(temp, pressure));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void getShadows_withServiceIds_returnsGrid() {
        allowSpaceMember();
        when(shadowService.registerCaches(List.of(SVC1, SVC2))).thenReturn(Uni.createFrom().item(grid()));

        given()
                .queryParam("serviceIds", SVC1 + "," + SVC2)
                .when().get("/matrix/v1/modbus/shadows/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.shadows['" + SVC1 + "'].serviceId", equalTo(SVC1))
                .body("data.shadows['" + SVC1 + "'].registers.size()", equalTo(2))
                .body("data.shadows['" + SVC1 + "'].registers[0].name", equalTo("进水温度"))
                .body("data.shadows['" + SVC1 + "'].registers[0].value", equalTo(61.5f))
                .body("data.shadows['" + SVC1 + "'].registers[0].status", equalTo("current"))
                .body("data.shadows['" + SVC1 + "'].registers[1].status", equalTo("no-data"));

        verify(shadowService).registerCaches(List.of(SVC1, SVC2));
        verify(service, never()).listBySpaceId(any());
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void getShadows_noServiceIds_resolvesAllBySpace() {
        allowSpaceMember();
        ModbusService s = new ModbusService();
        s.id = new ObjectId(SVC1);
        when(service.listBySpaceId(SPACE_ID)).thenReturn(Uni.createFrom().item(List.of(s)));
        when(shadowService.registerCaches(List.of(SVC1))).thenReturn(Uni.createFrom().item(grid()));

        given()
                .when().get("/matrix/v1/modbus/shadows/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.shadows['" + SVC1 + "'].serviceId", equalTo(SVC1));

        verify(service).listBySpaceId(SPACE_ID);
        verify(shadowService).registerCaches(List.of(SVC1));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void getShadows_notMember_returnsError() {
        denySpaceMember();

        given()
                .when().get("/matrix/v1/modbus/shadows/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(false));

        verify(shadowService, never()).registerCaches(any());
    }
}