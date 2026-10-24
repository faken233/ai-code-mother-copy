package com.yupi.proxy.web;

import com.yupi.proxy.config.ProxyProperties;
import com.yupi.proxy.route.HostNames;
import com.yupi.proxy.route.Route;
import com.yupi.proxy.route.RouteStore;
import com.yupi.proxy.route.RouteStoreUnavailableException;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 唯一的转发入口：按 Host 找路由，把请求原样流式转发到沙箱容器。
 * <p>
 * 三条铁律（改代码前先看）：
 * 1. 不做"读完再返回"——响应体必须是 {@code Flux<DataBuffer>}，任何 {@code byte[]}/Mono&lt;byte[]&gt; 都会把 SSE 打断。
 * 2. 不剥前缀——应用站在 {@code /} 上，{@code /api/*} 是它自己的，不需要和平台抢路径。
 * 3. 逐跳头（Connection/Transfer-Encoding/Upgrade...）必须过滤，否则下游会收到冲突的帧语义。
 */
@Component
@RestController
public class ProxyHandler {

    private static final Logger log = LoggerFactory.getLogger(ProxyHandler.class);

    /**
     * 逐跳头：只对单跳连接有意义，代理必须剥掉（RFC 7230 §6.1）。
     */
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade");

    /**
     * 不往上游透传的请求头：
     * - host：让客户端按上游真实地址重算
     * - content-length：由客户端按实际流重算（避免"头里有长度、体是分块流"的冲突）
     */
    private static final Set<String> REQUEST_EXCLUDED = Set.of("host", "content-length");

    private final RouteStore routeStore;
    private final WebClient normalClient;
    private final WebClient streamClient;

    public ProxyHandler(RouteStore routeStore, ProxyProperties properties) {
        this.routeStore = routeStore;

        ConnectionProvider provider = ConnectionProvider.builder("app-proxy")
                .maxConnections(512)
                .pendingAcquireTimeout(Duration.ofSeconds(10))
                .build();

        HttpClient base = HttpClient.create(provider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3_000)
                .compress(false)
                .followRedirect(false);

        // 普通请求：有读超时，避免上游卡住时连接泄漏
        this.normalClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(
                        base.responseTimeout(Duration.ofSeconds(properties.getNormalTimeoutSeconds()))))
                .build();
        // 流式请求（SSE / 长下载）：不设读超时，否则长连接会被代理自己掐断
        this.streamClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(base))
                .build();
    }

    @RequestMapping("/**")
    public Mono<Void> proxy(ServerHttpRequest request, ServerHttpResponse response) {
        String host = HostNames.normalize(request.getHeaders().getFirst(HttpHeaders.HOST));
        if (host == null) {
            return jsonError(response, HttpStatus.BAD_REQUEST, "missing host", null);
        }
        return routeStore.find(host)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(found -> {
                    if (found.isEmpty()) {
                        log.debug("未命中路由, host={}, path={}", host, request.getURI().getRawPath());
                        return jsonError(response, HttpStatus.NOT_FOUND, "route not found", null);
                    }
                    Route route = found.get();
                    if (!route.ready()) {
                        return jsonError(response, HttpStatus.SERVICE_UNAVAILABLE,
                                "app " + route.status().toLowerCase(Locale.ROOT), "5");
                    }
                    return forward(route, request, response);
                })
                .onErrorResume(RouteStoreUnavailableException.class, e -> {
                    log.warn("路由存储不可用, host={}, err={}", host, rootMessage(e));
                    if (response.isCommitted()) {
                        return response.setComplete();
                    }
                    return jsonError(response, HttpStatus.SERVICE_UNAVAILABLE, "route store unavailable", "5");
                })
                .onErrorResume(e -> {
                    log.warn("转发失败, host={}, err={}", host, e.toString());
                    if (response.isCommitted()) {
                        // 已经开始写响应，只能断连接（改状态码会让浏览器收到半截响应 + 500）
                        return response.setComplete();
                    }
                    return jsonError(response, HttpStatus.BAD_GATEWAY, "upstream unavailable", null);
                });
    }

    private Mono<Void> forward(Route route, ServerHttpRequest request, ServerHttpResponse response) {
        URI target = targetUri(route, request);
        boolean streaming = isEventStream(request);
        WebClient client = streaming ? streamClient : normalClient;
        HttpHeaders forward = forwardHeaders(request, route.host());

        WebClient.RequestBodySpec bodySpec = client.method(request.getMethod())
                .uri(target)
                .headers(h -> h.addAll(forward));

        WebClient.RequestHeadersSpec<?> spec = hasBody(request)
                ? bodySpec.body(BodyInserters.fromDataBuffers(request.getBody()))
                : bodySpec;

        return spec.exchangeToMono(upstream -> {
            response.setStatusCode(upstream.statusCode());
            response.getHeaders().addAll(filterResponseHeaders(upstream.headers().asHttpHeaders()));
            return response.writeWith(upstream.bodyToFlux(DataBuffer.class));
        });
    }

    private boolean hasBody(ServerHttpRequest request) {
        return request.getHeaders().containsKey(HttpHeaders.CONTENT_LENGTH)
                || request.getHeaders().containsKey(HttpHeaders.TRANSFER_ENCODING);
    }

    private boolean isEventStream(ServerHttpRequest request) {
        List<MediaType> accept = request.getHeaders().getAccept();
        return accept.stream().anyMatch(type -> type.includes(MediaType.TEXT_EVENT_STREAM));
    }

    private URI targetUri(Route route, ServerHttpRequest request) {
        String rawPath = request.getURI().getRawPath();
        String rawQuery = request.getURI().getRawQuery();
        String path = (rawPath == null || rawPath.isEmpty()) ? "/" : rawPath;
        String url = route.upstream() + path + (rawQuery == null || rawQuery.isEmpty() ? "" : "?" + rawQuery);
        return URI.create(url);
    }

    private HttpHeaders forwardHeaders(ServerHttpRequest request, String host) {
        HttpHeaders target = new HttpHeaders();
        for (Map.Entry<String, List<String>> entry : request.getHeaders().entrySet()) {
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(name) || REQUEST_EXCLUDED.contains(name)) {
                continue;
            }
            target.addAll(entry.getKey(), entry.getValue());
        }
        String remote = request.getRemoteAddress() == null
                ? "unknown"
                : request.getRemoteAddress().getAddress().getHostAddress();
        target.set("X-Forwarded-For", remote);
        target.set("X-Forwarded-Proto", "http");
        target.set("X-Forwarded-Host", host);
        return target;
    }

    private HttpHeaders filterResponseHeaders(HttpHeaders upstream) {
        HttpHeaders target = new HttpHeaders();
        for (Map.Entry<String, List<String>> entry : upstream.entrySet()) {
            if (HOP_BY_HOP.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                continue;
            }
            target.addAll(entry.getKey(), entry.getValue());
        }
        return target;
    }

    private Mono<Void> jsonError(ServerHttpResponse response, HttpStatus status, String message, String retryAfterSeconds) {
        if (response.isCommitted()) {
            return response.setComplete();
        }
        response.setStatusCode(status);
        if (retryAfterSeconds != null) {
            response.getHeaders().set(HttpHeaders.RETRY_AFTER, retryAfterSeconds);
        }
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    private String rootMessage(Throwable e) {
        Throwable cause = e.getCause();
        return cause == null ? e.toString() : cause.toString();
    }
}
