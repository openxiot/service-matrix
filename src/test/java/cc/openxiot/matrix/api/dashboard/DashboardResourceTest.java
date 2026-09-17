package cc.openxiot.matrix.api.dashboard;

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
import cc.openxiot.matrix.api.space.SpaceService;
import cc.openxiot.matrix.db.dashboard.DashboardLayoutEntity;
import cc.openxiot.matrix.db.dashboard.DashboardWidget;
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
 * 看板接口的**读写不对称**：读是空间成员（只读看板人人可见）、写是空间管理员
 * （改布局影响所有人）。这是本功能唯一的权限口径，也是这个测试存在的理由。
 *
 * <p>全部走 {@code @InjectMock}，不连 Mongo。三个校验器/服务各自的行为由它们自己的测试覆盖，
 * 这里只钉「谁被允许做什么」和「请求体有没有被正确地转给服务」。</p>
 */
@QuarkusTest
class DashboardResourceTest {

    private static final String SPACE_ID = "6512f3a4b5c6d7e8f9a0b1c3";
    private static final String ORG = "org1";

    @InjectMock
    SpaceService spaceService;

    @InjectMock
    OrgAccessService orgAccessService;

    @InjectMock
    DashboardLayoutService layoutService;

    @InjectMock
    DashboardLayoutValidator layoutValidator;

    @InjectMock
    WidgetDataService widgetDataService;

    @InjectMock
    DashboardCatalogService catalogService;

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

    // ===== GET /layout：空间成员 =====

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void layout_spaceMember_getsPreset() {
        allowSpaceMember();
        when(layoutService.load(any())).thenReturn(Uni.createFrom().item(PresetLayouts.build(SPACE_ID)));

        given()
                .when().get("/matrix/v1/dashboard/layout/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.spaceId", equalTo(SPACE_ID))
                // 预置布局的版本号是 0 = 「首次保存要提交的版本号」，前端原样回传即可
                .body("data.version", equalTo(0))
                .body("data.widgets.size()", equalTo(8));
    }

