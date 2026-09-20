package cc.openxiot.matrix.db.mongo;

import cc.openxiot.matrix.db.dashboard.mobile.MobileDashboardLayoutEntity;
import cc.openxiot.matrix.db.dashboard.mobile.MobileDashboardWidget;
import cc.openxiot.matrix.db.dashboard.web.WebDashboardLayoutEntity;
import cc.openxiot.matrix.db.dashboard.web.WebDashboardWidget;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceHistory;
import cc.openxiot.matrix.db.modbus.history.ModbusServiceShadow;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.common.person.Person;
import com.mongodb.MongoClientSettings;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonDocumentWriter;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
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

    /**
     * 看板卡片的 {@code config}：{@code Map<String, Object>} 里**再嵌 Map 与 List**
     * （{@code window} 是文档、{@code fields} 是数组），值还有 Integer / Long / Boolean。
     *
     * <p>比 {@link #shadowFieldsRoundTrip} 深一层：那张卡上的每个值都是标量，而看板卡片的值
     * **本身就是容器**。{@code WebDashboardWidget} 的「config 异构、不做结构化」这个设计
     * 整个压在「能原样读回」上 —— 读回来若变成了字符串或丢了一层，校验器与取数都会静默失灵。</p>
     */
    @Test
    void dashboardWidgetConfigRoundTrips() {
        WebDashboardWidget widget = new WebDashboardWidget();
        widget.id = "w1";
        widget.type = "stat";
        widget.size = "W6H200";
        // 坐标是平铺的两个 Integer（不是老的那个嵌套 layout），落库要原样读回来 ——
        // 读丢了的后果是「拖完保存、刷新回到原位」，而且不报任何错
        widget.x = 6;
        widget.y = 4;
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("metric", "alarms.today");
        config.put("window", new Document("kind", "last").append("hours", 24));
        config.put("fields", List.of("temp", "status.bit0"));
        config.put("limit", 8);
        config.put("showUnit", Boolean.TRUE);
        widget.config = config;

        WebDashboardLayoutEntity layout = new WebDashboardLayoutEntity();
        layout.id = new ObjectId();
        layout.spaceId = "space-1";
        layout.version = 3L;
        layout.widgets = List.of(widget);
        layout.creator = Person.of("acc-1", "张三");

        WebDashboardLayoutEntity read = decode(WebDashboardLayoutEntity.class, encode(layout));

        assertEquals("space-1", read.spaceId);
        assertEquals(3L, read.version);
        assertEquals("acc-1", read.creator.id);
        assertEquals(1, read.widgets.size());
        WebDashboardWidget readWidget = read.widgets.get(0);
        assertEquals("w1", readWidget.id);
        // title 没设就是不落键：读回来是 null，而不是空串（读侧靠这个区分「没设」与「设成了空」）
        assertNull(readWidget.title);
        assertEquals(6, readWidget.x);
        assertEquals(4, readWidget.y);
        assertEquals(config, readWidget.config);
        // 嵌套的文档读回来是 Document、数组读回来是 List：读 config 的一方（校验器 / 取数）
        // 一律按 Map / List 取值，不假设具体实现类
        assertInstanceOf(Map.class, readWidget.config.get("window"));
        assertInstanceOf(List.class, readWidget.config.get("fields"));
        assertEquals(24, ((Map<?, ?>) readWidget.config.get("window")).get("hours"));
    }

    /**
     * 移动端那张卡也走一遍同一条路：**两个看板各有一个实体**，所以上面那条 web 往返绿了
     * <b>不蕴含</b>这条也绿 —— 它们是两个类、两套字段，注册表只是碰巧同一张。
     *
     * <p>顺带钉住「不落 {@code _t}」：看板文档上<b>故意没有</b> {@code @BsonDiscriminator}
     * （{@code DeviceEntity} / {@code SpaceEntity} 有，看板这两个没有），POJO codec 因此从不写类名 ——
     * 这正是「拆实体不需要迁移脚本」的那块地基。一旦有人顺手给实体加上那个注解，新写的文档就会带上
     * {@code _t}，而库里已有的文档没有它。{@link #legacyLayoutKeyIsIgnored} 从读的一侧钉同一件事。</p>
     */
    @Test
    void mobileDashboardWidgetConfigRoundTrips() {
        MobileDashboardWidget widget = new MobileDashboardWidget();
        widget.id = "m1";
        widget.type = "service";
        widget.size = "HALF";
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("serviceId", "6aa759bb4e45b0b6122d2ed5");
        config.put("functionIndex", 1);
        config.put("fields", List.of("temp", "status.bit0"));
        config.put("showUnit", Boolean.TRUE);
        widget.config = config;

        MobileDashboardLayoutEntity layout = new MobileDashboardLayoutEntity();
        layout.id = new ObjectId();
        layout.spaceId = "space-1";
        layout.version = 1L;
        layout.widgets = List.of(widget);
        layout.creator = Person.of("acc-1", "张三");

        MobileDashboardLayoutEntity read = decode(MobileDashboardLayoutEntity.class, encode(layout));

        assertEquals("space-1", read.spaceId);
        assertEquals(1L, read.version);
        assertEquals(1, read.widgets.size());
        MobileDashboardWidget readWidget = read.widgets.get(0);
        assertEquals("m1", readWidget.id);
        assertEquals("service", readWidget.type);
        assertEquals("HALF", readWidget.size);
        assertNull(readWidget.title);
        assertEquals(config, readWidget.config);
        // 数组读回来是 List：读 config 的一方一律按 Map / List 取值，不假设具体实现类
        assertInstanceOf(List.class, readWidget.config.get("fields"));
        assertTrue(!encode(read).getArray("widgets").get(0).asDocument().containsKey("_t"),
                "没有 @BsonDiscriminator 就不该写类名：老文档里没有它，写了就读不回去");
    }

    /**
     * <b>老文档里的 {@code widgets[].layout} 被跳过</b> —— 这是「顺序进线格式不需要迁移」
     * 那条论证的证据。
     *
     * <p>改造之前每张卡片都带一个 {@code layout: {x, y, w, h}}。现在 {@code WebDashboardWidget}
     * 上没有这个字段了，而<b>现网库里存着的文档还带着它</b>。这里手工造一份那种老文档
     * （{@code BsonDocument} 直接写，绕过实体），按 {@code WebDashboardLayoutEntity} 解码：
     * 只要 POJO codec 遇到类上没有的键是<b>跳过</b>而不是报错，老布局就读得出来，
     * 不需要任何迁移脚本。</p>
     *
     * <p>验证的是「这一侧的编解码行为」：与真落库时驱动走的是同一个注册表、同一条
     * {@code BsonDocumentReader} 路径。唯一没覆盖到的是真 Mongo 本身 —— 而它在这件事上
     * 只是把文档原样吐回来。</p>
     *
     * <p><b>注意别把老 {@code layout} 与现在的 {@code x}/{@code y} 搞混</b>：这一轮坐标
     * 回到了卡片上（平铺的两个数），那两个键是<b>认识</b>的；老 {@code layout} 这个<b>嵌套对象</b>
     * 仍然不认识、仍然被跳过。两条规则互不相干，下面那条用例钉着「认识的那个能读回来」。</p>
     *
     * <p><b>卡片 id 的 BSON 键是 {@code _id} 而不是 {@code id}</b>：POJO codec 会把一个名叫
     * {@code id} 的属性映射成 {@code _id}（下面 {@link #dashboardWidgetConfigRoundTrips} 那条
     * 往返用例不容易看出来，因为编码与解码用的是同一个名字）。手工造文档时这一点很关键 ——
     * 写成 {@code "id"} 不会报错，只会静默得到一张没有 id 的卡片。</p>
     */
    @Test
    void legacyLayoutKeyIsIgnored() {
        BsonDocument legacyWidget = new BsonDocument()
                .append("_id", new BsonString("w1"))
                .append("type", new BsonString("stat"))
                // 档位名也照着老文档写（`S` 是改造前的名字）：落库这一层不认识档位、
                // 也不认新旧，原样读回 —— 翻译成新名是 codec / 枚举那一层的事
                .append("size", new BsonString("S"))
                // 老文档里那个坐标对象。它嵌的深一层，是「未知的键」这条规则的更深一层证据
                .append("layout", new BsonDocument()
                        .append("x", new BsonInt32(6))
                        .append("y", new BsonInt32(4))
                        .append("w", new BsonInt32(6))
                        .append("h", new BsonInt32(4)))
                .append("config", new BsonDocument("metric", new BsonString("devices.total")));

        BsonDocument legacy = new BsonDocument()
                .append("spaceId", new BsonString("space-1"))
                .append("version", new BsonInt64(2L))
                .append("widgets", new BsonArray(List.of(legacyWidget)));

        WebDashboardLayoutEntity read = decode(WebDashboardLayoutEntity.class, legacy);

        assertEquals("space-1", read.spaceId);
        assertEquals(2L, read.version);
        assertEquals(1, read.widgets.size());
        WebDashboardWidget readWidget = read.widgets.get(0);
        assertEquals("w1", readWidget.id);
        assertEquals("stat", readWidget.type);
        assertEquals("devices.total", readWidget.config.get("metric"));
        // 再编回去：layout 不会出现 —— 第一次保存就把它从文档里洗掉了，
        // 「解出来再存回去」之后老键自然消失，这正是「不需要迁移」的具体含义
        assertTrue(!encode(read).getArray("widgets").get(0).asDocument().containsKey("layout"));
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
