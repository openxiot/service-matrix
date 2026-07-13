package cc.openxiot.common.util;

import io.vertx.core.Future;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class FutureMerger {

    /**
     * 合并多个 Future<List<T>> 为一个 Future<List<T>>，将所有子列表展平。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T> Future<List<T>> mergeList(List<Future<List<T>>> futures) {
        if (futures.isEmpty()) {
            return Future.succeededFuture(List.of());
        }
        return Future.all(new ArrayList(futures))
                .map(cf -> futures.stream()
                        .map(Future::result)
                        .filter(Objects::nonNull)
                        .flatMap(List::stream)
                        .collect(Collectors.toList()));
    }
}
