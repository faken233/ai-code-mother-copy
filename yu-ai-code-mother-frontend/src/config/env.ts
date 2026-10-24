/**
 * 环境变量配置
 */
import {CodeGenTypeEnum} from "@/utils/codeGenTypes.ts";

// 应用部署入口（P5）：{scheme}://{deployKey}.{suffix}:{port}/
// deployKey 固定存在 app 表，重新部署/回滚都不换地址
export const DEPLOY_SCHEME = import.meta.env.VITE_DEPLOY_SCHEME || 'http'
export const DEPLOY_HOST_SUFFIX = import.meta.env.VITE_DEPLOY_HOST_SUFFIX || 'app.localhost'
export const DEPLOY_PORT = import.meta.env.VITE_DEPLOY_PORT || '8124'

// API 基础地址
export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8123/api'

// 静态资源地址
export const STATIC_BASE_URL = `${API_BASE_URL}/static`

// 获取部署应用的完整URL
export const getDeployUrl = (deployKey: string) => {
  const port = DEPLOY_PORT && DEPLOY_PORT !== '80' ? `:${DEPLOY_PORT}` : ''
  return `${DEPLOY_SCHEME}://${deployKey.toLowerCase()}.${DEPLOY_HOST_SUFFIX}${port}/`
}

// 获取静态资源预览URL
export const getStaticPreviewUrl = (codeGenType: string, appId: string) => {
  const baseUrl = `${STATIC_BASE_URL}/${codeGenType}_${appId}/`
  // 如果是 Vue 项目，浏览地址需要添加 dist 后缀
  if (codeGenType === CodeGenTypeEnum.VUE_PROJECT) {
    return `${baseUrl}dist/index.html`
  }
  return baseUrl
}
