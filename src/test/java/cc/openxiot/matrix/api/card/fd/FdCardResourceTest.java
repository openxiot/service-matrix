package cc.openxiot.matrix.api.card.fd;

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
import cc.openxiot.matrix.api.card.fd.params.FdCardDto;
import cc.openxiot.matrix.api.space.SpaceService;
import cc.openxiot.matrix.db.card.fd.FdCard;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.matrix.service.orgcheck.OrgAccessService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

/**
 * 现场设备卡片配置接口：读是空间成员、写是空间管理员；未配置与配置成空分得开。
 *
 * <p>与 {@code DeviceCardResourceTest} 分开：两者的接口、请求体、校验规则都不同，
 * 合并测试只会让「到底测的是哪一种卡片」看不出来。</p>
 */
@QuarkusTest
class FdCardResourceTest {

    private static final String SPACE_ID = "6512f3a4b5c6d7e8f9a0b1c3";
    private static final String FDID = "65f0c0ffee0123456789abcd";

    @InjectMock
    SpaceService spaceService;

    @InjectMock
    OrgAccessService orgAccessService;

    @InjectMock
    FdCardService cardService;

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

    private static FdCard card(List<Integer> registers) {
        FdCard card = new FdCard();
        card.spaceId = SPACE_ID;
        card.fdid = FDID;
        card.registers = registers;
        return card;
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void list_spaceMember_getsConfiguredCards() {
        allowSpaceMember();
        when(cardService.list(any())).thenReturn(Uni.createFrom().item(List.of(card(List.of(1, 2, 3)))));

        given()
                .when().get("/matrix/v1/card/fd/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.spaceId", equalTo(SPACE_ID))
                .body("data.items.size()", equalTo(1))
                .body("data.items[0].fdid", equalTo(FDID))
                .body("data.items[0].customized", equalTo(true))
                .body("data.items[0].registers.size()", equalTo(3));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void get_notConfigured_returnsCustomizedFalseWithoutRegisters() {
        allowSpaceMember();
        when(cardService.load(any(), eq(FDID))).thenReturn(Uni.createFrom().nullItem());

        given()
                .when().get("/matrix/v1/card/fd/{spaceId}/{fdid}", SPACE_ID, FDID)
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.fdid", equalTo(FDID))
                .body("data.customized", equalTo(false))
                .body("data", not(hasKey("registers")));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void save_member_denied() {
        denySpaceAdmin();

        given().contentType("application/json")
                .body("{\"registers\":[1,2]}")
                .when().put("/matrix/v1/card/fd/{spaceId}/{fdid}", SPACE_ID, FDID)
                .then().statusCode(200)
                .body("success", equalTo(false));

        verify(cardService, never()).save(any(), anyString(), any(), any());
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void save_admin_passesRegistersToService() {
        allowSpaceAdmin();
        when(cardService.save(any(), eq(FDID), any(), any()))
                .thenReturn(Uni.createFrom().item(card(List.of(3, 1))));

        given().contentType("application/json")
                .body("{\"registers\":[3,1]}")
                .when().put("/matrix/v1/card/fd/{spaceId}/{fdid}", SPACE_ID, FDID)
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.registers[0]", equalTo(3));

        ArgumentCaptor<FdCardDto> body = ArgumentCaptor.forClass(FdCardDto.class);
        verify(cardService).save(any(), eq(FDID), body.capture(), any());
        Assertions.assertEquals(List.of(3, 1), body.getValue().registers);
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void save_invalidRegister_returnsError() {
        allowSpaceAdmin();
        when(cardService.save(any(), eq(FDID), any(), any()))
                .thenReturn(Uni.createFrom().failure(
                        new IllegalArgumentException("register 9 is not a readable register of this field device")));

        given().contentType("application/json")
                .body(new JsonObject().put("registers", new JsonArray().add(9)).encode())
                .when().put("/matrix/v1/card/fd/{spaceId}/{fdid}", SPACE_ID, FDID)
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", equalTo("register 9 is not a readable register of this field device"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void reset_admin_returnsCustomizedFalse() {
        allowSpaceAdmin();
        when(cardService.reset(any(), eq(FDID))).thenReturn(Uni.createFrom().voidItem());

        given()
                .when().delete("/matrix/v1/card/fd/{spaceId}/{fdid}", SPACE_ID, FDID)
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.customized", equalTo(false));
    }
}
