package cc.openxiot.matrix.api.card.device;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.card.device.params.DeviceCardDto;
import cc.openxiot.matrix.api.space.SpaceService;
import cc.openxiot.matrix.db.card.device.DeviceCard;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.matrix.service.orgcheck.OrgAccessService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

/**
 * 设备卡片配置接口的**读写不对称**：读是空间成员、写是空间管理员（改配置影响所有成员）。
 *
 * <p>全部走 {@code @InjectMock}，不连 Mongo：校验与落库由 {@link DeviceCardServiceTest} 覆盖，
 * 这里只钉「谁被允许做什么」「未配置与配置成空分得开」「请求体有没有转给服务」。</p>
 */
@QuarkusTest
class DeviceCardResourceTest {

    private static final String SPACE_ID = "6512f3a4b5c6d7e8f9a0b1c3";
    private static final String DID = "did-1";

    @InjectMock
    SpaceService spaceService;

    @InjectMock
    OrgAccessService orgAccessService;

    @InjectMock
    DeviceCardService cardService;

    private SpaceEntity space() {
        SpaceEntity s = new SpaceEntity();
        s.id = new ObjectId(SPACE_ID);
        s.name = "测试项目";
        return s;
    }

    private void allowSpaceMember() {
        when(spaceService.getById(SPACE_ID)).thenReturn(Uni.createFrom().item(space()));
        when(orgAccessService.checkSpaceMember(any(), eq("tester"))).thenReturn(Uni.createFrom().voidItem());
    }

    private void allowSpaceAdmin() {
        when(spaceService.getById(SPACE_ID)).thenReturn(Uni.createFrom().item(space()));
        when(orgAccessService.checkSpaceAdmin(any(), eq("tester"))).thenReturn(Uni.createFrom().voidItem());
    }

    private void denySpaceAdmin() {
        when(spaceService.getById(SPACE_ID)).thenReturn(Uni.createFrom().item(space()));
        when(orgAccessService.checkSpaceAdmin(any(), anyString()))
                .thenReturn(Uni.createFrom().failure(new IllegalArgumentException("Not an admin of the space")));
    }

    private static DeviceCard card(int siid, List<Integer> piids) {
        DeviceCard card = new DeviceCard();
        card.spaceId = SPACE_ID;
        card.did = DID;
        DeviceCard.ServiceSelection selection = new DeviceCard.ServiceSelection();
        selection.siid = siid;
        selection.piids = piids;
        card.services = List.of(selection);
        return card;
    }

    // ===== GET：空间成员 =====

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void list_spaceMember_getsConfiguredCards() {
        allowSpaceMember();
        when(cardService.list(any())).thenReturn(Uni.createFrom().item(List.of(card(1, List.of(1, 2)))));

        given()
                .when().get("/matrix/v1/card/device/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.spaceId", equalTo(SPACE_ID))
                .body("data.items.size()", equalTo(1))
                .body("data.items[0].did", equalTo(DID))
                .body("data.items[0].customized", equalTo(true))
                .body("data.items[0].services[0].siid", equalTo(1))
                .body("data.items[0].services[0].piids.size()", equalTo(2));
    }

    /**
     * 没配置过**不报 404**，也**不补一个空的 services**：前端要靠 {@code customized:false}
     * 区分「按缺省 2×3 摆」与「用户清空了（什么都不摆）」。
     */
    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void get_notConfigured_returnsCustomizedFalseWithoutServices() {
        allowSpaceMember();
        when(cardService.load(any(), eq(DID))).thenReturn(Uni.createFrom().nullItem());

        given()
                .when().get("/matrix/v1/card/device/{spaceId}/{did}", SPACE_ID, DID)
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.did", equalTo(DID))
                .body("data.customized", equalTo(false))
                .body("data", not(hasKey("services")));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void list_nonMember_denied() {
        when(spaceService.getById(SPACE_ID)).thenReturn(Uni.createFrom().item(space()));
        when(orgAccessService.checkSpaceMember(any(), anyString()))
                .thenReturn(Uni.createFrom().failure(new IllegalArgumentException("No access to this space")));

        given()
                .when().get("/matrix/v1/card/device/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(false));
    }

    // ===== PUT / DELETE：空间管理员 =====

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void save_member_denied() {
        denySpaceAdmin();

        given().contentType("application/json")
                .body("{\"services\":[{\"siid\":1,\"piids\":[1]}]}")
                .when().put("/matrix/v1/card/device/{spaceId}/{did}", SPACE_ID, DID)
                .then().statusCode(200)
                .body("success", equalTo(false));

        verify(cardService, never()).save(any(), anyString(), any(), any());
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void save_admin_passesBodyToService() {
        allowSpaceAdmin();
        when(cardService.save(any(), eq(DID), any(), any()))
                .thenReturn(Uni.createFrom().item(card(1, List.of(1, 3))));

        given().contentType("application/json")
                .body("{\"services\":[{\"siid\":1,\"piids\":[1,3]}]}")
                .when().put("/matrix/v1/card/device/{spaceId}/{did}", SPACE_ID, DID)
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.customized", equalTo(true))
                .body("data.services[0].piids[1]", equalTo(3));

        ArgumentCaptor<DeviceCardDto> body = ArgumentCaptor.forClass(DeviceCardDto.class);
        verify(cardService).save(any(), eq(DID), body.capture(), any());
        org.junit.jupiter.api.Assertions.assertEquals(1, body.getValue().services.size());
        org.junit.jupiter.api.Assertions.assertEquals(1, body.getValue().services.get(0).siid);
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void reset_admin_returnsCustomizedFalse() {
        allowSpaceAdmin();
        when(cardService.reset(any(), eq(DID))).thenReturn(Uni.createFrom().voidItem());

        given()
                .when().delete("/matrix/v1/card/device/{spaceId}/{did}", SPACE_ID, DID)
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.customized", equalTo(false));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void save_deviceNotInProject_returnsError() {
        allowSpaceAdmin();
        when(cardService.save(any(), eq(DID), any(), any()))
                .thenReturn(Uni.createFrom().failure(
                        new IllegalArgumentException("Device not found in this project: " + DID)));

        given().contentType("application/json")
                .body(new JsonObject().put("services", new io.vertx.core.json.JsonArray()).encode())
                .when().put("/matrix/v1/card/device/{spaceId}/{did}", SPACE_ID, DID)
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", equalTo("Device not found in this project: " + DID));
    }
}
