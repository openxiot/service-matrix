package cc.openxiot.matrix.api.dashboard.mobile;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cc.openxiot.common.person.Person;
import cc.openxiot.common.role.OxRole;
import cc.openxiot.matrix.api.dashboard.mobile.layout.MobileDashboardLayoutCodec;
import cc.openxiot.matrix.api.dashboard.mobile.layout.MobileDashboardLayoutService;
import cc.openxiot.matrix.api.dashboard.mobile.layout.MobileDashboardLayoutValidator;
import cc.openxiot.matrix.api.dashboard.mobile.layout.MobileDashboardPresetLayouts;
import cc.openxiot.matrix.api.dashboard.mobile.params.MobileDashboardLayoutDto;
import cc.openxiot.matrix.api.dashboard.widget.DashboardWidgetDataService;
import cc.openxiot.matrix.api.dashboard.widget.DashboardWidgetStructureValidator;
import cc.openxiot.matrix.api.space.SpaceService;
import cc.openxiot.matrix.db.dashboard.mobile.MobileDashboardWidget;
import cc.openxiot.matrix.db.dashboard.mobile.MobileDashboardLayoutEntity;
import cc.openxiot.matrix.db.dashboard.mobile.widget.MobileDashboardWidgetSide;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.matrix.service.orgcheck.OrgAccessService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 移动端看板接口：与 Web 同一套权限口径（读是空间成员、写是空间管理员），但<b>布局是独立的
 * 一份</b>（竖排堆叠、FULL/HALF 档位、无坐标），也<b>没有 catalog</b>（移动端 app 不在这仓库）。
 *
 * <p>全部走 {@code @InjectMock}，不连 Mongo。校验器/服务的行为由它们自己的测试覆盖，
 * 这里只钉「谁被允许做什么」和「请求体有没有被正确地转给服务」。</p>
 */
@QuarkusTest
class MobileDashboardResourceTest {

    private static final String SPACE_ID = "6512f3a4b5c6d7e8f9a0b1c3";

    @InjectMock
    SpaceService spaceService;

    @InjectMock
    OrgAccessService orgAccessService;

    @InjectMock
    MobileDashboardLayoutService layoutService;

    @InjectMock
    MobileDashboardLayoutValidator layoutValidator;

    @InjectMock
    DashboardWidgetDataService widgetDataService;

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

    private void spaceNotFound() {
        when(spaceService.getById(anyString())).thenReturn(Uni.createFrom().item((SpaceEntity) null));
    }

    // ===== GET /layout：空间成员（移动端预置是 6 张竖排卡） =====

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void layout_spaceMember_getsPreset() {
        allowSpaceMember();
        when(layoutService.load(any())).thenReturn(Uni.createFrom().item(MobileDashboardPresetLayouts.build(SPACE_ID)));

        given()
                .when().get("/matrix/v1/dashboard/mobile/layout/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.spaceId", equalTo(SPACE_ID))
                // 预置布局版本号 0 = 「首次保存要提交的版本号」
                .body("data.version", equalTo(0))
                .body("data.widgets.size()", equalTo(6));
    }

    /** 移动端布局**不下发坐标**：竖排堆叠，位置真值就是数组顺序。 */
    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void layout_widgetsCarryNoCoordinates() {
        allowSpaceMember();
        when(layoutService.load(any())).thenReturn(Uni.createFrom().item(MobileDashboardPresetLayouts.build(SPACE_ID)));

        given()
                .when().get("/matrix/v1/dashboard/mobile/layout/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.widgets[0].size", equalTo("HALF"))
                .body("data.widgets[0].containsKey(\"x\")", equalTo(false))
                .body("data.widgets[0].containsKey(\"y\")", equalTo(false));
    }

