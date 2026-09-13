package cc.openxiot.matrix.db.space;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SpaceEntity#rootSpaceId()}：设备归属（manipulation 的 {@code ownerId}）取的就是它。
 *
 * <p>口径取错不会报错，只会让设备"看起来坏了"（归属查不到 → "device not owned"），
 * 所以这三种情形（根、子、祖先缺失的兜底）各钉一条。</p>
 */
class SpaceEntityTest {

    private static final ObjectId ROOT = new ObjectId("6512f3a4b5c6d7e8f9a0b101");
    private static final ObjectId LEVEL_1 = new ObjectId("6512f3a4b5c6d7e8f9a0b102");

    @Test
    void rootSpaceIsItself() {
        SpaceEntity root = new SpaceEntity();
        root.id = ROOT;
        root.parentId = null;       // 根空间就没有父
        root.ancestors = List.of(); // 也没有祖先

        assertEquals(ROOT.toHexString(), root.rootSpaceId());
    }

    /** 祖先链从根往下排（SpaceService.create：parent.ancestors + [parentId]），第一个就是根。 */
    @Test
    void childSpaceTakesFirstAncestor() {
        SpaceEntity child = new SpaceEntity();
        child.id = new ObjectId("6512f3a4b5c6d7e8f9a0b103");
        child.parentId = LEVEL_1.toHexString();
        child.ancestors = List.of(ROOT.toHexString(), LEVEL_1.toHexString());

        assertEquals(ROOT.toHexString(), child.rootSpaceId());
    }

    /**
     * 祖先缺失（历史数据 / 手工改库）时退一级到 parentId：认成上一级只是「归属粒度粗一点」，
     * 认成自己会让同根空间下的子空间各记各的归属、设备在兄弟空间里就不可见了。
     */
    @Test
    void missingAncestorsFallsBackToParent() {
        SpaceEntity orphan = new SpaceEntity();
        orphan.id = new ObjectId("6512f3a4b5c6d7e8f9a0b103");
        orphan.parentId = LEVEL_1.toHexString();
        orphan.ancestors = new ArrayList<>();   // 空列表

        assertEquals(LEVEL_1.toHexString(), orphan.rootSpaceId());

        orphan.ancestors = null;                // 字段整个缺失（老文档）
        assertEquals(LEVEL_1.toHexString(), orphan.rootSpaceId());
    }

    /** 空串与 null 的 parentId 同义（都表示「没有父」）；blank 的 parentId 不是空间 id，不能当根返回。 */
    @Test
    void blankParentIdCountsAsRoot() {
        SpaceEntity root = new SpaceEntity();
        root.id = ROOT;

        root.parentId = "";
        assertEquals(ROOT.toHexString(), root.rootSpaceId());

        root.parentId = "  ";
        assertEquals(ROOT.toHexString(), root.rootSpaceId());
    }

    /** 还没落库（{@code id} 为空）的实体：没有可返回的 id，返回 null 而不是 "null"。 */
    @Test
    void noIdReturnsNull() {
        assertNull(new SpaceEntity().rootSpaceId());
    }
}
