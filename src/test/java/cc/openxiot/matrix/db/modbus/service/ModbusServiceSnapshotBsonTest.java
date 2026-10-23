package cc.openxiot.matrix.db.modbus.service;

import cc.openxiot.matrix.db.modbus.config.ModbusConfig;
import cc.openxiot.matrix.db.modbus.config.ModbusRegistry;
import cc.openxiot.matrix.db.modbus.config.ModbusSlave;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonDocumentWriter;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 服务内嵌点表快照的**落库形状**：不连 Mongo，按 Panache/POJO codec（Quarkus 给 Mongo 客户端
 * 搭的那张默认注册表）编码一遍，确认快照是以 {@code _id}（ObjectId）存的。
 *
 * <p>这条形状是 {@code docs/migrations/modbus-service-config-snapshot.js} 的前提：迁移脚本把
 * {@code modbus.configs} 的原始文档**整段拷进** {@code services.configSnapshot}，靠的正是「两份
 * 文档同形」—— 点表集合里主键就叫 {@code _id}，服务里的快照也必须是 {@code _id}。若某天给
 * {@link ModbusConfig} 改了 {@code @BsonId} 或加了存储层 codec，这个用例会先红，提醒同步改脚本。</p>
 */
class ModbusServiceSnapshotBsonTest {

    private static final String CONFIG_ID = "65f0c0ffee0123456789abcd";

    @Test
    void snapshotIsStoredAsNestedDocumentWithUnderscoreId() {
        ModbusService service = new ModbusService();
        service.id = new ObjectId("65f0c0ffee0123456789abce");
        service.name = "1 号冷水机组";
        service.version = ModbusService.VERSION;
        service.configSnapshot = config();

        BsonDocument doc = encode(service);
        BsonDocument snapshot = doc.getDocument("configSnapshot");

        assertNotNull(snapshot);
        // 快照的主键与点表集合同形：_id（ObjectId），不是 id 字符串
        assertEquals(new ObjectId(CONFIG_ID), snapshot.getObjectId("_id").getValue());
        assertEquals("特灵", snapshot.getDocument("slave").getString("manufacturer").getValue());
        assertEquals("进水温度",
                snapshot.getArray("registries").get(0).asDocument().getString("name").getValue());
    }

    @Test
    void snapshotRoundTripsThroughBson() {
        ModbusService service = new ModbusService();
        service.id = new ObjectId("65f0c0ffee0123456789abce");
        service.configSnapshot = config();

        ModbusService back = decode(encode(service));

        assertNotNull(back.configSnapshot);
        assertEquals(new ObjectId(CONFIG_ID), back.configSnapshot.id);
        assertEquals("19XRV", back.configSnapshot.slave.model);
        assertEquals(1, back.configSnapshot.registries.size());
    }

    // ---- 夹具 / 编解码 ----

    private static ModbusConfig config() {
        ModbusConfig config = new ModbusConfig();
        config.id = new ObjectId(CONFIG_ID);
        ModbusSlave slave = new ModbusSlave();
        slave.manufacturer = "特灵";
        slave.model = "19XRV";
        slave.slaveId = 1;
        config.slave = slave;
        ModbusRegistry registry = new ModbusRegistry();
        registry.name = "进水温度";
        registry.index = 1;
        registry.area = "holding";
        registry.access = "R";
        config.registries = List.of(registry);
        config.commands = List.of();
        config.lifecycle = "development";
        return config;
    }

    private static BsonDocument encode(ModbusService service) {
        Codec<ModbusService> codec = registry().get(ModbusService.class);
        BsonDocument doc = new BsonDocument();
        codec.encode(new BsonDocumentWriter(doc), service, EncoderContext.builder().build());
        return doc;
    }

    private static ModbusService decode(BsonDocument doc) {
        Codec<ModbusService> codec = registry().get(ModbusService.class);
        return codec.decode(new BsonDocumentReader(doc), DecoderContext.builder().build());
    }

    /** 与 Quarkus 的默认客户端注册表同形：默认注册表 + automatic 的 POJO provider。 */
    private static org.bson.codecs.configuration.CodecRegistry registry() {
        return org.bson.codecs.configuration.CodecRegistries.fromRegistries(
                com.mongodb.MongoClientSettings.getDefaultCodecRegistry(),
                org.bson.codecs.configuration.CodecRegistries.fromProviders(
                        org.bson.codecs.pojo.PojoCodecProvider.builder()
                                .automatic(true)
                                .build()));
    }
}