    /**
     * 缺省的半格由**服务端**推出来再下发：预置那两张 HALF 没写 side，读出来就是 {@code LEFT}/{@code RIGHT}
     * （并排的老样子）。客户端因此不必自己推 —— 自己推就会在拖走一张后把同伴横着翻面。
     */
    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void layout_derivesMissingSide() {
        allowSpaceMember();
        when(layoutService.load(any())).thenReturn(Uni.createFrom().item(MobileDashboardPresetLayouts.build(SPACE_ID)));

        given()
                .when().get("/matrix/v1/dashboard/mobile/layout/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("data.widgets[0].side", equalTo("LEFT"))
                .body("data.widgets[1].side", equalTo("RIGHT"))
                // 整宽卡没有半格：键都不出现
                .body("data.widgets[4].containsKey(\"side\")", equalTo(false));
    }

    /** 存了显式 side 的布局读回来原样下发、不被重推：单独一张 {@code RIGHT} = 右半格有卡、左半格空着。 */
    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void layout_keepsExplicitSide() {
        allowSpaceMember();
        MobileDashboardLayoutEntity stored = new MobileDashboardLayoutEntity();
        stored.spaceId = SPACE_ID;
        stored.version = 3L;
        MobileDashboardWidget only = statWidget();
        only.side = "RIGHT";
        stored.widgets = List.of(only);
        when(layoutService.load(any())).thenReturn(Uni.createFrom().item(stored));

        given()
                .when().get("/matrix/v1/dashboard/mobile/layout/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("data.version", equalTo(3))
                .body("data.widgets[0].side", equalTo("RIGHT"));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void layout_notMember_returnsError() {
        when(spaceService.getById(SPACE_ID)).thenReturn(Uni.createFrom().item(space()));
        when(orgAccessService.checkSpaceMember(any(), anyString()))
                .thenReturn(Uni.createFrom().failure(new IllegalArgumentException("Not a member of the space")));

        given()
                .when().get("/matrix/v1/dashboard/mobile/layout/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(false));

        verify(layoutService, never()).load(any());
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void layout_spaceNotFound_returnsNoAccess() {
        spaceNotFound();

        given()
                .when().get("/matrix/v1/dashboard/mobile/layout/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", equalTo("No access to this space"));

        verify(layoutService, never()).load(any());
    }

    // ===== PUT /layout：空间管理员 =====

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void save_spaceAdmin_succeeds() {
        allowSpaceAdmin();
        when(layoutValidator.validate(any(), any())).thenReturn(Uni.createFrom().voidItem());
        when(layoutService.save(any(), any(), any())).thenAnswer(invocation -> {
            MobileDashboardLayoutEntity layout = MobileDashboardPresetLayouts.build(SPACE_ID);
            layout.version = 1L;
            return Uni.createFrom().item(layout);
        });

        given().contentType("application/json").body(saveBody(0L, statWidget()))
                .when().put("/matrix/v1/dashboard/mobile/layout/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.version", equalTo(1));

        // 保存的人取自 JWT，不是请求体
        ArgumentCaptor<Person> who = ArgumentCaptor.forClass(Person.class);
        verify(layoutService).save(any(), any(), who.capture());
        assertEquals("tester", who.getValue().id);
    }

    /**
     * 请求体里的 {@code side} 必须真的绑进 DTO —— 实体上多一个没人绑的字段，
     * 症状是「客户端拖好半格、保存、刷新全回到左边」，而且不报任何错。
     */
    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void save_bindsSideFromRequestBody() {
        allowSpaceAdmin();
        when(layoutValidator.validate(any(), any())).thenReturn(Uni.createFrom().voidItem());
        when(layoutService.save(any(), any(), any()))
                .thenReturn(Uni.createFrom().item(MobileDashboardPresetLayouts.build(SPACE_ID)));

        MobileDashboardWidget half = statWidget();
        half.side = "RIGHT";
        JsonObject body = new JsonObject().put("version", 0)
                .put("widgets", new JsonArray().add(MobileDashboardLayoutCodec.encodeWidget(half, MobileDashboardWidgetSide.RIGHT)));

        given().contentType("application/json").body(body.encode())
                .when().put("/matrix/v1/dashboard/mobile/layout/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(true));

        ArgumentCaptor<MobileDashboardLayoutDto> dto = ArgumentCaptor.forClass(MobileDashboardLayoutDto.class);
        verify(layoutService).save(any(), dto.capture(), any());
        assertEquals("RIGHT", dto.getValue().widgets.get(0).side);
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void save_spaceMemberOnly_isRejected() {
        denySpaceAdmin();

        given().contentType("application/json").body(saveBody(0L, statWidget()))
                .when().put("/matrix/v1/dashboard/mobile/layout/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(false));

        verify(layoutService, never()).save(any(), any(), any());
        // 鉴权不过时连校验都不该跑
        verify(layoutValidator, never()).validate(any(), any());
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void save_invalidBody_isRejectedBeforeSaving() {
        allowSpaceAdmin();
        when(layoutValidator.validate(any(), any()))
                .thenReturn(Uni.createFrom().failure(new IllegalArgumentException(
                        "widgets[0]: size HALF is only valid for stat and service cards")));

        given().contentType("application/json").body(saveBody(0L, statWidget()))
                .when().put("/matrix/v1/dashboard/mobile/layout/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", equalTo("widgets[0]: size HALF is only valid for stat and service cards"));

        verify(layoutService, never()).save(any(), any(), any());
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void save_versionConflict_isSurfacedNotSwallowed() {
        allowSpaceAdmin();
        when(layoutValidator.validate(any(), any())).thenReturn(Uni.createFrom().voidItem());
        when(layoutService.save(any(), any(), any())).thenReturn(Uni.createFrom()
                .failure(new IllegalArgumentException("layout has been modified by someone else, please reload")));

        given().contentType("application/json").body(saveBody(3L, statWidget()))
                .when().put("/matrix/v1/dashboard/mobile/layout/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", equalTo("layout has been modified by someone else, please reload"));
    }

    // ===== GET /layout/{spaceId}/preset：空间成员（只读） =====

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void preset_member_returnsPreset() {
        allowSpaceMember();
        when(layoutService.preset(any())).thenReturn(MobileDashboardPresetLayouts.build(SPACE_ID));

        given()
                .when().get("/matrix/v1/dashboard/mobile/layout/{spaceId}/preset", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.version", equalTo(0))
                .body("data.widgets.size()", equalTo(6));

        verify(layoutService).preset(any());
    }

    /** 与 Web 一致：移动端也**没有 DELETE**——「恢复默认」是纯前端草稿替换，不是删库。 */
    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void preset_isReadOnly_notDelete() {
        allowSpaceAdmin();

        given()
                .when().delete("/matrix/v1/dashboard/mobile/layout/{spaceId}", SPACE_ID)
                .then().statusCode(405);
    }

    // ===== POST /render：空间成员（复用共享取数引擎） =====

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void render_noDraft_usesStoredLayout() {
        allowSpaceMember();
        MobileDashboardLayoutEntity stored = MobileDashboardPresetLayouts.build(SPACE_ID);
        when(layoutService.load(any())).thenReturn(Uni.createFrom().item(stored));
        when(widgetDataService.render(any(), anyList())).thenReturn(Uni.createFrom().item(widgetData()));

        given().contentType("application/json").body(new JsonObject().encode())
                .when().post("/matrix/v1/dashboard/mobile/render/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.widgets[0].success", equalTo(true));

        ArgumentCaptor<List<MobileDashboardWidget>> widgets = ArgumentCaptor.forClass(List.class);
        verify(widgetDataService).render(any(), widgets.capture());
        assertEquals(6, widgets.getValue().size());
    }

    /** 空的 JSON 对象（前端「刷新看板」）也走已存布局，不该 500。 */
    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void render_emptyBody_usesStoredLayout() {
        allowSpaceMember();
        when(layoutService.load(any())).thenReturn(Uni.createFrom().item(MobileDashboardPresetLayouts.build(SPACE_ID)));
        when(widgetDataService.render(any(), anyList())).thenReturn(Uni.createFrom().item(widgetData()));

        given().contentType("application/json").body(new JsonObject().encode())
                .when().post("/matrix/v1/dashboard/mobile/render/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(true));
    }

    /** 带 widgets 的请求体是**编辑器预览**：渲染未保存草稿，成员即可（render 只读）。 */
    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void render_draft_isRenderedWithoutSaving() {
        allowSpaceMember();
        when(widgetDataService.render(any(), anyList())).thenReturn(Uni.createFrom().item(widgetData()));

        given().contentType("application/json").body(saveBody(null, statWidget()))
                .when().post("/matrix/v1/dashboard/mobile/render/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(true));

        // 预览不落库
        verify(layoutService, never()).save(any(), any(), any());
        verify(layoutService, never()).load(any());
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void render_draftTooManyWidgets_isRejected() {
        allowSpaceMember();
        List<MobileDashboardWidget> tooMany = new ArrayList<>();
        for (int i = 0; i <= DashboardWidgetStructureValidator.MAX_WIDGETS; i++) {
            MobileDashboardWidget w = statWidget();
            w.id = "mw" + i;
            tooMany.add(w);
        }

        given().contentType("application/json").body(saveBody(null, tooMany.toArray(new MobileDashboardWidget[0])))
                .when().post("/matrix/v1/dashboard/mobile/render/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", equalTo("too many widgets: at most "
                        + DashboardWidgetStructureValidator.MAX_WIDGETS));

        verify(widgetDataService, never()).render(any(), anyList());
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void render_notMember_returnsError() {
        when(spaceService.getById(SPACE_ID)).thenReturn(Uni.createFrom().item(space()));
        when(orgAccessService.checkSpaceMember(any(), anyString()))
                .thenReturn(Uni.createFrom().failure(new IllegalArgumentException("Not a member of the space")));

        given().contentType("application/json").body(new JsonObject().encode())
                .when().post("/matrix/v1/dashboard/mobile/render/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(false));

        verify(widgetDataService, never()).render(any(), anyList());
    }

    // ===== helpers =====

    private static DashboardWidgetDataService.WidgetData widgetData() {
        return new DashboardWidgetDataService.WidgetData("space-1", new Date(1L), new Date(2L),
                List.of(new DashboardWidgetDataService.Item("w1", true, new JsonObject().put("value", 10), null)));
    }

    /** 移动端统计卡用半宽档位。 */
    private static MobileDashboardWidget statWidget() {
        MobileDashboardWidget w = new MobileDashboardWidget();
        w.id = "w1";
        w.type = "stat";
        w.size = "HALF";
        w.config = new LinkedHashMap<>(Map.of("metric", "devices.total"));
        return w;
    }

    private static String saveBody(Long version, MobileDashboardWidget... widgets) {
        JsonObject body = new JsonObject().put("widgets", new JsonArray());
        if (version != null) {
            body.put("version", version);
        }
        for (MobileDashboardWidget widget : widgets) {
            body.getJsonArray("widgets").add(MobileDashboardLayoutCodec.encodeWidget(widget));
        }
        return body.encode();
    }
}