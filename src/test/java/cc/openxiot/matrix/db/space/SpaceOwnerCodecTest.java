package cc.openxiot.matrix.db.space;

import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonDocumentWriter;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import com.mongodb.MongoClientSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回归测试：嵌套类字段名恰好叫 "id" 时，MongoDB PojoCodec 会默认把它当成文档 _id 字段，
 * 导致读真实数据（字段名是 "id"）时拿不到值。SpaceOwner.id 用 @BsonProperty("id") 显式
 * 指定 BSON 字段名，绕开该约定。此测试在内存中编解码验证字段映射，不依赖数据库。
 */
class SpaceOwnerCodecTest {

    @Test
    void idFieldMapsToBsonId_notUnderscoreId() {
        // 与生产一致：driver 默认注册表（String/ObjectId 等内建 codec）+ 追加 pojo provider
        CodecRegistry registry = CodecRegistries.fromRegistries(
                MongoClientSettings.getDefaultCodecRegistry(),
                CodecRegistries.fromProviders(
                        PojoCodecProvider.builder().register(SpaceOwner.class).build()));
        Codec<SpaceOwner> codec = registry.get(SpaceOwner.class);

        SpaceOwner owner = new SpaceOwner("cct", "organization", "admin");

        BsonDocument out = new BsonDocument();
        codec.encode(new BsonDocumentWriter(out), owner, EncoderContext.builder().build());

        // 关键断言：字段必须写为 "id"，而不是被 driver 默认映射成 "_id"
        assertTrue(out.containsKey("id"), "字段应写为 id，而不是被映射成 _id：" + out.toJson());
        assertEquals("cct", out.getString("id").getValue());

        SpaceOwner back = codec.decode(new BsonDocumentReader(out), DecoderContext.builder().build());
        assertEquals("cct", back.id);
        assertEquals(SpaceOwnerType.ORGANIZATION, back.type);
        assertEquals(SpaceOwnerRole.ADMIN, back.role);
    }
}
