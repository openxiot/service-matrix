package cc.openxiot.matrix.api.modbus.polling;

import cc.openxiot.matrix.typedef.modbus.frame.ModbusFrameCodec;
import cc.openxiot.matrix.db.modbus.polling.ModbusPolling;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.transaction.ModbusTransaction;
import cc.openxiot.matrix.db.modbus.service.transaction.request.ModbusRequest;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * 「哪些方法该被轮询」的口径，以及定义 → 任务清单的纯转换（不碰库、不碰线程）。
 *
 * <p>入表条件三条**同时**成立（见 MODBUS.md 第七节）：</p>
 *
 * <ol>
 *   <li>{@code interval != null}（配了周期）；</li>
 *   <li>{@code polling != false}（三态：{@code null} = 有周期即跑，{@code false} = 留着周期先不跑）；</li>
 *   <li>请求帧是**读**功能码（fc 01/02/03/04）。</li>
 * </ol>
 *
 * <p>第三条与 {@code ModbusServiceValidator} 重复，是**故意**的：校验器管的是「写进来的定义合不合法」，
 * 这里管的是「库里已经存在的定义能不能被定时器跑」。历史数据、直接改库、将来放宽校验，
 * 都能让一份写方法带着 {@code interval} 出现在库里 —— 而让服务端定时往寄存器里写值是危险动作，
 * 判定的最后一道闸必须在执行侧，不能只在写入侧。</p>
 *
 * <p>所以这里对 {@code request} 一律空值安全：运行期数据不能假定已经过校验
 * （以前要 {@code parseHex} 取帧的第二字节、还得包一层 try/catch，结构化之后只是读一个字段，
 * 但「可能为 null」这条仍然成立 —— 直属改库、旧口径的文档都可能留在这里）。</p>
 */
public final class ModbusPollPlan {

    private ModbusPollPlan() {
    }

    /** 该不该入表：三条口径见类注释。 */
    public static boolean shouldPoll(ModbusTransaction function) {
        if (function == null || function.index == null) {
            return false;
        }
        if (function.interval == null || function.interval <= 0) {
            return false;
        }
        if (Boolean.FALSE.equals(function.polling)) {
            return false;
        }
        return ModbusFrameCodec.isRead(functionFc(function.request));
    }

    /** 请求定义的功能码（{@code request} 缺失时返回 {@code null}，{@code isRead} 认它作非读）。 */
    public static String functionFc(ModbusRequest request) {
        return request == null ? null : request.fc;
    }

    /**
     * 一份定义 → 该服务的任务清单（{@code transactionIndex} 升序，不改动入参），
     * 输入是**未落库**的 {@link ModbusPolling}（{@code _id} 由**这里**生成，插入时原样落库）。
     *
     * <p>{@code _id} 不等插入回写（回写时机由驱动/编解码器决定，不是这条链路能假定的）：
     * 内存时间轮里的任务必须在**放进去的那一刻**就有 id —— 抢租约、写结果、认领都靠它，
     * 而没有 id 的任务只能每周期空转一遍。所以 id 在计划里就定下来，插入与内存表用同一个。
     * 库里**已经有这一行**时以行上的 {@code _id} 为准（见 {@code ModbusPollReconciler}）。</p>
     *
     * <p>{@code did} / {@code interval} 冗余进任务表只为调度：按设备串行、算下一次时刻都不必回查定义。
     * 定义改了由对账跟着改（见 {@code ModbusPollReconciler}），这张表随时可以全量重建。</p>
     *
     * @param did 父设备 id（{@code service.device.did}）；为空的定义不可调度，不入表
     */
    public static List<ModbusPolling> tasksOf(ModbusService service, String did, long now,
                                           int jitterPercent, RandomGenerator random) {
        List<ModbusPolling> tasks = new ArrayList<>();
        if (service == null || service.id == null || did == null || did.isBlank()
                || service.transactions == null) {
            return tasks;
        }
        String serviceId = service.id.toHexString();
        for (ModbusTransaction function : service.transactions) {
            if (!shouldPoll(function)) {
                continue;
            }
            ModbusPolling task = new ModbusPolling();
            task.id = new ObjectId();
            task.serviceId = serviceId;
            task.transactionIndex = function.index;
            task.did = did;
            task.interval = function.interval;
            task.nextRunAt = new Date(ModbusPollSchedule.insertRunAt(now, function.interval,
                    jitterPercent, random));
            tasks.add(task);
        }
        tasks.sort((left, right) -> Integer.compare(left.transactionIndex, right.transactionIndex));
        return tasks;
    }
}
