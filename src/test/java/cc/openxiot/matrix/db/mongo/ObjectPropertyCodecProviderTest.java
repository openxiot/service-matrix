package cc.openxiot.matrix.db.mongo;

import cc.openxiot.matrix.db.modbus.history.ModbusServiceHistory;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceShadow;
import cc.openxiot.matrix.db.space.SpaceEntity;
import com.mongodb.MongoClientSettings;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonDocumentWriter;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.Document;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.configuration.CodecConfigurationException;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.Conventions;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ObjectPropertyCodecProvider}：声明成 {@code Object} 的字段能落库、能读回来。
 *
 * <p>这里**不连 Mongo**：注册表按 Quarkus 的 {@code MongoClients.configureCodecRegistry} 原样搭
 * （默认注册表 + {@code automatic(true)} 的 {@code PojoCodecProvider}，本 provider 注册在其上），
 * 再用 {@code BsonDocumentWriter} / {@code BsonDocumentReader} 走一遍编解码 ——
 * 这条路径与真落库时驱动走的是同一条。</p>
 *
 * <p>现网就是这么炸的：{@code ModbusServiceHistory.value} 声明成 {@code Object}，编码第一步
 * {@code Can't find a codec for CodecCacheKey{clazz=class java.lang.Object, types=null}}，
 * 于是 {@code service-histories} 一条样本也没写进去过。{@link #withoutTheProviderTheSameWriteFails()}
 * 把那个失败形态钉在这里。</p>
 *
 * <p>把 provider 摘掉重跑过一遍（确认这些用例不是「本来就绿」）：四个 {@code value} 用例全报出与现网
 * 一字不差的错，两个地图用例**仍然绿** —— {@code Map<String, Object>} 驱动自己有一条兜底（见
 * {@link ObjectPropertyCodecProvider} 的说明）。所以那两个用例钉的不是「修好了」，而是
 * 「值槽被接过来之后口径没变」。</p>
 */
class ObjectPropertyCodecProviderTest {

    private static final long AT = 1_700_000_000_000L;

    /** 装上 provider 的注册表：与 Quarkus 给 Mongo 客户端搭的那张同形。 */
    private static final CodecRegistry REGISTRY = registryWith(new ObjectPropertyCodecProvider());

    private static CodecRegistry registryWith(ObjectPropertyCodecProvider... providers) {
        PojoCodecProvider.Builder pojo = PojoCodecProvider.builder()
                .automatic(true)
                .conventions(Conventions.DEFAULT_CONVENTIONS);
        for (ObjectPropertyCodecProvider provider : providers) {
            pojo.register(provider);
        }
        return CodecRegistries.fromRegistries(
                MongoClientSettings.getDefaultCodecRegistry(),
                CodecRegistries.fromProviders(pojo.build()));
    }

    // ===== 历史样本：value 是异构的 =====

    @Test
    void integerValueIsWrittenAsItsRuntimeType() {
        ModbusServiceHistory sample = sample(42);

        BsonDocument doc = encode(sample);

        assertEquals(new BsonInt32(42), doc.get("value"));
        assertEquals(new BsonString("6aa759bb4e45b0b6122d2ed5"), doc.get("serviceId"));
        assertEquals(1, doc.getInt32("functionIndex").getValue());
        assertTrue(!doc.containsKey("keepalive"), "null 不落键：正常样本身上不该出现 keepalive");

        ModbusServiceHistory read = decode(ModbusServiceHistory.class, doc);
        assertInstanceOf(Integer.class, read.value);
        assertEquals(42, read.value);
        assertEquals("温度", read.field);
        assertEquals(new Date(AT), read.at);
        assertNull(read.keepalive);
    }

    /** 另外三种形态：{@code value-list} 的 description、位展开的 0|1、以及没有值。 */
    @Test
    void theOtherValueShapesRoundTrip() {
        assertEquals("运行", decode(ModbusServiceHistory.class, encode(sample("运行"))).value);
        assertEquals(Boolean.TRUE, decode(ModbusServiceHistory.class, encode(sample(Boolean.TRUE))).value);
        assertEquals(3.5, decode(ModbusServiceHistory.class, encode(sample(3.5))).value);
        assertEquals(new Date(AT), decode(ModbusServiceHistory.class, encode(sample(new Date(AT)))).value);
        assertNull(decode(ModbusServiceHistory.class, encode(sample(null))).value);
    }

    /** 值里嵌数组 / 嵌文档一样要能落（空间的 {@code attributes} 里就可能放数组）。 */
    @Test
    void nestedContainersRecurse() {
        assertEquals(List.of(1, "运行", Boolean.TRUE),
                decode(ModbusServiceHistory.class, encode(sample(List.of(1, "运行", Boolean.TRUE)))).value);
        assertEquals(new Document("上限", 60).append("别名", "高温"),
                decode(ModbusServiceHistory.class,
                        encode(sample(new Document("上限", 60).append("别名", "高温")))).value);
    }

    /** 数值读回来的类由 BSON 类型决定：int32 → Integer、int64 → Long、double → Double。 */
    @Test
    void numbersComeBackAsTheTypeTheBsonTypeMaps() {
        assertEquals(Integer.class, decode(ModbusServiceHistory.class, encode(sample(42))).value.getClass());
        assertEquals(Long.class, decode(ModbusServiceHistory.class, encode(sample(42L))).value.getClass());
        assertEquals(Double.class, decode(ModbusServiceHistory.class, encode(sample(42.0))).value.getClass());
    }

    // ===== 影子 / 空间属性：Map<String, Object> 的每个值都走同一格 =====

    /** 影子的 {@code fields}：四类值（数值 / 描述 / 布尔 / 空）都能进能出。 */
    @Test
    void shadowFieldsRoundTrip() {
        ModbusServiceShadow shadow = new ModbusServiceShadow();
        shadow.id = new ObjectId();
        shadow.serviceId = "6aa759bb4e45b0b6122d2ed5";
        shadow.functionIndex = 1;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("温度", 25);
        fields.put("状态", "运行");
        fields.put("告警", Boolean.FALSE);
        fields.put("空位", null);
        shadow.fields = fields;
        shadow.recordedAt = new Date(AT);

        ModbusServiceShadow read = decode(ModbusServiceShadow.class, encode(shadow));

        assertEquals(fields, read.fields);
        assertEquals(new Date(AT), read.recordedAt);
        assertNull(read.lastError, "最后一次调用是成功的，故障位不该有值");
    }

    /** 同一格兜的还有空间属性；顺带说明这条 provider 是跨实体生效的，不是给历史打的一个补丁。 */
    @Test
    void spaceAttributesRoundTrip() {
        SpaceEntity space = new SpaceEntity();
        space.id = new ObjectId();
        space.name = "车间";
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("高度", 8.5);
        attributes.put("楼层", 3);
        attributes.put("备注", "西侧");
        space.attributes = attributes;

        SpaceEntity read = decode(SpaceEntity.class, encode(space));

        assertEquals(attributes, read.attributes);
        assertEquals("车间", read.name);
    }

    // ===== 钉住失败形态 =====

    /**
     * 没有这条 provider 时，同一个「写一条历史样本」就是现网那个报错 ——
     * 异常类型与消息都钉住，免得哪天它变成了另一个错、却看起来还是绿的。
     */
    @Test
    void withoutTheProviderTheSameWriteFails() {
        Codec<ModbusServiceHistory> codec = registryWith().get(ModbusServiceHistory.class);
        BsonDocumentWriter writer = new BsonDocumentWriter(new BsonDocument());

        CodecConfigurationException error = assertThrows(CodecConfigurationException.class,
                () -> codec.encode(writer, sample(42), EncoderContext.builder().build()));

        assertTrue(error.getMessage().contains("Failed to encode 'ModbusServiceHistory'"), error.getMessage());
        assertTrue(error.getMessage().contains("java.lang.Object"), error.getMessage());
    }

    // ===== 夹具 =====

    private static ModbusServiceHistory sample(Object value) {
        ModbusServiceHistory sample = new ModbusServiceHistory();
        sample.id = new ObjectId();
        sample.serviceId = "6aa759bb4e45b0b6122d2ed5";
        sample.functionIndex = 1;
        sample.field = "温度";
        sample.value = value;
        sample.at = new Date(AT);
        return sample;
    }

    /** 编成一个 BSON 文档（真落库时驱动写的就是它）。 */
    private static <T> BsonDocument encode(T entity) {
        BsonDocument doc = new BsonDocument();
        codecOf(entity).encode(new BsonDocumentWriter(doc), entity, EncoderContext.builder().build());
        return doc;
    }

    private static <T> T decode(Class<T> clazz, BsonDocument doc) {
        return REGISTRY.get(clazz).decode(new BsonDocumentReader(doc), DecoderContext.builder().build());
    }

    @SuppressWarnings("unchecked")
    private static <T> Codec<T> codecOf(T entity) {
        return (Codec<T>) REGISTRY.get(entity.getClass());
    }
}
