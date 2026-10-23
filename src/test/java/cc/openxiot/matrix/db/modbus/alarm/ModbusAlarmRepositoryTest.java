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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModbusAlarmRepository} 里那些**纯静态、不需要数据库**的部分：
 * 重复键的识别、关闭与处理的写语句形状、以及开行状态值的折算。
 *
 * <p>单独测它们是因为：这些地方写错了都不会报错，只是行为悄悄不对 ——
 * 判漏了重复键会静默产生两条开着的告警（{@code Modbus-Alarm.md} 第七节）；
 * 整文档替换会抹掉并行的关闭字段；把缺失的 {@code handled} 当成已处理会让稳态零写失效。</p>
 */
class ModbusAlarmRepositoryTest {

    private static final ServerAddress ADDRESS = new ServerAddress("localhost");
    private static final ObjectId OID = new ObjectId("65f0c0ffee0123456789abcd");
    private static final Date AT = new Date(1_700_000_000_000L);

    @Test
    void recognizesDuplicateKeyWrite() {
        assertTrue(ModbusAlarmRepository.isDuplicateKey(write(11000)));
    }

    @Test
    void recognizesDuplicateKeyCommand() {
        assertTrue(ModbusAlarmRepository.isDuplicateKey(command(11000)));
    }

    @Test
    void recognizesDuplicateKeyConcern() {
        // 驱动那个「这就是重复键」的类型（com.mongodb.DuplicateKeyException）继承的是
        // WriteConcernException，**不是** MongoWriteException 的子类 —— 想当然会漏掉这一支
        assertTrue(ModbusAlarmRepository.isDuplicateKey(duplicateKey()));
    }

    @Test
    void digsThroughWrapping() {
        // 驱动或上层可能把原始异常包一层
        assertTrue(ModbusAlarmRepository.isDuplicateKey(
                new IllegalStateException("write failed", write(11000))));
        assertTrue(ModbusAlarmRepository.isDuplicateKey(
                new RuntimeException(new IllegalStateException(write(11000)))));
    }

    @Test
    void otherWriteErrorsAreNotDuplicateKeys() {
        // 别的写错误必须照常外传（调用方要记日志、要让那次评估失败），不能被当成「别人先开了」吞掉
        assertFalse(ModbusAlarmRepository.isDuplicateKey(write(121)));     // 文档校验失败
        assertFalse(ModbusAlarmRepository.isDuplicateKey(write(13)));
        assertFalse(ModbusAlarmRepository.isDuplicateKey(command(11601)));
        // 同族的写关注异常也只按错误码认，不按类型整类放行
        assertFalse(ModbusAlarmRepository.isDuplicateKey(concern(64)));
    }

    @Test
    void unrelatedFailuresAreNotDuplicateKeys() {
        assertFalse(ModbusAlarmRepository.isDuplicateKey(null));
        assertFalse(ModbusAlarmRepository.isDuplicateKey(new RuntimeException("boom")));
        assertFalse(ModbusAlarmRepository.isDuplicateKey(new java.io.IOException("read timed out")));
    }

    @Test
    void selfReferentialCauseTerminates() {
        // cause 自引用会让「顺着 cause 链走」变成死循环。JDK 的 initCause 已经拦掉了最直白的造法
        // （java.lang.IllegalArgumentException: Self-causation not permitted），但自定义的或
        // 反序列化出来的 Throwable 可以把自己报成 cause —— 判定的那道栏杆就是给这种输入准备的。
        assertFalse(ModbusAlarmRepository.isDuplicateKey(new SelfCaused()));
        // 外面再包一层：得穿过「包装」那一层之后再停下
        assertFalse(ModbusAlarmRepository.isDuplicateKey(new RuntimeException("outer", new SelfCaused())));
    }

    @Test
    void longCauseChainIsBounded() {
        // 深度上限是第二道栏杆：链很长时不能变成无界遍历（真丢了一个深层重复键也只是漏吞一次，
        // 调用方按普通失败处理 —— 比在这里绕不出来强）
        Throwable chain = new RuntimeException("root");
        for (int i = 0; i < 100; i++) {
            chain = new RuntimeException("wrap " + i, chain);
        }
        assertFalse(ModbusAlarmRepository.isDuplicateKey(chain));
    }

    // ===== 写语句的形状 =====

    @Test
    void closingAnAlarmMeansUnsettingTheOpenKey() {
        BsonDocument update = ModbusAlarmRepository
                .closeUpdate(AT, ModbusAlarm.CLOSE_VALUE).toBsonDocument();

        assertEquals(ModbusAlarm.CLOSE_VALUE, update.getDocument("$set").getString("closeType").getValue());
        assertEquals(AT.getTime(), update.getDocument("$set").getDateTime("recoveredAt").getValue());
        // 这一条才是「关闭」本身：openKey 是状态（采集侧的判定、列表筛选、汇总的 open 计数都看它）。
        // 只写 recoveredAt 而不 $unset 它，这条会永远被算作「还开着」
        assertTrue(update.getDocument("$unset").containsKey("openKey"));
    }

    @Test
    void closingTargetsTheWholeOpenKeyNotOneRow() {
        BsonDocument filter = ModbusAlarmRepository
                .closeFilter("svc-1", 2, List.of("进水温度", "回水温度")).toBsonDocument();

        // 按 serviceId + pollIndex + 一批 field 关（$in 而不是某一个），不是定位到某条 _id
        assertEquals("svc-1", clause(filter, "serviceId").asString().getValue());
        assertEquals(2, clause(filter, "pollIndex").asInt32().getValue());
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

        BsonDocument update = ModbusAlarmRepository.handleUpdate(who).toBsonDocument();
        // $set 而不是整文档替换：替换会把并行的「关闭」刚写的 recoveredAt / closeType 抹掉
        assertTrue(update.getDocument("$set").getBoolean("handled").getValue());
        BsonDocument handledBy = update.getDocument("$set").getDocument("handledBy");
        assertEquals("u-1", handledBy.getString("id").getValue());
        assertEquals("张三", handledBy.getString("name").getValue());
        assertEquals(AT.getTime(), handledBy.getDateTime("timestamp").getValue());
        // 替换文档的形状是把实体字段摊在顶层：这里一个都不该有
        assertFalse(update.containsKey("serviceId"));
        assertFalse(update.containsKey("at"));

        BsonDocument filter = ModbusAlarmRepository.handleFilter(OID).toBsonDocument();
        assertEquals(OID, clause(filter, "_id").asObjectId().getValue());
        // CAS：重复点击只生效一次
        assertTrue(clause(filter, "handled").asDocument().getBoolean("$ne").getValue());
    }

    @Test
    void missingHandledCountsAsUnhandled() {
        // 判定侧的状态值：这一条写错了不会报错，只会让**所有老数据**（handled 缺失）被当成
        // 「已经处理过」—— 于是每一轮采集都给它们关旧开新，稳态零写当场失效、告警列表每采一次翻一倍
        assertFalse(ModbusAlarmRepository.openRuleOf("r1", null).handled());
        assertFalse(ModbusAlarmRepository.openRuleOf("r1", false).handled());
        assertTrue(ModbusAlarmRepository.openRuleOf("r1", true).handled());
        // ruleId 原样带着：null 只可能出现在更早的数据里，判定侧与当前胜者比不相等时会走「被取代」
        assertEquals("r1", ModbusAlarmRepository.openRuleOf("r1", null).ruleId());
        assertNull(ModbusAlarmRepository.openRuleOf(null, false).ruleId());
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
        return new MongoWriteException(new WriteError(code, "write failed", new BsonDocument()), ADDRESS, List.of());
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
