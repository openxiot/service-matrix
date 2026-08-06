package cc.openxiot.matrix.db.space;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Schema(description = "空间坐标")
@RegisterForReflection
public class SpaceLocation {

    @Schema(description = "坐标类型", required = true)
    public String type = "Point";

    @Schema(description = "经度", required = true)
    public double longitude;

    @Schema(description = "纬度", required = true)
    public double latitude;

    @Schema(description = "GeoJSON 坐标", required = true)
    public List<Double> coordinates;

    public SpaceLocation() {
    }

    public SpaceLocation(double longitude, double latitude) {
        this.longitude = longitude;
        this.latitude = latitude;
        this.coordinates = List.of(longitude, latitude);
    }
}
