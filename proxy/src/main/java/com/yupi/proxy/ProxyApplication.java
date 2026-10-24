package com.yupi.proxy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 访问入口代理。
 * <p>
 * 职责只有两件：按 Host 查路由、把请求流式转发到沙箱容器。
 * 它不调用主服务，也不读业务库——路由由主服务写入 Redis，这里只读。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ProxyApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProxyApplication.class, args);
    }
}
