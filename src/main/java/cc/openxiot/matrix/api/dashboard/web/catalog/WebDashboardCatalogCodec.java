package cc.openxiot.matrix.api.dashboard.web.catalog;

import cc.openxiot.matrix.db.device.DeviceEntity;
import cc.openxiot.matrix.db.modbus.service.ModbusService;
import cc.openxiot.matrix.db.modbus.service.codec.ModbusServiceCodec;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * 候选清单 → 线上 JSON：{@code {devices: [...], services: [...]}}。
 *
 * <h3>设备为什么不带 {@code name}</h3>
 * <p>{@link DeviceEntity} 上**没有名字**（它的字段是 {@code did} / {@code type} / {@code online} /
 * {@code space} / {@code anchor} 这些），设备的显示名全仓库都在前端按一条四级回退链解析：
 * 产品名称 → 设备实例描述 → URN 的 type 段 → {@code did}
 * （见 {@code DeviceDisplayService}，那三个页面的既有做法）。服务端要给出这个名字，
 * 就得在这里回调产品服务、把同一条链再实现一遍 —— 两处各解一次，迟早解出两个名字。</p>
 * <p>所以这里只给**解析得出名字所必需的输入**：{@code type}（DeviceType URN，既是查产品规格的键，
 * 也是回退链的第三级）。前端拿 {@code DeviceDisplayService} 补名字，与设备列表页同一个口径。</p>
 *
 * <h3>服务为什么整份下发</h3>
 * <p>编辑器要的就是 {@code functions[].response.fields[]} 里的 {@code format}（判是不是数值）、
 * {@code unit}、{@code valueList}（枚举）、{@code bitList}（位），以及 {@code function.name}。
 * 做投影裁剪能省些体积，但换来的是「卡片侧将来要别的字段又得改接口」——
 * 这是**编辑时的一次性取数**（进编辑态取一次），不是每次刷新都拉的，不值得为此收窄契约。
 * 复用 {@link ModbusServiceCodec#encode(List)} 而不是另写一份，正是为了与
 * {@code /modbus/service/one/{id}} 的下发形状**逐字相同**：前端因此能直接复用
 * 已有的 {@code ModbusServiceCodec.decode}，不必为一个新形状再写一个解码器。</p>
 */
public final class WebDashboardCatalogCodec {

    private WebDashboardCatalogCodec() {
    }

    public static JsonObject encode(List<DeviceEntity> devices, List<ModbusService> services) {
        JsonArray deviceArr = new JsonArray();
        if (devices != null) {
            for (DeviceEntity device : devices) {
                if (device != null) {
                    deviceArr.add(encodeDevice(device));
                }
            }
        }
        return new JsonObject()
                .put("devices", deviceArr)
                .put("services", ModbusServiceCodec.encode(services));
    }

    /** {@code did} 就是设备的 {@code _id}；{@code online} 恒给（false 与「没有这个字段」不是一回事）。 */
    private static JsonObject encodeDevice(DeviceEntity device) {
        JsonObject o = new JsonObject();
        if (device.did != null) {
            o.put("did", device.did);
        }
        if (device.type != null) {
            o.put("type", device.type);
        }
        o.put("online", device.online);
        return o;
    }
}
