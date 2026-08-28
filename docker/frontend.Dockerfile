# 前端构建 + nginx 静态托管
# 多阶段：Node 20 构建 Vue3 dist -> nginx 托管并反代 /api 到后端
# 注意：本项目前端用 Vite 7 + @vitejs/plugin-vue 6，要求 Node 20.19+（crypto.hash 在 Node18 不存在）

# ---- 构建阶段 ----
FROM node:20-alpine AS build
WORKDIR /build

# 拷贝依赖锁定文件复用缓存
COPY yu-ai-code-mother-frontend/package*.json ./
RUN npm ci || npm install

# 拷贝前端源码并构建
COPY yu-ai-code-mother-frontend/ ./
RUN npm run build

# ---- 运行阶段 ----
FROM nginx:alpine
WORKDIR /usr/share/nginx/html

# 拷贝构建产物
COPY --from=build /build/dist ./

# 拷贝反代配置（/api -> 后端服务）
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
