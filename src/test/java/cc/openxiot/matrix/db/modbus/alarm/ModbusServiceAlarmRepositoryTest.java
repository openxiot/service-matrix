package cc.openxiot.matrix.db.modbus.alarm;

import cc.openxiot.common.person.Person;
import com.mongodb.DuplicateKeyException;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteConcernException;
import com.mongodb.WriteConcernResult;
import com.mongodb.WriteError;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModbusServiceAlarmRepository#isDuplicateKey} 的判定。
 *
 * <p>纯静态、不需要数据库。单独测它是因为：并发护栏的整套推理压在「重复键被认出来、然后吞掉」上
 * （{@code Modbus-Alarm.md} 第七节）——**判漏了就会静默产生两条开着的告警**，
 * 而那正是这个特性要避免的失效模式。</p>
 */
class ModbusServiceAlarmRepositoryTest {

    private static final ServerAddress ADDRESS = new ServerAddress("localhost");
    private static final ObjectId OID = new ObjectId("65f0c0ffee0123456789abcd");
    private static final Date AT = new Date(1_700_000_000_000L);

    @Test
    void recognizesDuplicateKeyWrite() {
        assertTrue(ModbusServiceAlarmRepository.isDuplicateKey(write(11000)));
    }

    @Test
    void recognizesDuplicateKeyCommand() {
        assertTrue(ModbusServiceAlarmRepository.isDuplicateKey(command(11000)));
    }

    @Test
    void recognizesDuplicateKeyConcern() {
        // 驱动那个「这就是重复键」的类型（com.mongodb.DuplicateKeyException）继承的是
        // WriteConcernException，**不是** MongoWriteException 的子类 —— 想当然会漏掉这一支
        assertTrue(ModbusServiceAlarmRepository.isDuplicateKey(duplicateKey()));
    }

    @Test
    void digsThroughWrapping() {
        // 驱动或上层可能把原始异常包一层
        assertTrue(ModbusServiceAlarmRepository.isDuplicateKey(
                new IllegalStateException("write failed", write(11000))));
        assertTrue(ModbusServiceAlarmRepository.isDuplicateKey(
                new RuntimeException(new IllegalStateException(write(11000)))));
    }

    @Test
    void otherWriteErrorsAreNotDuplicateKeys() {
        // 别的写错误必须照常外传（调用方要记日志、要让那次评估失败），不能被当成「别人先开了」吞掉
        assertFalse(ModbusServiceAlarmRepository.isDuplicateKey(write(121)));     // 文档校验失败
        assertFalse(ModbusServiceAlarmRepository.isDuplicateKey(write(13)));
        assertFalse(ModbusServiceAlarmRepository.isDuplicateKey(command(11601)));
        // 同族的写关注异常也只按错误码认，不按类型整类放行
        assertFalse(ModbusServiceAlarmRepository.isDuplicateKey(concern(64)));
    }

    @Test
    void unrelatedFailuresAreNotDuplicateKeys() {
        assertFalse(ModbusServiceAlarmRepository.isDuplicateKey(null));
        assertFalse(ModbusServiceAlarmRepository.isDuplicateKey(new RuntimeException("boom")));
        assertFalse(ModbusServiceAlarmRepository.isDuplicateKey(new java.io.IOException("read timed out")));
    }

    @Test
    void selfReferentialCauseTerminates() {
        // cause 自引用会让「顺着 cause 链走」变成死循环。JDK 的 initCause 已经拦掉了最直白的造法
        // （java.lang.IllegalArgumentException: Self-causation not permitted），但自定义的或
        // 反序列化出来的 Throwable 可以把自己报成 cause —— 判定的那道栏杆就是给这种输入准备的。
        assertFalse(ModbusServiceAlarmRepository.isDuplicateKey(new SelfCaused()));
        // 外面再包一层：得穿过「包装」那一层之后再停下
        assertFalse(ModbusServiceAlarmRepository.isDuplicateKey(new RuntimeException("outer", new SelfCaused())));
    }

    @Test
    void longCauseChainIsBounded() {
        // 深度上限是第二道栏杆：链很长时不能变成无界遍历（真丢了一个深层重复键也只是漏吞一次，
        // 调用方按普通失败处理 —— 比在这里绕不出来强）
        Throwable chain = new RuntimeException("root");
        for (int i = 0; i < 100; i++) {
            chain = new RuntimeException("wrap " + i, chain);
        }
        assertFalse(ModbusServiceAlarmRepository.isDuplicateKey(chain));
    }

    // ===== 写语句的形状 =====

