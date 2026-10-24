package com.yupi.proxy.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 真实路由来源：Redis {@code route:{host}}，由主服务写入。
 * <p>
 * 设计取舍：
 * - 只读 Redis，不查 MySQL、不回调主服务 —— 主服务重启/发版不能影响已发出的预览链接；
 * - 1 秒本地缓存：路由变更（新预览、容器重建）最坏延迟 1 秒生效，
 *   换来的是每个静态资源请求不用打一次 Redis；
 * - Redis 异常映射为 {@link RouteStoreUnavailableException} → 503，绝不退化成 404。
 */
@Component
public class RedisRouteStore implements RouteStore {

    private static final Logger log = LoggerFactory.getLogger(RedisRouteStore.class);

    /** 与主服务 AppRouteService 的 key 约定保持一致 */
    private static final String KEY_PREFIX = "route:";

    /** 单次 Redis 读取超时：宁可 503 也不让请求挂住 */
    private static final Duration READ_TIMEOUT = Duration.ofMillis(800);

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Cache<String, Route> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(1))
            .build();

    public RedisRouteStore(ReactiveStringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Route> find(String host) {
        Route cached = cache.getIfPresent(host);
        if (cached != null) {
            return Mono.just(cached);
        }
        return redis.opsForValue().get(KEY_PREFIX + host)
                .timeout(READ_TIMEOUT)
                .mapNotNull(json -> parse(host, json))
                .doOnNext(route -> cache.put(host, route))
                .onErrorMap(e -> new RouteStoreUnavailableException(host, e));
    }

    private Route parse(String host, String json) {
        try {
            Route route = objectMapper.readValue(json, Route.class);
            if (route.host() == null) {
                // 容错：Redis 里缺 host 字段时用查询用的 host 补齐
                return new Route(host, route.appId(), route.kind(), route.upstream(), route.status());
            }
            return route;
        } catch (Exception e) {
            log.warn("路由 JSON 解析失败, host={}, json={}, err={}", host, json, e.getMessage());
            return null;
        }
    }
}
