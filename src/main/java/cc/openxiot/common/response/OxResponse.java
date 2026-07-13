package cc.openxiot.common.response;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

public class OxResponse {

    public static Response error(Exception e) {
        return Response.ok()
                .entity(new OxError(e.getMessage()))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    public static Response error(String message) {
        return Response.ok()
                .entity(new OxError(message))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    public static Response ok() {
        return Response.ok()
                .entity(new OxOk())
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    public static Response created() {
        return Response.status(Response.Status.CREATED)
                .entity(new OxOk())
                .build();
    }

    public static Response ok(Object entity) {
        return Response.ok()
                .entity(new OxData(entity))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