    /**
     * 从未配置过的空间**不报 404**：没有文档时给一份内存生成的预置布局，
     * 用户第一次打开就有东西可看（而不是先看到「尚未配置」再点一个「创建」按钮）。
     */
    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void layout_notConfigured_returnsPresetNotError() {
        allowSpaceMember();
        when(layoutService.load(any()))
                .thenReturn(Uni.createFrom().item(PresetLayouts.build(SPACE_ID)));

        given()
                .when().get("/matrix/v1/dashboard/layout/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.widgets.size()", equalTo(8));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void layout_notMember_returnsError() {
        when(spaceService.getById(SPACE_ID)).thenReturn(Uni.createFrom().item(space()));
        when(orgAccessService.checkSpaceMember(any(), anyString()))
                .thenReturn(Uni.createFrom().failure(new IllegalArgumentException("Not a member of the space")));

        given()
                .when().get("/matrix/v1/dashboard/layout/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(false));

        verify(layoutService, never()).load(any());
    }

    /** 空间不存在按「无权限」处理，不区分 404（与 DeviceResource 同口径）：不泄漏空间是否存在。 */
    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void layout_spaceNotFound_returnsNoAccess() {
        spaceNotFound();

        given()
                .when().get("/matrix/v1/dashboard/layout/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", equalTo("No access to this space"));

        verify(layoutService, never()).load(any());
    }

    // ===== GET /catalog：空间成员 =====

    /**
     * 候选清单与读布局同权限：清单里只有**他已经能看见**的设备与服务（同一子树），
     * 所以这不是一处新的信息暴露面，他只是省掉了手抄 id。
     */
    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void catalog_spaceMember_getsTheLists() {
        allowSpaceMember();
        when(catalogService.catalog(any()))
                .thenReturn(Uni.createFrom().item(DashboardCatalogCodec.encode(List.of(), List.of())));

        given()
                .when().get("/matrix/v1/dashboard/catalog/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(true))
                // 两个键恒在：编辑器据此显示「没有可选项」，而不是把级联画成坏掉的样子
                .body("data.devices.size()", equalTo(0))
                .body("data.services.size()", equalTo(0));
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void catalog_notMember_returnsError() {
        when(spaceService.getById(SPACE_ID)).thenReturn(Uni.createFrom().item(space()));
        when(orgAccessService.checkSpaceMember(any(), anyString()))
                .thenReturn(Uni.createFrom().failure(new IllegalArgumentException("Not a member of the space")));

        given()
                .when().get("/matrix/v1/dashboard/catalog/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(false));

        verify(catalogService, never()).catalog(any());
    }

    // ===== PUT /layout：空间管理员 =====

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void save_spaceAdmin_succeeds() {
        allowSpaceAdmin();
        when(layoutValidator.validate(any(), any())).thenReturn(Uni.createFrom().voidItem());
        when(layoutService.save(any(), any(), any())).thenAnswer(invocation -> {
            DashboardLayoutEntity layout = PresetLayouts.build(SPACE_ID);
            layout.version = 1L;
            return Uni.createFrom().item(layout);
        });

        given().contentType("application/json").body(saveBody(0L, statWidget()))
                .when().put("/matrix/v1/dashboard/layout/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.version", equalTo(1));

        // 保存的人取自 JWT，不是请求体：前端传什么都不该影响 creator/updater
        ArgumentCaptor<Person> who = ArgumentCaptor.forClass(Person.class);
        verify(layoutService).save(any(), any(), who.capture());
        assertEquals("tester", who.getValue().id);
    }

    /**
     * 空间成员**不能**保存 —— 布局是空间共享一份，改它影响所有人。这条是本功能的核心权限口径。
     */
    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void save_spaceMemberOnly_isRejected() {
        denySpaceAdmin();

        given().contentType("application/json").body(saveBody(0L, statWidget()))
                .when().put("/matrix/v1/dashboard/layout/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(false));

        verify(layoutService, never()).save(any(), any(), any());
        // 鉴权不过时连校验都不该跑（校验要查库，那是白花的钱）
        verify(layoutValidator, never()).validate(any(), any());
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void save_invalidBody_isRejectedBeforeSaving() {
        allowSpaceAdmin();
        when(layoutValidator.validate(any(), any()))
                .thenReturn(Uni.createFrom().failure(new IllegalArgumentException(
                        "widgets[0]: config.metric must be one of [devices.total]")));

        given().contentType("application/json").body(saveBody(0L, statWidget()))
                .when().put("/matrix/v1/dashboard/layout/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", equalTo("widgets[0]: config.metric must be one of [devices.total]"));

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
                .when().put("/matrix/v1/dashboard/layout/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", equalTo("layout has been modified by someone else, please reload"));
    }

    // ===== DELETE /layout：空间管理员 =====

    /**
     * 恢复默认返回的是**删完之后读到的预置布局**，与 GET/PUT 同形状：前端「恢复默认」之后
     * 本来就要重画看板，省一次 GET 也就少一次「删完再读」之间被人插进一次保存的竞态。
     */
    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void reset_spaceAdmin_returnsPreset() {
        allowSpaceAdmin();
        when(layoutService.reset(any())).thenReturn(Uni.createFrom().item(1L));
        when(layoutService.load(any())).thenReturn(Uni.createFrom().item(PresetLayouts.build(SPACE_ID)));

        given()
                .when().delete("/matrix/v1/dashboard/layout/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.version", equalTo(0))
                .body("data.widgets.size()", equalTo(8));

        verify(layoutService).reset(any());
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void reset_notAdmin_isRejected() {
        denySpaceAdmin();

        given()
                .when().delete("/matrix/v1/dashboard/layout/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(false));

        verify(layoutService, never()).reset(any());
    }

    // ===== POST /render：空间成员 =====

    /**
     * 不传 widgets = 渲染**已存布局**，而不是让前端总把它传上来：前端手里那份可能是几分钟前
     * GET 的，而看板自己刷新应该取当前布局（否则管理员刚改完，别人的页面上还是旧卡片）。
     */
    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void render_noDraft_usesStoredLayout() {
        allowSpaceMember();
        DashboardLayoutEntity stored = PresetLayouts.build(SPACE_ID);
        when(layoutService.load(any())).thenReturn(Uni.createFrom().item(stored));
        when(widgetDataService.render(any(), anyList())).thenReturn(Uni.createFrom().item(widgetData()));

        given().contentType("application/json").body(new JsonObject().encode())
                .when().post("/matrix/v1/dashboard/render/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("data.widgets[0].success", equalTo(true));

        ArgumentCaptor<List<DashboardWidget>> widgets = ArgumentCaptor.forClass(List.class);
        verify(widgetDataService).render(any(), widgets.capture());
        assertEquals(8, widgets.getValue().size());
    }

    /**
     * 请求体是空的 JSON 对象（前端「刷新看板」就是这样调的）也走已存布局，不该 500。
     *
     * <p>注意「不传 widgets」指的是 JSON 里没有这个字段，而不是「没有 HTTP body」：
     * 这个方法消费 {@code application/json}，裸 POST 会在进入方法体之前就被框架以 415 拒掉 ——
     * 这是 JAX-RS 的常规行为，也是前端本来就不会这么调的一种请求。</p>
     */
    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void render_emptyBody_usesStoredLayout() {
        allowSpaceMember();
        when(layoutService.load(any())).thenReturn(Uni.createFrom().item(PresetLayouts.build(SPACE_ID)));
        when(widgetDataService.render(any(), anyList())).thenReturn(Uni.createFrom().item(widgetData()));

        given().contentType("application/json").body(new JsonObject().encode())
                .when().post("/matrix/v1/dashboard/render/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(true));
    }

    /**
     * 带 widgets 的请求体是**编辑器预览**：渲染尚未保存的草稿。成员可用 —— render 只读，
     * 且归属校验照做，所以成员预览不了别人的卡片。
     */
    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void render_draft_isRenderedWithoutSaving() {
        allowSpaceMember();
        when(widgetDataService.render(any(), anyList())).thenReturn(Uni.createFrom().item(widgetData()));

        given().contentType("application/json").body(saveBody(null, statWidget()))
                .when().post("/matrix/v1/dashboard/render/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(true));

        // 预览不落库：编辑器里改十次预览不该写十次
        verify(layoutService, never()).save(any(), any(), any());
        verify(layoutService, never()).load(any());
    }

    /**
     * 草稿的**结构性**错误整份拒绝：body 不成形时连「有哪些卡片」都无从谈起。
     * （归属错误则相反 —— 逐张隔离，见 {@code WidgetDataService}。）
     */
    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void render_draftTooManyWidgets_isRejected() {
        allowSpaceMember();
        List<DashboardWidget> tooMany = new ArrayList<>();
        for (int i = 0; i <= DashboardWidgetValidator.MAX_WIDGETS; i++) {
            DashboardWidget w = statWidget();
            w.id = "w" + i;
            tooMany.add(w);
        }

        given().contentType("application/json").body(saveBody(null, tooMany.toArray(new DashboardWidget[0])))
                .when().post("/matrix/v1/dashboard/render/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", equalTo("too many widgets: at most " + DashboardWidgetValidator.MAX_WIDGETS));

        verify(widgetDataService, never()).render(any(), anyList());
    }

    @Test
    @TestSecurity(user = "tester", roles = {OxRole.USER})
    void render_notMember_returnsError() {
        when(spaceService.getById(SPACE_ID)).thenReturn(Uni.createFrom().item(space()));
        when(orgAccessService.checkSpaceMember(any(), anyString()))
                .thenReturn(Uni.createFrom().failure(new IllegalArgumentException("Not a member of the space")));

        given().contentType("application/json").body(new JsonObject().encode())
                .when().post("/matrix/v1/dashboard/render/{spaceId}", SPACE_ID)
                .then().statusCode(200)
                .body("success", equalTo(false));

        verify(widgetDataService, never()).render(any(), anyList());
    }

    // ===== helpers =====

    private static WidgetDataService.WidgetData widgetData() {
        return new WidgetDataService.WidgetData("space-1", new Date(1L), new Date(2L),
                List.of(new WidgetDataService.Item("w1", true, new JsonObject().put("value", 10), null)));
    }

    private static DashboardWidget statWidget() {
        DashboardWidget w = new DashboardWidget();
        w.id = "w1";
        w.type = "stat";
        w.size = "S";
        w.config = new LinkedHashMap<>(Map.of("metric", "devices.total"));
        return w;
    }

    /** 保存请求体：{@code version} 可以为 null（那是「漏传」的情形，不在这里测）。 */
    private static String saveBody(Long version, DashboardWidget... widgets) {
        JsonObject body = new JsonObject().put("widgets", new JsonArray());
        if (version != null) {
            body.put("version", version);
        }
        for (DashboardWidget widget : widgets) {
            body.getJsonArray("widgets").add(DashboardLayoutCodec.encodeWidget(widget));
        }
        return body.encode();
    }
}
