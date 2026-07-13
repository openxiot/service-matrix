package cc.openxiot.site.api;

import cn.geekcity.xiot.spec.device.Device;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class SiteService {

    public List<Device> getDevices(String requestId, String appId, String token, List<String> deviceIds) {
        return List.of();
    }
}
