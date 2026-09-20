package cc.openxiot.matrix.api.dashboard.web.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cc.openxiot.matrix.api.dashboard.web.params.WebDashboardLayoutDto;
import cc.openxiot.matrix.api.dashboard.widget.DashboardWidgetOwnershipValidator;
import cc.openxiot.matrix.db.dashboard.web.WebDashboardWidget;
import cc.openxiot.matrix.db.space.SpaceEntity;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 保存路径的**校验编排**：先结构、后归属，第一个错误即整份失败。
 *
 * <p>本类补的是一个真实存在的覆盖空洞。改造前 {@code WebDashboardLayoutValidator} 同时干两件事，
 * 而测试只调了 {@code errorsByWidget} —— 也就是说「先跑结构、结构错了就不查库」这段编排
 * <b>从来没被任何用例执行过</b>；{@code DashboardResourceTest} 又把整个 validator mock 掉了，
 * 更碰不到它。拆成薄壳之后这段逻辑成了本类唯一的职责，也就必须自己有用例。</p>
 *
 * <p><b>纯 JUnit，不连库</b>：两个校验器一个是静态纯函数、一个是 mock，
 * 所以这里没有 {@code @QuarkusTest}。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DashboardLayoutValidatorTest {

    @Mock
    DashboardWidgetOwnershipValidator ownershipValidator;

    @InjectMocks
    WebDashboardLayoutValidator validator;

    /**
     * 这条是拆分的**全部理由**：body 不成形时连「有哪些卡片」都无从谈起，
     * 此时一次查询都不该发出去。旧写法里这一步和查库混在一个方法里，漏掉短路就会
     * 拿着半成形的 body 去查 Mongo —— 校验器于是成了新的故障源。
     */
    @Test
    void validate_structuralErrorFailsWithoutQueryingOwnership() {
        WebDashboardLayoutDto body = body(null, List.of(validWidget()));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(space(), body).await().indefinitely());

        assertEquals("version is required", e.getMessage());
        verifyNoInteractions(ownershipValidator);
    }

    /**
     * 归属错误逐张上报时，保存路径只取**第一张出错的卡**：错误提示最终是一个字段/一条 toast，
     * 编辑器放不下第二个。顺序由 {@code errorsByWidget} 用 {@code LinkedHashMap} 保证
     * （按卡片提交顺序插入），这里把它钉住 —— 换成 {@code HashMap} 这条就会红。
     */
    @Test
    void validate_ownershipErrorReportsTheFirstOneInSubmissionOrder() {
        Map<String, String> errors = new LinkedHashMap<>();
        errors.put("w1", "widgets[0](w1): device not found: did-9");
        errors.put("w2", "widgets[1](w2): service not found: svc-9");
        when(ownershipValidator.errorsByWidget(any(), anyList()))
                .thenReturn(Uni.createFrom().item(errors));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(space(), body(0L, List.of(validWidget()))).await().indefinitely());

        assertEquals("widgets[0](w1): device not found: did-9", e.getMessage());
    }

    @Test
    void validate_noErrorsCompletes() {
        when(ownershipValidator.errorsByWidget(any(), anyList()))
                .thenReturn(Uni.createFrom().item(Map.of()));

        assertDoesNotThrow(() -> validator.validate(space(), body(0L, List.of(validWidget())))
                .await().indefinitely());
    }

    // ===== helpers =====

    private static SpaceEntity space() {
        SpaceEntity space = new SpaceEntity();
        space.parentId = "root-1";
        return space;
    }

    private static WebDashboardLayoutDto body(Long version, List<WebDashboardWidget> widgets) {
        WebDashboardLayoutDto body = new WebDashboardLayoutDto();
        body.version = version;
        body.widgets = widgets;
        return body;
    }

    /**
     * 一张结构上完全合法的卡片。**故意用最简的 stat**：本类要测的是编排（跑不跑、按什么顺序），
     * 用一张必然过结构校验的卡才不至于让结构规则的变化把编排用例带红 —— 结构规则自己有
     * {@code WidgetStructureValidatorTest} 几十条用例管着。
     */
    private static WebDashboardWidget validWidget() {
        WebDashboardWidget widget = new WebDashboardWidget();
        widget.id = "w1";
        widget.type = "stat";
        widget.size = "W6H92";
        widget.config = new LinkedHashMap<>(Map.of("metric", "devices.total"));
        return widget;
    }
}
