package cc.openxiot.common.log;

import io.vertx.core.http.HttpServerResponse;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class LogBroadcaster {

    private final Set<HttpServerResponse> sinks = ConcurrentHashMap.newKeySet();

    public void register(HttpServerResponse sink) {
        sinks.add(sink);
    }

    public void unregister(HttpServerResponse sink) {
        sinks.remove(sink);
    }

    public void broadcast(String data) {
        String sse = "data: " + data.replace("\n", "\\n") + "\n\n";
        for (var it = sinks.iterator(); it.hasNext(); ) {
            HttpServerResponse sink = it.next();
            if (sink.closed()) {
                it.remove();
                continue;
            }
            try {
                sink.write(sse);
            } catch (Exception e) {
                it.remove();
            }
        }
    }
}
