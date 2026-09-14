package cc.openxiot.matrix.api.modbus.poll;

import cc.openxiot.matrix.db.modbus.poll.ModbusPoll;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.ModbusServiceFunction;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModbusPollPlan}：谁会进任务表。
 *
 * <p>最要紧的一条是**写方法不入表**：让服务端定时往寄存器里写值是危险动作，
 * 校验器那道闸拦的是「写进来的定义」，而库里可能已经有历史数据、或被人直接改过 ——
 * 执行侧必须自己再判一次。这个用例就是钉住那道闸。</p>
 */
class ModbusPollPlanTest {

    private static final long NOW = 1_700_000_000_000L;

    /** 读保持寄存器（fc 03）。 */
    private static final String READ_FRAME = "010300000001840A";

    /** 写单寄存器（fc 06）。 */
    private static final String WRITE_FRAME = "0106000100C864";

    @Test
    void pollingTrueWithIntervalIsPolled() {
        assertTrue(ModbusPollPlan.shouldPoll(function(1, READ_FRAME, 10, Boolean.TRUE)));
    }

    /** 三态里的 null：配了周期就是要跑，不必再显式写一个 true。 */
    @Test
    void defaultPollingWithIntervalIsPolled() {
        assertTrue(ModbusPollPlan.shouldPoll(function(1, READ_FRAME, 10, null)));
    }

    /** 留着周期先不跑：定义不动，只把开关关掉。 */
    @Test
    void pollingFalseIsNotPolled() {
        assertTrue(!ModbusPollPlan.shouldPoll(function(1, READ_FRAME, 10, Boolean.FALSE)));
    }

    @Test
    void withoutIntervalIsNotPolled() {
        assertTrue(!ModbusPollPlan.shouldPoll(function(1, READ_FRAME, null, Boolean.TRUE)));
        assertTrue(!ModbusPollPlan.shouldPoll(function(1, READ_FRAME, 0, Boolean.TRUE)));
    }

    /** 写方法即便被人手工塞了 interval 也不入表（fc 05 / 06 / 0F / 10 逐个过一遍）。 */
    @Test
    void writeFunctionsAreNotPolledEvenWithAnInterval() {
        String[] writeFrames = {
                "01050001FF00",   // fc 05
                WRITE_FRAME,      // fc 06
                "010F0001000801",   // fc 0F
                "01100001000102",   // fc 10
        };
        for (String frame : writeFrames) {
            assertTrue(!ModbusPollPlan.shouldPoll(function(1, frame, 10, Boolean.TRUE)),
                    "write frame must not be polled: " + frame);
        }
    }

    /** 运行期数据不能假定已过校验：解析不出来的帧一律不入表，而不是把对账带崩。 */
    @Test
    void unparsableRequestsAreNotPolled() {
        assertNull(ModbusPollPlan.functionFc(null));
        assertNull(ModbusPollPlan.functionFc(""));
        assertNull(ModbusPollPlan.functionFc("01"));        // 只有一个字节
        assertNull(ModbusPollPlan.functionFc("ZZZZ"));      // 不是 16 进制
        assertNull(ModbusPollPlan.functionFc("010"));       // 奇数长度
        assertTrue(!ModbusPollPlan.shouldPoll(function(1, null, 10, Boolean.TRUE)));
        assertTrue(!ModbusPollPlan.shouldPoll(function(1, "01", 10, Boolean.TRUE)));
        assertTrue(!ModbusPollPlan.shouldPoll(function(1, "ZZZZ", 10, Boolean.TRUE)));
    }

    @Test
    void functionFcReadsTheSecondByte() {
        assertEquals("03", ModbusPollPlan.functionFc(READ_FRAME));
        assertEquals("06", ModbusPollPlan.functionFc(WRITE_FRAME));
        assertEquals("03", ModbusPollPlan.functionFc("01 03 00 00"));   // 空白不影响
        assertEquals("03", ModbusPollPlan.functionFc("0103"));          // 短帧也认
    }

