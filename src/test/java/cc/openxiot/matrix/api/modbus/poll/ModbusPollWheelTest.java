package cc.openxiot.matrix.api.modbus.poll;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModbusPollWheel}：内存时间轮（不碰库、不碰线程，可以直接单测）。
 *
 * <p>钉住三条不变量（它们的失效方式都是**静默**的：任务不再跑、或者同一条总线上出现两帧，
 * 都不会抛异常）：排序键只能在轮外改、在飞 = 不在轮里、同一个 did 最多一个在飞。</p>
 */
class ModbusPollWheelTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final int LIMIT = 10;

    @Test
    void pumpTakesDueTasksInRunOrder() {
        ModbusPollWheel wheel = new ModbusPollWheel();
        wheel.offer(task("svc-b", 1, "did-1", NOW - 100), NOW - 100);
        wheel.offer(task("svc-a", 1, "did-2", NOW - 300), NOW - 300);
        wheel.offer(task("svc-c", 1, "did-3", NOW + 5_000), NOW + 5_000);

        List<ModbusPollTask> due = wheel.pump(NOW, LIMIT);

        assertEquals(2, due.size());
        assertEquals("svc-a", due.get(0).serviceId);     // 最旧的先跑
        assertEquals("svc-b", due.get(1).serviceId);
        assertEquals(1, wheel.waitingCount());           // 未到点的留在轮里
        assertEquals(2, wheel.inFlightCount());
    }

    /** 到期是闭区间：正好等于当下就该派发（否则要白等一个唤醒周期）。 */
    @Test
    void pumpIncludesTheExactMoment() {
        ModbusPollWheel wheel = new ModbusPollWheel();
        wheel.offer(task("svc-a", 1, "did-1", NOW), NOW);

        assertEquals(1, wheel.pump(NOW, LIMIT).size());
    }

    /** 并发上限：取出来的个数不超过 concurrency，剩下的下一轮再说。 */
    @Test
    void pumpRespectsTheConcurrencyLimit() {
        ModbusPollWheel wheel = new ModbusPollWheel();
        for (int i = 0; i < 5; i++) {
            wheel.offer(task("svc-" + i, 1, "did-" + i, NOW), NOW);
        }

        assertEquals(2, wheel.pump(NOW, 2).size());
        assertEquals(3, wheel.waitingCount());
        assertEquals(2, wheel.inFlightCount());

        // 在飞的占用着名额，这一轮只剩 0 个空位
        assertEquals(0, wheel.pump(NOW, 2).size());
        assertEquals(3, wheel.waitingCount());
    }

    /** 同一条 RS485 总线不能并发：同一个 did 只派发一个，另一个**留在轮里**等它空。 */
    @Test
    void pumpSerializesByDevice() {
        ModbusPollWheel wheel = new ModbusPollWheel();
        ModbusPollTask first = task("svc-a", 1, "did-1", NOW - 100);
        ModbusPollTask second = task("svc-b", 1, "did-1", NOW - 50);
        wheel.offer(first, NOW - 100);
        wheel.offer(second, NOW - 50);

        List<ModbusPollTask> due = wheel.pump(NOW, LIMIT);

        assertEquals(1, due.size());
        assertEquals("svc-a", due.get(0).serviceId);
        assertEquals(1, wheel.busyDeviceCount());
        assertEquals(1, wheel.waitingCount());     // 不是被丢弃，也不是被重排

        // 前一个跑完，后一个立刻被取到（它早就到点了，一直等着）
        wheel.finish(due.get(0), NOW + 10_000);
        List<ModbusPollTask> next = wheel.pump(NOW, LIMIT);
        assertEquals(1, next.size());
        assertEquals("svc-b", next.get(0).serviceId);
    }

    /** 在飞 = 不在轮里：同一个任务不可能被派发两次（重入保护是结构性的，不靠标记判断）。 */
    @Test
    void pumpNeverReturnsATaskTwice() {
        ModbusPollWheel wheel = new ModbusPollWheel();
        ModbusPollTask task = task("svc-a", 1, "did-1", NOW - 100);
        wheel.offer(task, NOW - 100);

        assertEquals(1, wheel.pump(NOW, LIMIT).size());
        assertTrue(wheel.pump(NOW + 60_000, LIMIT).isEmpty());   // 又过了一分钟，它还在飞

        // 直到回填为止都不会再被取到 —— 哪怕它「早就又到期了」
        wheel.finish(task, NOW + 60_000);
        assertEquals(1, wheel.pump(NOW + 60_000, LIMIT).size());
    }

    @Test
    void finishPutsTheTaskBackWithTheNewRunAt() {
        ModbusPollWheel wheel = new ModbusPollWheel();
        ModbusPollTask task = task("svc-a", 1, "did-1", NOW);
        wheel.offer(task, NOW);

        wheel.finish(wheel.pump(NOW, LIMIT).get(0), NOW + 10_000);

        assertEquals(0, wheel.inFlightCount());
        assertEquals(0, wheel.busyDeviceCount());      // 设备也放开了
        assertEquals(1, wheel.waitingCount());
        assertTrue(wheel.pump(NOW, LIMIT).isEmpty());  // 新的时刻还没到
        assertEquals(1, wheel.pump(NOW + 10_000, LIMIT).size());
    }

    /** 对账要删的任务正在飞：不打断这一帧，跑完自然离场。 */
    @Test
    void removeOnAnInFlightTaskRetiresItOnFinish() {
        ModbusPollWheel wheel = new ModbusPollWheel();
        ModbusPollTask task = task("svc-a", 1, "did-1", NOW);
        wheel.offer(task, NOW);
        ModbusPollTask live = wheel.pump(NOW, LIMIT).get(0);

        wheel.remove(live.key());

        assertEquals(0, wheel.waitingCount());         // 没被放回去
        assertTrue(wheel.keys().contains(live.key()));  // 还在飞，键还在（对账据此不会重复插）

        wheel.finish(live, NOW + 10_000);
        assertEquals(0, wheel.waitingCount());
        assertEquals(0, wheel.inFlightCount());
        assertTrue(wheel.keys().isEmpty());            // 彻底离场
    }

    @Test
    void removeOnAWaitingTaskDropsItRightAway() {
        ModbusPollWheel wheel = new ModbusPollWheel();
        ModbusPollTask task = task("svc-a", 1, "did-1", NOW + 5_000);
        wheel.offer(task, NOW + 5_000);

        wheel.remove(task.key());

        assertEquals(0, wheel.waitingCount());
        assertTrue(wheel.keys().isEmpty());
        assertTrue(wheel.pump(NOW + 60_000, LIMIT).isEmpty());
        wheel.remove(task.key());                      // 重复删不抛
    }

    @Test
    void offerAddsANewTask() {
        ModbusPollWheel wheel = new ModbusPollWheel();
        ModbusPollTask task = task("svc-a", 1, "did-1", 0);

        assertTrue(wheel.offer(task, NOW + 1_000));
        assertEquals(1, wheel.waitingCount());
        assertTrue(wheel.keys().contains(task.key()));
    }

    /** 定义改了周期：在轮里的任务就地更新（排序键先摘后改，见类注释）。 */
    @Test
    void offerUpdatesAWaitingTask() {
        ModbusPollWheel wheel = new ModbusPollWheel();
        ModbusPollTask task = task("svc-a", 1, "did-1", NOW + 5_000);
        wheel.offer(task, NOW + 5_000);

        ModbusPollTask updated = task("svc-a", 1, "did-1", 0);
        updated.intervalSeconds = 60;
        updated.ownerId = "space-2";
        assertTrue(wheel.offer(updated, NOW + 1_000));

        assertEquals(1, wheel.waitingCount());         // 没变成两条
        assertEquals(NOW + 1_000, wheel.pump(NOW + 1_000, LIMIT).get(0).nextRunAt);
        assertEquals(60, task.intervalSeconds);        // 更新落在原来那个对象上
        assertEquals("space-2", task.ownerId);
    }

    /** 在飞的任务被更新：只改周期与归属，**下一次时刻由 finish 自己算**（不能被对账改成过去的时刻）。 */
    @Test
    void offerOnAnInFlightTaskOnlyUpdatesInterval() {
        ModbusPollWheel wheel = new ModbusPollWheel();
        ModbusPollTask task = task("svc-a", 1, "did-1", NOW);
        wheel.offer(task, NOW);
        ModbusPollTask live = wheel.pump(NOW, LIMIT).get(0);

        ModbusPollTask updated = task("svc-a", 1, "did-1", 0);
        updated.intervalSeconds = 60;
        assertTrue(wheel.offer(updated, NOW + 1_000));

        assertEquals(60, live.intervalSeconds);
        assertEquals(0, wheel.waitingCount());         // 没有偷偷放回轮里
        assertEquals(1, wheel.inFlightCount());
    }

    /** 在飞的任务换了设备：这一轮不动它（在飞的那一帧不能换设备），留给下一次对账。 */
    @Test
    void offerOnAnInFlightTaskWithANewDeviceIsRefused() {
        ModbusPollWheel wheel = new ModbusPollWheel();
        ModbusPollTask task = task("svc-a", 1, "did-1", NOW);
        wheel.offer(task, NOW);
        ModbusPollTask live = wheel.pump(NOW, LIMIT).get(0);

        assertFalse(wheel.offer(task("svc-a", 1, "did-2", 0), NOW + 1_000));

        assertEquals("did-1", live.did);
        assertEquals(1, wheel.inFlightCount());
    }

    /** 在轮里换设备：整条替换（did 是 final，改不了），旧对象离场。 */
    @Test
    void offerOnAWaitingTaskWithANewDeviceReplacesIt() {
        ModbusPollWheel wheel = new ModbusPollWheel();
        ModbusPollTask task = task("svc-a", 1, "did-1", NOW + 5_000);
        wheel.offer(task, NOW + 5_000);

        ModbusPollTask replaced = task("svc-a", 1, "did-2", 0);
        assertTrue(wheel.offer(replaced, NOW + 1_000));

        assertEquals(1, wheel.waitingCount());
        assertEquals("did-2", wheel.pump(NOW + 1_000, LIMIT).get(0).did);
    }

    @Test
    void keysCoverWaitingAndInFlightTasks() {
        ModbusPollWheel wheel = new ModbusPollWheel();
        wheel.offer(task("svc-a", 1, "did-1", NOW), NOW);
        wheel.offer(task("svc-b", 2, "did-2", NOW + 60_000), NOW + 60_000);

        wheel.pump(NOW, LIMIT);

        assertEquals(2, wheel.keys().size());
        assertTrue(wheel.keys().contains(new ModbusPollKey("svc-a", 1)));
        assertTrue(wheel.keys().contains(new ModbusPollKey("svc-b", 2)));
    }

    @Test
    void clearEmptiesTheWheel() {
        ModbusPollWheel wheel = new ModbusPollWheel();
        wheel.offer(task("svc-a", 1, "did-1", NOW), NOW);
        wheel.offer(task("svc-b", 1, "did-2", NOW + 60_000), NOW + 60_000);

        wheel.clear();

        assertEquals(0, wheel.waitingCount());
        assertTrue(wheel.keys().isEmpty());
    }

    private static ModbusPollTask task(String serviceId, int index, String did, long nextRunAt) {
        ModbusPollTask task = new ModbusPollTask(new ObjectId(), serviceId, index, did);
        task.intervalSeconds = 10;
        task.ownerId = "space-1";
        task.nextRunAt = nextRunAt;
        return task;
    }
}
