package cc.openxiot.matrix.db.card;

import cc.openxiot.matrix.db.card.device.DeviceCard;
import cc.openxiot.matrix.db.card.fd.FdCard;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonDocumentWriter;
import org.bson.BsonObjectId;
import org.bson.BsonString;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.configuration.CodecConfigurationException;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 卡片配置的**落库形状**：不连 Mongo，按 POJO codec 编一遍，确认 {@code _id} 就是 did / fdid。
 *
 * <p>这条形状是 {@code scripts/migrate-card-config-id.js} 的前提：迁移脚本把旧文档的
 * ObjectId 主键换成 did / fdid 字符串。若某天 {@code @BsonId} 挪了位置或加了存储层 codec，
 * 这个用例会先红，提醒同步改脚本。</p>
 *
 * <p>也钉住「旧文档读不了」这件事：旧文档的 {@code _id} 是 ObjectId，解到 String 主键上会抛异常
 * —— 所以迁库和新后端部署必须紧挨着，没有互相兼容的中间态。</p>
 */
class CardConfigBsonTest {

    private static final String DID = "65f0c0ffee0123456789ab01";
    private static final String FDID = "65f0c0ffee0123456789ab02";
    private static final String SPACE_ID = "6512f3a4b5c6d7e8f9a0b1c3";

    @Test
    void deviceCardIsStoredWithDidAsUnderscoreId() {
        DeviceCard card = new DeviceCard();
        card.did = DID;
        card.spaceId = SPACE_ID;
        DeviceCard.ServiceSelection service = new DeviceCard.ServiceSelection();
        service.siid = 2;
        service.piids = List.of(5, 4);
        card.services = List.of(service);

        BsonDocument doc = encode(card, DeviceCard.class);

        assertEquals(DID, doc.getString("_id").getValue());
        assertFalse(doc.containsKey("id"), "不再有独立的 ObjectId 主键字段");
        assertEquals(SPACE_ID, doc.getString("spaceId").getValue());
        assertEquals(2, doc.getArray("services").get(0).asDocument().getInt32("siid").getValue());

        DeviceCard back = decode(doc, DeviceCard.class);
        assertEquals(DID, back.did);
        assertEquals(SPACE_ID, back.spaceId);
        assertEquals(List.of(5, 4), back.services.get(0).piids);
    }

    @Test
    void fdCardIsStoredWithFdidAsUnderscoreId() {
        FdCard card = new FdCard();
        card.fdid = FDID;
        card.spaceId = SPACE_ID;
        card.registers = List.of(3, 1);

        BsonDocument doc = encode(card, FdCard.class);

        assertEquals(FDID, doc.getString("_id").getValue());
        assertFalse(doc.containsKey("id"));
        assertEquals(SPACE_ID, doc.getString("spaceId").getValue());

        FdCard back = decode(doc, FdCard.class);
        assertEquals(FDID, back.fdid);
        assertEquals(List.of(3, 1), back.registers);
    }

    @Test
    void legacyObjectIdDocumentCannotBeDecoded() {
        // 旧文档：_id 是 ObjectId，did 是普通字段 —— 新形状读不了，这正是必须跑迁移的原因
        BsonDocument legacy = new BsonDocument()
                .append("_id", new BsonObjectId(new ObjectId(DID)))
                .append("spaceId", new BsonString(SPACE_ID))
                .append("did", new BsonString(DID))
                .append("services", new BsonArray());

        assertThrows(CodecConfigurationException.class, () -> decode(legacy, DeviceCard.class));
    }

    // ---- 编解码 ----

    private static <T> BsonDocument encode(T value, Class<T> type) {
        Codec<T> codec = registry().get(type);
        BsonDocument doc = new BsonDocument();
        codec.encode(new BsonDocumentWriter(doc), value, EncoderContext.builder().build());
        return doc;
    }

    private static <T> T decode(BsonDocument doc, Class<T> type) {
        Codec<T> codec = registry().get(type);
        return codec.decode(new BsonDocumentReader(doc), DecoderContext.builder().build());
    }

    /** 与 Quarkus 的默认客户端注册表同形：默认注册表 + automatic 的 POJO provider。 */
    private static CodecRegistry registry() {
        return CodecRegistries.fromRegistries(
                com.mongodb.MongoClientSettings.getDefaultCodecRegistry(),
                CodecRegistries.fromProviders(
                        PojoCodecProvider.builder()
                                .automatic(true)
                                .build()));
    }
}
