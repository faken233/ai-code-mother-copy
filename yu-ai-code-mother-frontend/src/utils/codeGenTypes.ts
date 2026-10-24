/**
 * 代码生成类型枚举
 *
 * 产品上只有两种模式：
 * - STATIC    纯前端项目：同一套 React 模板，但没有数据库，数据只能放内存或 localStorage
 * - FULLSTACK 带存储项目：schema.json 声明表结构，运行时自动建表，数据落 MySQL
 *
 * 其余为历史类型，只用于展示老应用，不可再被选择。
 */
export enum CodeGenTypeEnum {
  STATIC = 'static',
  FULLSTACK = 'fullstack',
  /** @deprecated 历史类型 */
  HTML = 'html',
  /** @deprecated 历史类型 */
  MULTI_FILE = 'multi_file',
  /** @deprecated 历史类型 */
  VUE_PROJECT = 'vue_project',
}

/**
 * 代码生成类型配置
 */
export const CODE_GEN_TYPE_CONFIG = {
  [CodeGenTypeEnum.STATIC]: {
    label: '纯前端项目',
    value: CodeGenTypeEnum.STATIC,
  },
  [CodeGenTypeEnum.FULLSTACK]: {
    label: '带存储项目',
    value: CodeGenTypeEnum.FULLSTACK,
  },
  [CodeGenTypeEnum.HTML]: {
    label: '原生 HTML（旧）',
    value: CodeGenTypeEnum.HTML,
  },
  [CodeGenTypeEnum.MULTI_FILE]: {
    label: '原生多文件（旧）',
    value: CodeGenTypeEnum.MULTI_FILE,
  },
  [CodeGenTypeEnum.VUE_PROJECT]: {
    label: 'Vue 工程（旧）',
    value: CodeGenTypeEnum.VUE_PROJECT,
  },
} as const

/**
 * 可选的代码生成类型（新建应用时只能选这两个）
 */
export const SELECTABLE_CODE_GEN_TYPES = [CodeGenTypeEnum.STATIC, CodeGenTypeEnum.FULLSTACK] as const

/**
 * 代码生成类型选项（用于下拉选择）
 */
export const CODE_GEN_TYPE_OPTIONS = SELECTABLE_CODE_GEN_TYPES.map((value) => ({
  label: CODE_GEN_TYPE_CONFIG[value].label,
  value,
}))

/**
 * 是否为「跑在沙箱容器里」的类型（预览/生成都走同一套链路）
 */
export const isSandboxType = (type: string | undefined): boolean => {
  return type === CodeGenTypeEnum.STATIC || type === CodeGenTypeEnum.FULLSTACK
}

/**
 * 格式化代码生成类型
 * @param type 代码生成类型
 * @returns 格式化后的类型描述
 */
export const formatCodeGenType = (type: string | undefined): string => {
  if (!type) return '未知类型'

  const config = CODE_GEN_TYPE_CONFIG[type as CodeGenTypeEnum]
  return config ? config.label : type
}

/**
 * 获取所有代码生成类型
 */
export const getAllCodeGenTypes = () => {
  return Object.values(CodeGenTypeEnum)
}

/**
 * 检查是否为有效的代码生成类型
 * @param type 待检查的类型
 */
export const isValidCodeGenType = (type: string): type is CodeGenTypeEnum => {
  return Object.values(CodeGenTypeEnum).includes(type as CodeGenTypeEnum)
}
