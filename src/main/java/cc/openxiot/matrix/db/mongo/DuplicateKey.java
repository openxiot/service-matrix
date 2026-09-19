package cc.openxiot.matrix.db.mongo;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoWriteException;
import com.mongodb.WriteConcernException;

/**
 * 判断一条异步失败是不是 MongoDB 的「重复键」错误（码 11000）。
 *
 * <p>这是 Web/Mobile 看板布局仓库共同的乐观锁兜底：两个管理员同时创建第一份布局时，
 * 唯一索引会让后一个拿到 11000 —— 这与「版本不一致」是同一件事（别人先写成功了），
 * 服务层要把它翻译成正常的版本冲突提示，而不是一个 500。</p>
 *
 * <p><b>为什么按错误码而不是按异常类型判</b>：Quarkus/Panache 的异步层会把底层异常包在
 * {@code MutinyException} 里，可靠触达门面的类型逐个变过；唯一稳的是沿着 {@code cause} 链
 * 找到的那个带错误码的 Mongo 异常。三种异常形状（写/命令/写关注）都可能出现，
 * 一一具名而非用 {@code getCode()} 泛判，是为了在某个实现变了时让编译器先红。</p>
 */
public final class DuplicateKey {

    private static final int DUPLICATE_KEY = 11000;

    private DuplicateKey() {
    }

    public static boolean is(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof MongoWriteException write
                    && write.getError().getCode() == DUPLICATE_KEY) {
                return true;
            }
            if (current instanceof MongoCommandException command
                    && command.getErrorCode() == DUPLICATE_KEY) {
                return true;
            }
            if (current instanceof WriteConcernException concern
                    && concern.getErrorCode() == DUPLICATE_KEY) {
                return true;
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return false;
    }
}