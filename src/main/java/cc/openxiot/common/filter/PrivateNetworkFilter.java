package cc.openxiot.common.filter;

import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

@PrivateNetwork
@Provider
public class PrivateNetworkFilter implements ContainerRequestFilter {

    @Inject
    Logger logger;

    @Inject
    RoutingContext routingContext;

    @Override
    public void filter(ContainerRequestContext ctx) {
        String remoteIp = resolveClientIp();

        logger.infov("Client IP: {0}", remoteIp);

        if (remoteIp == null || !isPrivateNetwork(remoteIp)) {
            logger.warnv("Blocked request from non-private IP: {0}", remoteIp);
            ctx.abortWith(Response.status(Response.Status.FORBIDDEN)
                    .entity("{\"success\":false,\"message\":\"internal only\"}")
                    .build());
        }
    }

    private String resolveClientIp() {
        // X-Forwarded-For: behind reverse proxy / load balancer
        String xff = routingContext.request().getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }

        // X-Real-IP: Nginx convention
        String realIp = routingContext.request().getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp;
        }

        return routingContext.request().remoteAddress().host();
    }

    static boolean isPrivateNetwork(String ip) {
        try {
            InetAddress addr = InetAddress.getByName(ip);

            if (!(addr instanceof Inet4Address)) {
                return false;
            }

            // 回环地址: 127.0.0.0/8
            if (addr.isLoopbackAddress()) {
                return true;
            }

            byte[] bytes = addr.getAddress();
            int firstByte = bytes[0] & 0xFF;
            int secondByte = bytes[1] & 0xFF;

            // 10.0.0.0/8 — RFC 1918
            if (firstByte == 10) {
                return true;
            }

            // 100.64.0.0/10 — RFC 6598 运营商级 NAT (Azure Container Apps 等)
            if (firstByte == 100 && (secondByte & 0xC0) == 0x40) {
                return true;
            }

            // 169.254.0.0/16 — 链路本地 (云平台元数据服务、DHCP)
            if (firstByte == 169 && secondByte == 254) {
                return true;
            }

            // 172.16.0.0/12 — RFC 1918
            if (firstByte == 172 && (secondByte & 0xF0) == 16) {
                return true;
            }

            // 192.168.0.0/16 — RFC 1918
            if (firstByte == 192 && secondByte == 168) {
                return true;
            }

            // 198.18.0.0/15 — 网络基准测试 (部分云网络组件内部使用)
            if (firstByte == 198 && (secondByte & 0xFE) == 18) {
                return true;
            }

            return false;
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
