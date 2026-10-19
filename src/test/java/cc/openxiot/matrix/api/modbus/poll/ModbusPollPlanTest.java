package cc.openxiot.matrix.api.modbus.poll;

import cc.openxiot.matrix.db.modbus.service.polling.ModbusPollingFrom;
import cc.openxiot.matrix.db.modbus.service.polling.ModbusPolling;
import cc.openxiot.matrix.db.modbus.poll.ModbusPoll;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.polling.request.ModbusPollingRequest;
import cc.openxiot.matrix.db.modbus.service.polling.request.ModbusPollingRequests;
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
    private static final ModbusPollingRequest READ_REQUEST = ModbusPollingRequests.holding();

    /** 写单寄存器（fc 06）。 */
    private static final ModbusPollingRequest WRITE_REQUEST = ModbusPollingRequests.write("06", 1);

    @Test
    void pollingTrueWithIntervalIsPolled() {
        assertTrue(ModbusPollPlan.shouldPoll(function(1, READ_REQUEST, 10, Boolean.TRUE)));
    }

    /** 三态里的 null：配了周期就是要跑，不必再显式写一个 true。 */
    @Test
    void defaultPollingWithIntervalIsPolled() {
        assertTrue(ModbusPollPlan.shouldPoll(function(1, READ_REQUEST, 10, null)));
    }

    /** 留着周期先不跑：定义不动，只把开关关掉。 */
    @Test
    void pollingFalseIsNotPolled() {
        assertTrue(!ModbusPollPlan.shouldPoll(function(1, READ_REQUEST, 10, Boolean.FALSE)));
    }

    @Test
    void withoutIntervalIsNotPolled() {
        assertTrue(!ModbusPollPlan.shouldPoll(function(1, READ_REQUEST, null, Boolean.TRUE)));
        assertTrue(!ModbusPollPlan.shouldPoll(function(1, READ_REQUEST, 0, Boolean.TRUE)));
    }

    /** 写方法即便被人手工塞了 interval 也不入表（fc 05 / 06 / 0F / 10 逐个过一遍）。 */
    @Test
    void writeFunctionsAreNotPolledEvenWithAnInterval() {
        for (String fc : List.of("05", "06", "0F", "10")) {
            ModbusPollingRequest request = ModbusPollingRequests.write(fc, 1);
            assertTrue(!ModbusPollPlan.shouldPoll(function(1, request, 10, Boolean.TRUE)),
                    "write function must not be polled: fc " + fc);
        }
    }

    /**
     * 运行期数据不能假定已过校验：{@code request} 缺失（老数据）、或 {@code fc} 缺失（手工改库），
     * 一律不入表，而不是把对账带崩。
     */
    @Test
    void requestsWithoutFunctionCodeAreNotPolled() {
        assertNull(ModbusPollPlan.functionFc(null));
        assertNull(ModbusPollPlan.functionFc(new ModbusPollingRequest()));
        assertTrue(!ModbusPollPlan.shouldPoll(function(1, null, 10, Boolean.TRUE)));
        assertTrue(!ModbusPollPlan.shouldPoll(function(1, new ModbusPollingRequest(), 10, Boolean.TRUE)));
    }

    @Test
    void functionFcReadsTheRequestFunctionCode() {
        // functionFc 原样返回（大小写不在这里归一，判定读写的 isRead 自己归一）
        assertEquals("03", ModbusPollPlan.functionFc(READ_REQUEST));
        assertEquals("06", ModbusPollPlan.functionFc(WRITE_REQUEST));
        assertEquals("0f", ModbusPollPlan.functionFc(ModbusPollingRequests.write("0f", 0)));
        // 小写 fc 照样算写方法
        assertTrue(!ModbusPollPlan.shouldPoll(
                function(1, ModbusPollingRequests.write("0f", 1), 10, Boolean.TRUE)));
    }

    /** 缺 index 的方法没有键，进不了表。 */
    @Test
    void withoutIndexIsNotPolled() {
        ModbusPolling function = function(null, READ_REQUEST, 10, Boolean.TRUE);
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
        assertEquals(1, tasks.get(0).pollingIndex);
        assertEquals(3, tasks.get(1).pollingIndex);
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

    private static ModbusPolling function(Integer index, ModbusPollingRequest request, Integer interval,
                                                   Boolean polling) {
        ModbusPolling function = new ModbusPolling();
                    function.from = new ModbusPollingFrom();
                    function.from.type = "command";
                    function.from.index = index;
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
            service.functions.add(function(index, READ_REQUEST, null, null));
        }
        return service;
    }
}
