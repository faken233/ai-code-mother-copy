# 主服务多阶段构建：Maven 编译可执行 jar -> JRE 运行
# 注意：不打包任何真实密钥（application-local.yml 不入镜像），运行时用环境变量注入。

# ---- 构建阶段 ----
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先拷贝 pom.xml 以复用依赖缓存层
COPY pom.xml .
RUN mvn -q -B dependency:go-offline || true

# 拷贝源码并打包（跳过测试）
COPY src ./src
RUN mvn -q -B package -DskipTests

# ---- 运行阶段 ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# 安装 docker CLI（主服务通过挂载的 docker.sock 用 docker 命令管理沙箱容器）
# 使用 docker 官方静态二进制，轻量且不依赖 apt 源
ARG TARGETARCH
RUN set -eux; \
    case "${TARGETARCH}" in \
        amd64) ARCH=x86_64 ;; \
        arm64) ARCH=aarch64 ;; \
        *) echo "unsupported arch: ${TARGETARCH}"; exit 1 ;; \
    esac; \
    DOCKER_VERSION=27.5.1; \
    curl -fsSL "https://download.docker.com/linux/static/stable/${ARCH}/docker-${DOCKER_VERSION}.tgz" -o /tmp/docker.tgz; \
    tar -xzf /tmp/docker.tgz -C /tmp; \
    cp /tmp/docker/docker /usr/local/bin/docker; \
    chmod +x /usr/local/bin/docker; \
    rm -rf /tmp/docker /tmp/docker.tgz; \
    docker --version

# 拷贝可执行 jar
COPY --from=build /build/target/*.jar app.jar

# 时区
ENV TZ=Asia/Shanghai

# 暴露端口
EXPOSE 8123

# 启动：配置通过环境变量注入（见 docker-compose.yml）
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
