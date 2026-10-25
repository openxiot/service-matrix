package cc.openxiot.matrix.api.modbus.polling;

import cc.openxiot.matrix.db.modbus.device.poll.ModbusPollFrom;
import cc.openxiot.matrix.db.modbus.device.poll.ModbusPoll;
import cc.openxiot.matrix.db.modbus.shadow.ModbusShadowRepository;
import cc.openxiot.matrix.db.modbus.polling.ModbusPolling;
import cc.openxiot.matrix.db.modbus.polling.ModbusPollingRepository;
import cc.openxiot.matrix.db.modbus.device.DeviceOn;
import cc.openxiot.matrix.db.modbus.device.ModbusDevice;
import cc.openxiot.matrix.db.modbus.device.poll.request.ModbusRequests;
import cc.openxiot.matrix.db.modbus.device.ModbusDeviceRepository;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.matrix.db.space.SpaceOn;
import cc.openxiot.matrix.db.space.SpaceRepository;
import io.smallrye.mutiny.Uni;
import org.bson.types.ObjectId;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ModbusPollingReconciler} 的一条不变式：**进时间轮的任务必须带上库里那一行的 {@code _id}**。
 *
 * <p>这条不变式被现网打破过：库里**已经有这一行**时，计划对象（{@code ModbusPollingPlan.tasksOf} 现算的）
 * 身上没有 id，而当时内存任务就是从这个对象上取 id 的 —— 于是任务每周期进一次轮、
 * {@code claim} 每次都因为 {@code task.id == null} 跳过：日志里每周期一条
 * {@code modbus poll task has no id}，而设备一次都没被采到。
 * 这里的三个用例分别钉住三条路径：库里已有行、库里还没有行、行被删了又插（换了 {@code _id}）。</p>
 *
 * <p>全部走 mock，不连 Mongo。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModbusPollingReconcilerTest {

    private static final String SID = "6aa759bb4e45b0b6122d2ed5";
    private static final String DID = "0324C2F9BE34A6F8920CA026A88CE087";
    private static final String SPACE_ID = "6a73e868e364a1faeafddf18";
    private static final long NOW = 1_700_000_000_000L;

    @Mock
    ModbusDeviceRepository serviceRepo;

    @Mock
    ModbusPollingRepository pollRepo;

    @Mock
    ModbusShadowRepository registryShadowRepo;

    @Mock
    SpaceRepository spaceRepo;

    @Mock
    ModbusPollingBaselines baselines;

    @Mock
    Logger logger;

    @InjectMocks
    ModbusPollingReconciler reconciler;

    /**
     * 库里已经有这一行：内存任务用的是**行上的** {@code _id}，不是计划对象那个 null。
     */
    @Test
    void existingRowKeepsItsDatabaseId() {
        ObjectId rowId = new ObjectId();
        givenServices();
        givenSpace();
        givenRows(row(rowId));

        reconcile();

        ModbusPollingTask task = pumpOne();
        assertEquals(rowId, task.id);
        assertEquals(30, task.intervalSeconds);
        assertEquals(SPACE_ID, task.ownerId, "归属也要解析出来（没有归属的任务一次也调不出去）");
        verify(pollRepo, never()).insert(any());
    }

    /**
     * 库里还没有这一行：计划生成的 id 就是落库的 {@code _id}，两个必须是同一个。
     */
    @Test
    void newTaskIsScheduledWithTheIdItIsInsertedWith() {
        givenServices();
        givenSpace();
        givenRows();
        when(pollRepo.insert(any())).thenReturn(Uni.createFrom().voidItem());

        reconcile();

        ArgumentCaptor<ModbusPolling> inserted = ArgumentCaptor.forClass(ModbusPolling.class);
        verify(pollRepo).insert(inserted.capture());
        assertNotNull(inserted.getValue().id, "落库的那条自己得先有 id");
        assertEquals(inserted.getValue().id, pumpOne().id, "落库的 _id 与内存任务必须是同一个");
    }

    /**
     * 行被删了又插（关掉轮询再打开、方法序号删了又加）：内存任务要跟到新的 {@code _id} 上。
     *
     * <p>停在旧 {@code _id} 上不会报错 —— 认领会一直「抢不到」（{@code findOneAndUpdate} 匹配不上），
     * 表现为这个任务静默地永远不采。所以换的是**同一个任务对象上的字段**（在飞的那一帧不能换对象）。</p>
     */
    @Test
    void taskFollowsTheRowWhenItsIdChanges() {
        ObjectId first = new ObjectId();
        ObjectId second = new ObjectId();
        ObjectId third = new ObjectId();
        givenServices();
        givenSpace();
        givenRows(row(first));

        reconcile();
        ModbusPollingTask task = pumpOne();       // 出轮 = 在飞
        assertEquals(first, task.id);

        givenRows(row(second));
        reconcile();                            // 在飞的时候换行
        assertEquals(second, task.id);

        reconciler.wheel().finish(task, NOW + 60_000);  // 这一帧跑完回填
        givenRows(row(third));
        reconcile();                            // 出轮那一支同样要跟
        assertSame(task, pumpOne());
        assertEquals(third, task.id);
    }

    // ===== 夹具 =====

    private void reconcile() {
        reconciler.reconcileAll().await().indefinitely();
    }

    /**
     * 取到点的那一个任务（{@code Long.MAX_VALUE} 当「现在」：轮里所有任务都算到点）。
     */
    private ModbusPollingTask pumpOne() {
        List<ModbusPollingTask> due = reconciler.wheel().pump(Long.MAX_VALUE, 10);
        assertEquals(1, due.size(), "这一轮只该有一个任务到点");
        return due.get(0);
    }

    private void givenServices() {
        when(serviceRepo.listAll()).thenReturn(Uni.createFrom().item(List.of(service())));
    }

    private void givenRows(ModbusPolling... rows) {
        when(pollRepo.listAll()).thenReturn(Uni.createFrom().item(List.of(rows)));
    }

    private void givenSpace() {
        when(spaceRepo.findById(SPACE_ID)).thenReturn(Uni.createFrom().item(space()));
    }

    /**
     * 一个读保持寄存器（fc 03）的方法，周期 30 秒 —— 与现网那条报了 bug 的任务同形。
     */
    private static ModbusDevice service() {
        ModbusDevice service = new ModbusDevice();
        service.id = new ObjectId(SID);
        DeviceOn device = new DeviceOn();
        device.did = DID;
        SpaceOn space = new SpaceOn();
        space.spaceId = SPACE_ID;
        space.rootId = SPACE_ID;
        device.space = space;
        service.device = device;
        ModbusPoll poll = new ModbusPoll();
        poll.from = new ModbusPollFrom();
        poll.from.type = "command";
        poll.from.index = 1;
        poll.index = 1;
        poll.interval = 30;
        poll.polling = Boolean.TRUE;
        poll.request = ModbusRequests.holding();
        service.polls = List.of(poll);
        return service;
    }

    private static SpaceEntity space() {
        SpaceEntity space = new SpaceEntity();
        space.id = new ObjectId(SPACE_ID);
        space.ancestors = List.of();
        return space;
    }

    private static ModbusPolling row(ObjectId id) {
        ModbusPolling row = new ModbusPolling();
        row.id = id;
        row.serviceId = SID;
        row.pollIndex = 1;
        row.did = DID;
        row.interval = 30;
        row.nextRunAt = new Date(NOW);
        return row;
    }
}
