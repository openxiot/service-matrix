package cc.openxiot.site.api.device;

import cc.openxiot.site.db.device.DeviceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class DeviceService {

    @Inject
    DeviceRepository deviceRepository;
}
