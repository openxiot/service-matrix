package cc.openxiot.matrix.db.modbus.history;

import cc.openxiot.matrix.db.modbus.history.ModbusServiceHistoryRepository.TtlIndex;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 保留策略里唯一一处「读 Mongo 的返回」：从 {@code listIndexes} 的结果里认出 TTL 索引
 * （见 Modbus-History.md 第六节）。
 *
 * <p>不用数据库：喂的就是 {@code listIndexes} 真实吐出来的那种文档。认错了的代价是
 * 保留策略静默失效或每次启动白重建一遍索引，所以这一处值得钉住。</p>
 */
class ModbusServiceHistoryRepositoryTest {

    /** 一条不带 TTL 的普通索引：{@code listIndexes} 的返回就是这种形状。 */
    private static Document index(String name, Document key) {
        return new Document("v", 2).append("key", key).append("name", name);
    }

    private static Document ttlIndex(String name, Document key, Object expireAfterSeconds) {
        return index(name, key).append("expireAfterSeconds", expireAfterSeconds);
    }

    /** 库里的常态：{@code _id_} + 我们那条复合索引，没有 TTL。 */
    private static final List<Document> NO_TTL = List.of(
            index("_id_", new Document("_id", 1)),
            index("serviceId_1_pollingIndex_1_field_1_at_1",
                    new Document("serviceId", 1).append("pollingIndex", 1).append("field", 1).append("at", 1)));

    @Test
    void noTtlIndexMeansNull() {
        assertNull(ModbusServiceHistoryRepository.findTtlIndex(NO_TTL));
        assertNull(ModbusServiceHistoryRepository.findTtlIndex(List.of()));
    }

    @Test
    void picksTheTtlIndexOutOfTheOthers() {
        List<Document> indexes = List.of(
                index("_id_", new Document("_id", 1)),
                ttlIndex("at_1", new Document("at", 1), 7_776_000),
                index("other", new Document("serviceId", 1).append("at", 1)));

        TtlIndex found = ModbusServiceHistoryRepository.findTtlIndex(indexes);

        assertEquals("at_1", found.name());
        assertEquals(7_776_000, found.seconds());
    }

    @Test
    void compoundIndexOnAtIsNotTheTtlIndex() {
        // 复合索引的 key 不止一段；只有单字段 {at:1} 才是我们那条
        List<Document> indexes = List.of(
                ttlIndex("serviceId_1_at_1", new Document("serviceId", 1).append("at", 1), 7_776_000));

        assertNull(ModbusServiceHistoryRepository.findTtlIndex(indexes));
    }

    @Test
    void descendingIndexOnAtIsNotTheTtlIndex() {
        // 方向对不上：我们建的是升序
        List<Document> indexes = List.of(ttlIndex("at_-1", new Document("at", -1), 7_776_000));

        assertNull(ModbusServiceHistoryRepository.findTtlIndex(indexes));
    }

    @Test
    void plainIndexOnAtWithoutExpiryIsNotTheTtlIndex() {
        // {at:1} 但没挂 expireAfterSeconds（比如有人手工建过一条普通索引）—— 不是保留策略那条
        List<Document> indexes = List.of(index("at_1", new Document("at", 1)));

        assertNull(ModbusServiceHistoryRepository.findTtlIndex(indexes));
    }

    @Test
    void secondsAreReadAsAnyNumber() {
        // int32 与 int64 都当数读：哪个版本回哪种没把握，强转 Integer 会在其中一个上炸
        assertEquals(60, ModbusServiceHistoryRepository.findTtlIndex(
                List.of(ttlIndex("at_1", new Document("at", 1), 60))).seconds());
        assertEquals(7_776_000L, ModbusServiceHistoryRepository.findTtlIndex(
                List.of(ttlIndex("at_1", new Document("at", 1), 7_776_000L))).seconds());
    }

    @Test
    void recognizesTheShapeAzureDocumentDbActuallyReturns() {
        // staging 冒烟时 listIndexes 原样吐回来的那条（2026-09-13，serverVersion 8.0.0）：
        // 比原版 MongoDB 多一个 storageEngine —— 多出来的字段不该影响识别
        Document real = Document.parse("""
                {"v":2,"key":{"at":1},"name":"at_1_ttl_smoke","expireAfterSeconds":60,
                 "storageEngine":{"enableOrderedIndex":true}}""");

        TtlIndex found = ModbusServiceHistoryRepository.findTtlIndex(List.of(
                index("_id_", new Document("_id", 1)), real));

        assertEquals("at_1_ttl_smoke", found.name());
        assertEquals(60, found.seconds());
    }
}