    /** 缺 index 的方法没有键，进不了表。 */
    @Test
    void withoutIndexIsNotPolled() {
        ModbusServiceFunction function = function(null, READ_FRAME, 10, Boolean.TRUE);
        assertTrue(!ModbusPollPlan.shouldPoll(function));
        assertTrue(!ModbusPollPlan.shouldPoll(null));
    }

    /** 一份定义 → 只留下该轮询的那些，按 functionIndex 升序。 */
    @Test
    void tasksOfKeepsOnlyPollableFunctionsInIndexOrder() {
        ModbusService service = service(3, 1, 2);
        service.functions.get(0).interval = 30;               // index 3
        service.functions.get(1).interval = 10;               // index 1
        service.functions.get(2).interval = 10;
        service.functions.get(2).polling = Boolean.FALSE;     // index 2 被关掉

        List<ModbusPoll> tasks = ModbusPollPlan.tasksOf(service, "did-1", NOW, 0, new Random(1L));

        assertEquals(2, tasks.size());
        assertEquals(1, tasks.get(0).functionIndex);
        assertEquals(3, tasks.get(1).functionIndex);
        for (ModbusPoll task : tasks) {
            assertEquals(service.id.toHexString(), task.serviceId);
            assertEquals("did-1", task.did);
            assertNotNull(task.id);                           // id 在这里就定下来，不等插入回写
            assertNull(task.lastStatus);
            assertNull(task.leaseOwner);
            assertTrue(task.nextRunAt.getTime() > NOW);        // 不立刻跑第一次
        }
        assertEquals(10, tasks.get(0).interval);
        assertEquals(30, tasks.get(1).interval);
    }

    /** 周期不同的任务排出来的时刻不同（每个方法各自从当下起算一个周期）。 */
    @Test
    void tasksOfSchedulesEachFunctionByItsOwnInterval() {
        ModbusService service = service(1, 2);
        service.functions.get(0).interval = 10;
        service.functions.get(1).interval = 60;

        List<ModbusPoll> tasks = ModbusPollPlan.tasksOf(service, "did-1", NOW, 0, new Random(1L));

        assertEquals(NOW + 10_000, tasks.get(0).nextRunAt.getTime());
        assertEquals(NOW + 60_000, tasks.get(1).nextRunAt.getTime());
    }

    /** 没有父设备的定义不可调度：归属（did → 空间）反推不出来。 */
    @Test
    void tasksOfWithoutDeviceIsEmpty() {
        ModbusService service = service(1);
        service.functions.get(0).interval = 10;

        assertTrue(ModbusPollPlan.tasksOf(service, null, NOW, 0, new Random(1L)).isEmpty());
        assertTrue(ModbusPollPlan.tasksOf(service, "  ", NOW, 0, new Random(1L)).isEmpty());
    }

    /** 未落库的定义（id 为空）算不出键，什么都不产出 —— 也不抛。 */
    @Test
    void tasksOfWithoutIdIsEmpty() {
        ModbusService service = service(1);
        service.functions.get(0).interval = 10;
        service.id = null;

        assertTrue(ModbusPollPlan.tasksOf(service, "did-1", NOW, 0, new Random(1L)).isEmpty());
    }

    @Test
    void tasksOfWithoutFunctionsIsEmpty() {
        ModbusService service = service(1);
        service.functions = null;

        assertTrue(ModbusPollPlan.tasksOf(service, "did-1", NOW, 0, new Random(1L)).isEmpty());
        assertTrue(ModbusPollPlan.tasksOf(null, "did-1", NOW, 0, new Random(1L)).isEmpty());
    }

    // -- helpers --

    private static ModbusServiceFunction function(Integer index, String request, Integer interval, Boolean polling) {
        ModbusServiceFunction function = new ModbusServiceFunction();
        function.index = index;
        function.request = request;
        function.interval = interval;
        function.polling = polling;
        return function;
    }

    /** 建一个带 n 个读方法（index 1..n）的服务，周期都还没配。 */
    private static ModbusService service(int... indexes) {
        ModbusService service = new ModbusService();
        service.id = new ObjectId();
        service.name = "svc";
        service.functions = new ArrayList<>();
        for (int index : indexes) {
            service.functions.add(function(index, READ_FRAME, null, null));
        }
        return service;
    }
}
