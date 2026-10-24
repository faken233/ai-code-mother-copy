# 访问入口代理（proxy）镜像
#
# 为什么单独一个进程/镜像：
# - 主服务是 Servlet/MVC（Spring MVC），WebFlux 不能和它跑在同一个进程里；
# - 代理只读 Redis 路由表，不依赖 MySQL/主服务，主服务发版不影响已发出的预览链接。
#
# 构建上下文是仓库根目录（需要 proxy/pom.xml 与 proxy/src）：
#   docker build -f docker/proxy.Dockerfile -t app-proxy:latest .

# ===== 构建阶段 =====
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先只拷 pom 预热依赖，源码改动时可复用这一层
COPY proxy/pom.xml ./
RUN mvn -B -q -DskipTests dependency:go-offline || true

COPY proxy/src ./src
RUN mvn -B -q -DskipTests package

# ===== 运行阶段 =====
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/app-proxy-*.jar /app/app.jar

EXPOSE 8124
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
