# 代码沙箱统一镜像
# 基础：JDK21 + Maven + Node18 + npm，一个镜像跑前后端
FROM maven:3.9.9-eclipse-temurin-21

# 容器内走宿主机代理（构建时通过 build-arg 注入，运行时可覆盖）
ARG HTTP_PROXY=http://host.docker.internal:7897
ARG HTTPS_PROXY=http://host.docker.internal:7897
ARG http_proxy=http://host.docker.internal:7897
ARG https_proxy=http://host.docker.internal:7897
ENV http_proxy=$http_proxy
ENV https_proxy=$https_proxy
ENV HTTP_PROXY=$HTTP_PROXY
ENV HTTPS_PROXY=$HTTPS_PROXY
ENV NO_PROXY=localhost,127.0.0.1,host.docker.internal
ENV no_proxy=localhost,127.0.0.1,host.docker.internal

# 安装 Node.js 18 + npm（用官方 gzip tarball 解压，避免依赖 apt 源不稳 / 容器缺 xz）
# 基础镜像带 curl + ca-certificates；按 CPU 架构选择对应 tarball
RUN set -eux; \
    arch="$(uname -m)"; \
    case "$arch" in \
        x86_64) node_arch="x64" ;; \
        aarch64|arm64) node_arch="arm64" ;; \
        *) echo "unsupported arch: $arch"; exit 1 ;; \
    esac; \
    curl -fsSLO "https://nodejs.org/dist/v18.20.4/node-v18.20.4-linux-${node_arch}.tar.gz"; \
    tar -xzf "node-v18.20.4-linux-${node_arch}.tar.gz" -C /usr/local --strip-components=1; \
    rm -f "node-v18.20.4-linux-${node_arch}.tar.gz"; \
    node -v && npm -v

# 预缓存 Maven 依赖，加速全栈项目构建（B6）
COPY maven-repo/scaffold-pom.xml /tmp/scaffold-pom.xml
RUN mvn -f /tmp/scaffold-pom.xml -q -B dependency:go-offline || true
RUN rm -f /tmp/scaffold-pom.xml

# 应用代码
COPY app.js /app/app.js
COPY start.sh /app/start.sh
RUN chmod +x /app/start.sh

WORKDIR /workspace
EXPOSE 8080

ENTRYPOINT ["node", "/app/app.js"]
