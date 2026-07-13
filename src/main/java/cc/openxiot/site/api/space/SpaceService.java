package cc.openxiot.site.api.space;

import cc.openxiot.site.db.space.SpaceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class SpaceService {

    @Inject
    SpaceRepository spaceRepository;
}
