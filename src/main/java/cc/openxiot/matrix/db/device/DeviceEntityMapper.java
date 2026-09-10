package cc.openxiot.matrix.db.device;

import cc.openxiot.matrix.db.space.SpaceOn;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cn.geekcity.xiot.spec.device.Device;
import cn.geekcity.xiot.spec.summary.Summary;

import java.util.List;

public class DeviceEntityMapper {

    public static DeviceEntity from(Device device, SpaceEntity space) {
        DeviceEntity entity = new DeviceEntity();
        entity.did = device.did();
        entity.space = new SpaceOn(space);

        Summary summary = device.summary();
        if (summary != null) {
            entity.type = summary.type() != null ? summary.type().toString() : null;
            entity.online = Boolean.TRUE.equals(summary.online());
            entity.protocol = summary.protocol();
            entity.rootId = summary.rootId();
            entity.parentId = summary.parentId();
            entity.members = summary.members();
            entity.lastOnline = summary.lastOnline();
            entity.lastOffline = summary.lastOffline();
        }

        return entity;
    }

    public static List<DeviceEntity> from(List<Device> devices,SpaceEntity space) {
        return devices.stream()
                .map(d -> DeviceEntityMapper.from(d, space))
                .toList();
    }
}
