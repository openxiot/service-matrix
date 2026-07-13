package cc.openxiot.site.api.device;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ManipulationService {

    @Inject
    ManipulationClient client;

}
