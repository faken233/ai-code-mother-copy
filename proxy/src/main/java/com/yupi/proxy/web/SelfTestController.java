package com.yupi.proxy.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * 自检端点（仅 {@code proxy.selftest-enabled=true} 时注册）。
 * <p>
 * 存在的唯一目的：让"代理到底有没有缓冲"可以用一条 curl 判定。
 * 普通 JSON 接口根本测不出来——它们本来就一次性返回。
 */
@RestController
@ConditionalOnProperty(name = "proxy.selftest-enabled", havingValue = "true", matchIfMissing = true)
public class SelfTestController {

    /**
     * 存活探针，供 scripts/proxy-up.sh 等待启动完成。
     */
    @GetMapping("/__health")
    public Mono<Map<String, Object>> health() {
        return Mono.just(Map.of("ok", true, "service", "app-proxy"));
    }

    /**
     * 流式自检：每秒一个事件，共 5 个。
     * <p>
     * 判据：{@code curl -sN -w 'ttfb=%{time_starttransfer} total=%{time_total}'}
     * 如果 ttfb ≈ total，说明代理在缓冲；ttfb ≈ 0 且 total ≈ 5s 才是真流式。
     */
    @GetMapping(value = "/__selftest/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream() {
        return Flux.interval(Duration.ofSeconds(1))
                .take(5)
                .map(i -> ServerSentEvent.builder("tick-" + (i + 1)).build());
    }
}
