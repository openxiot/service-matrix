package cc.openxiot.matrix.api.modbus.alarm.reconciler;

import cc.openxiot.matrix.db.modbus.alarm.ModbusAlarm;
import cc.openxiot.matrix.db.modbus.alarm.ModbusAlarmRepository;
import cc.openxiot.matrix.db.modbus.fd.FieldDevice;
import cc.openxiot.matrix.db.modbus.fd.FieldDeviceRepository;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 告警的**定义变更对账**：把「新定义再也判定不到、却还开着」的告警行按 {@code DEFINITION} 关掉
 * （判据是纯函数 {@link ModbusAlarmPlan}，为什么需要它见那里的类注释）。
 *
 * <h2>两个时机，同一个实现</h2>
 *
 * <table border="1">
 *   <tr><th>时机</th><th>挂在哪</th><th>读什么</th></tr>
 *   <tr><td>定义变更</td><td>{@code FieldDeviceService} 的 update（与轮询任务、影子同一处）</td>
 *       <td>该服务的开行（带索引）</td></tr>
 *   <tr><td>启动</td><td>本类的 {@code StartupEvent}</td>
 *       <td>全部服务的定义 + 全部开行</td></tr>
 * </table>
 *
 * <p><b>不进周期对账</b>：与影子同一条理由 —— 陈旧只可能来自定义变更，而定义变更必经写入路径；
 * 剩下能绕过去的只有**直接改库**（运维脚本、迁移），启动那次兜住。周期扫要扫「服务 × 开行」，
 * 成本比它兜住的概率高太多。</p>
 *
 * <p><b>为什么不像轮询任务那样挂在调度器的 {@code StartupEvent} 上</b>：那个事件只在
 * {@code modbus.poll.enabled=true} 时才起调度器。而告警判定挂在**采集链路**上，手动 invoke 也会评 ——
 * 关掉轮询开关不该让残留从此没人扫。</p>
 *
 * <p><b>只写库，不动内存</b>：所以它没有 {@code ModbusPollingReconciler} 那种「跨进程要等下一次周期」
 * 的延迟问题 —— 在哪个副本上改的定义，关就在哪个副本上落库。</p>
 *
 * <p>失败照常上抛，由调用方处置：写入路径那边只记日志（与轮询任务、影子同壳），
 * 不让一次定义维护跟着失败；启动那次自己兜住。</p>
 */
@ApplicationScoped
public class ModbusAlarmReconciler {

    @Inject
    ModbusAlarmRepository alarmRepo;

    @Inject
    FieldDeviceRepository fdRepo;

    @Inject
    Logger logger;

    /**
     * 启动扫一次残留：**服务还在、定义却被绕开写入路径改了**（运维脚本、迁移、手工订正）留下的开行。
     *
     * <p><b>不覆盖「服务已被删除」留下的开行</b> —— {@link ModbusAlarmPlan} 对不认识的服务一律不动
     * （见那里的类注释：那是它最要紧的一条保守口径）。服务级联删除归
     * {@code FieldDeviceService} → {@code ModbusHistoryWriteService.purge}；那一趟失败时留下的行是存储垃圾，
     * 与影子 / 历史 / 故障记录同一个失败模式（只记日志、不重试），而且页面按空间子树查，看不见它。
     * 换句话说，这里与那里是**分工**：对账在后，级联在前。</p>
     *
     * <p>失败不阻断启动（与建索引同口径）：扫不掉的最坏后果是那几条告警多挂一会儿，
     * 而下一次定义变更或下一次重启会再来一遍。</p>
     */
    void init(@Observes StartupEvent event) {
        fdRepo.listAll()
                .chain(fd -> alarmRepo.listAllOpen()
                        .chain(open -> close(ModbusAlarmPlan.plan(fd, open))))
                .onFailure().invoke(e -> logger.warnv("sweep modbus alarms failed, error: {0}", e.getMessage()))
                .onFailure().recoverWithNull()
                .await().indefinitely();
    }

    /**
     * 某个服务的定义刚被改过：把它上面那些再也判定不到的开行关掉。
     *
     * <p>「再也不采」也算 —— 方法被删、{@code polling} 关掉、告警被整个关掉，三种在
     * {@link ModbusAlarmPlan} 里是同一条判据。</p>
     */
    public Uni<Void> reconcile(FieldDevice fd) {
        if (fd == null || fd.id == null) {
            return Uni.createFrom().voidItem();
        }
        return alarmRepo.listOpenByFdid(fd.id.toHexString())
                .chain(open -> close(ModbusAlarmPlan.plan(List.of(fd), open)));
    }

    /** 执行关闭。一个方法一条语句：关闭是按整个开行标识 {@code updateMany} 的。 */
    private Uni<Void> close(List<ModbusAlarmPlan.Close> closes) {
        if (closes.isEmpty()) {
            // 稳态（没有定义变更、也没有直接改库）走这条：读了两把，一个写操作都没有
            return Uni.createFrom().voidItem();
        }
        Date now = new Date();
        List<Uni<Void>> ops = new ArrayList<>(closes.size());
        for (ModbusAlarmPlan.Close close : closes) {
            ops.add(alarmRepo.closeByKeys(close.fdid(), close.pollIndex(), close.fields(),
                    ModbusAlarm.CLOSE_DEFINITION, now).replaceWithVoid());
        }
        int fields = closes.stream().mapToInt(close -> close.fields().size()).sum();
        return Uni.combine().all().unis(ops).discardItems()
                .invoke(() -> logger.infov(
                        "modbus alarms reconciled, stale open functions: {0}, fields: {1}",
                        closes.size(), fields));
    }
}
