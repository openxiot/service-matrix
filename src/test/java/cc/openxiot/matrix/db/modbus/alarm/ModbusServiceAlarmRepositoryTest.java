package cc.openxiot.matrix.db.modbus.alarm;

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
import org.junit.jupiter.api.Test;

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

    // ===== 夹具 =====

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