    @Test
    void closingAnAlarmMeansUnsettingTheOpenKey() {
        BsonDocument update = ModbusServiceAlarmRepository
                .closeUpdate(AT, ModbusServiceAlarm.CLOSE_VALUE).toBsonDocument();

        assertEquals(ModbusServiceAlarm.CLOSE_VALUE, update.getDocument("$set").getString("closeType").getValue());
        assertEquals(AT.getTime(), update.getDocument("$set").getDateTime("recoveredAt").getValue());
        // 这一条才是「关闭」本身：openKey 是状态（采集侧的判定、列表筛选、汇总的 open 计数都看它）。
        // 只写 recoveredAt 而不 $unset 它，这条会永远被算作「还开着」
        assertTrue(update.getDocument("$unset").containsKey("openKey"));
    }

    @Test
    void closingTargetsTheWholeOpenKeyNotOneRow() {
        BsonDocument filter = ModbusServiceAlarmRepository
                .closeFilter("svc-1", 2, List.of("进水温度", "回水温度")).toBsonDocument();

        // 按 serviceId + functionIndex + 一批 field 关（$in 而不是某一个），不是定位到某条 _id
        assertEquals("svc-1", clause(filter, "serviceId").asString().getValue());
        assertEquals(2, clause(filter, "functionIndex").asInt32().getValue());
        assertEquals(2, clause(filter, "field").asDocument().getArray("$in").size());
        // 已恢复的行 openKey 已被 $unset，不该被再关一次而覆盖掉它第一次的 recoveredAt
        assertTrue(clause(filter, "openKey").asDocument().getBoolean("$exists").getValue());
    }

    @Test
    void handlingIsACasOnHandledAndWritesTheReceipt() {
        Person who = new Person();
        who.id = "u-1";
        who.name = "张三";
        who.timestamp = AT;

        BsonDocument update = ModbusServiceAlarmRepository.handleUpdate(who).toBsonDocument();
        // $set 而不是整文档替换：替换会把并行的「关闭」刚写的 recoveredAt / closeType 抹掉
        assertTrue(update.getDocument("$set").getBoolean("handled").getValue());
        BsonDocument handledBy = update.getDocument("$set").getDocument("handledBy");
        assertEquals("u-1", handledBy.getString("id").getValue());
        assertEquals("张三", handledBy.getString("name").getValue());
        assertEquals(AT.getTime(), handledBy.getDateTime("timestamp").getValue());
        // 替换文档的形状是把实体字段摊在顶层：这里一个都不该有
        assertFalse(update.containsKey("serviceId"));
        assertFalse(update.containsKey("at"));

        BsonDocument filter = ModbusServiceAlarmRepository.handleFilter(OID).toBsonDocument();
        assertEquals(OID, clause(filter, "_id").asObjectId().getValue());
        // CAS：重复点击只生效一次
        assertTrue(clause(filter, "handled").asDocument().getBoolean("$ne").getValue());
    }

    // ===== 夹具 =====

    /** 从一个（可能被 {@code Filters.and} 包进 {@code $and} 数组的）过滤条件里取出某个键的判据。 */
    private static BsonValue clause(BsonDocument filter, String key) {
        if (filter.containsKey(key)) {
            return filter.get(key);
        }
        for (BsonValue item : filter.getArray("$and")) {
            if (item.asDocument().containsKey(key)) {
                return item.asDocument().get(key);
            }
        }
        throw new AssertionError("no clause for " + key + " in " + filter.toJson());
    }

    private static MongoWriteException write(int code) {
        return new MongoWriteException(new WriteError(code, "write failed", new BsonDocument()), ADDRESS);
    }

    private static MongoCommandException command(int code) {
        return new MongoCommandException(new BsonDocument("code", new BsonInt32(code)), ADDRESS);
    }

    private static DuplicateKeyException duplicateKey() {
        return new DuplicateKeyException(concernResponse(11000), ADDRESS, acknowledged());
    }

    /** 同族但不是重复键的写关注异常（错误码取自响应文档，与 {@link #duplicateKey()} 同一形状）。 */
    private static WriteConcernException concern(int code) {
        return new WriteConcernException(concernResponse(code), ADDRESS, acknowledged());
    }

    private static BsonDocument concernResponse(int code) {
        return new BsonDocument("code", new BsonInt32(code))
                .append("errmsg", new BsonString("write concern error"));
    }

    private static WriteConcernResult acknowledged() {
        return WriteConcernResult.acknowledged(0, false, null);
    }

    /** 把自己报成 cause 的异常（{@code initCause} 造不出来，只能这样造）。 */
    private static final class SelfCaused extends RuntimeException {

        @Override
        public Throwable getCause() {
            return this;
        }
    }
}
