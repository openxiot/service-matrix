package cc.openxiot.matrix.db.space;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "空间类型")
public enum SpaceType {

    SITE("matrix"),
    BUILDING("building"),
    ZONE("zone"),
    FLOOR("floor"),
    ROOM("room"),
    FIELD("field"),
    WORKSHOP("workshop"),
    PARKING("parking"),
    OTHER("other");

    private final String value;

    SpaceType(String value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return value;
    }
}
