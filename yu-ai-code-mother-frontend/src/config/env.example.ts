/**
 * 环境变量配置说明
 *
 * 在项目根目录创建 .env.local 或 .env.development 文件，并添加以下配置：
 *
 * # 应用部署入口（P5：子域名 + 独立 proxy）
 * VITE_DEPLOY_SCHEME=http
 * VITE_DEPLOY_HOST_SUFFIX=app.localhost
 * VITE_DEPLOY_PORT=8124
 *
 * # API 基础地址
 * VITE_API_BASE_URL=http://localhost:8123/api
 *
 * 生产环境可以创建 .env.production 文件：
 *
 * # 生产环境配置示例
 * VITE_DEPLOY_SCHEME=https
 * VITE_DEPLOY_HOST_SUFFIX=your-domain.com
 * VITE_DEPLOY_PORT=443
 * VITE_API_BASE_URL=https://api.your-domain.com
 */

export {}
