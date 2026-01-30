/**
 * AI 代码生成任务队列客户端
 * 支持任务排队、断线重连、队列位置实时更新
 */

import request from '@/request';
import { API_BASE_URL } from '@/config/env';

/**
 * 任务状态枚举
 */
export enum GenTaskStatus {
  PENDING = 'pending',
  RUNNING = 'running',
  COMPLETED = 'completed',
  FAILED = 'failed',
  CANCELLED = 'cancelled',
}

/**
 * 任务 VO
 * 注意：id 和 appId 使用 string | number 类型，避免大整数精度丢失
 */
export interface GenTaskVO {
  id: string | number;
  appId: string | number;
  status: string;
  queuePosition: number;
  codeGenType: string;
  errorMessage?: string;
  createTime?: string;
  startTime?: string;
  endTime?: string;
}

/**
 * SSE 事件类型
 */
export enum SseEventType {
  DATA = 'data',       // 数据块
  STATUS = 'status',   // 状态更新
  QUEUE = 'queue',     // 队列位置更新
  RECONNECT = 'reconnect', // 重连恢复数据
  DONE = 'done',       // 完成
  ERROR = 'error',     // 错误
  CANCELLED = 'cancelled', // 已取消
}

/**
 * SSE 连接配置
 */
export interface SseConnectionConfig {
  /** 自动重连 */
  autoReconnect?: boolean;
  /** 最大重连次数 */
  maxRetries?: number;
  /** 重连间隔（毫秒） */
  retryInterval?: number;
  /** 数据回调 */
  onData?: (chunk: string) => void;
  /** 状态变更回调 */
  onStatusChange?: (status: string, queuePosition: number) => void;
  /** 队列位置更新回调 */
  onQueueUpdate?: (position: number) => void;
  /** 完成回调 */
  onComplete?: () => void;
  /** 错误回调 */
  onError?: (error: string) => void;
  /** 连接打开回调 */
  onOpen?: () => void;
  /** 连接关闭回调 */
  onClose?: () => void;
}

/**
 * 创建生成任务
 * @param appId 应用ID（可选，首次对话时为空，系统会自动创建应用）
 * @param message 用户消息
 */
export async function createGenTask(appId: string | number | null | undefined, message: string): Promise<GenTaskVO> {
  const data: { appId?: string | number; message: string } = { message };
  if (appId) {
    data.appId = appId;
  }
  const response = await request.post('/gen-task/create', data);
  return response.data.data;
}

/**
 * 获取任务状态
 */
export async function getTaskStatus(taskId: string | number): Promise<GenTaskVO> {
  const response = await request.get(`/gen-task/status/${taskId}`);
  return response.data.data;
}

/**
 * 取消任务
 */
export async function cancelTask(taskId: string | number): Promise<boolean> {
  const response = await request.post(`/gen-task/cancel/${taskId}`);
  return response.data.data;
}

/**
 * 获取用户活跃任务列表
 */
export async function getActiveTasks(): Promise<GenTaskVO[]> {
  const response = await request.get('/gen-task/active/list');
  return response.data.data;
}

/**
 * 获取指定应用的活跃任务（用于页面刷新后恢复）
 * @param appId 应用ID（使用字符串避免精度丢失）
 */
export async function getActiveTaskByApp(appId: string | number): Promise<GenTaskVO | null> {
  const response = await request.get(`/gen-task/active/${appId}`);
  return response.data.data;
}

/**
 * 获取任务已生成的内容
 */
export async function getGeneratedContent(taskId: string | number): Promise<string> {
  const response = await request.get(`/gen-task/content/${taskId}`);
  return response.data.data;
}

/**
 * 可重连的 SSE 客户端类
 * 注意：taskId 使用 string | number 类型，避免大整数精度丢失
 */
export class ReconnectableSseClient {
  private taskId: string | number;
  private config: SseConnectionConfig;
  private eventSource: EventSource | null = null;
  private retryCount: number = 0;
  private receivedContentLength: number = 0;
  private contentBuffer: string = '';
  private isManualClose: boolean = false;
  private baseURL: string;

  constructor(taskId: string | number, config: SseConnectionConfig = {}) {
    this.taskId = taskId;
    this.config = {
      autoReconnect: true,
      maxRetries: 5,
      retryInterval: 3000,
      ...config,
    };
    // 获取 baseURL
    this.baseURL = request.defaults.baseURL || API_BASE_URL;
  }

  /**
   * 建立连接
   */
  connect(offset?: number): void {
    if (this.eventSource) {
      this.eventSource.close();
    }

    this.isManualClose = false;
    const contentOffset = offset ?? this.receivedContentLength;
    const url = `${this.baseURL}/gen-task/stream/${this.taskId}?offset=${contentOffset}`;
    
    this.eventSource = new EventSource(url, { withCredentials: true });

    // 连接打开
    this.eventSource.onopen = () => {
      console.log(`[SSE] 连接已建立，taskId: ${this.taskId}`);
      this.retryCount = 0;
      this.config.onOpen?.();
    };

    // 默认消息（数据块）
    this.eventSource.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        if (data.d) {
          this.contentBuffer += data.d;
          this.receivedContentLength += data.d.length;
          this.config.onData?.(data.d);
        }
      } catch (e) {
        console.error('[SSE] 解析数据失败:', e);
      }
    };

    // 状态更新事件
    this.eventSource.addEventListener('status', (event: MessageEvent) => {
      try {
        const data = JSON.parse(event.data);
        this.config.onStatusChange?.(data.status, data.queuePosition);
      } catch (e) {
        console.error('[SSE] 解析状态失败:', e);
      }
    });

    // 队列位置更新事件
    this.eventSource.addEventListener('queue', (event: MessageEvent) => {
      try {
        const data = JSON.parse(event.data);
        this.config.onQueueUpdate?.(data.position);
      } catch (e) {
        console.error('[SSE] 解析队列位置失败:', e);
      }
    });

    // 重连恢复数据事件
    this.eventSource.addEventListener('reconnect', (event: MessageEvent) => {
      try {
        const data = JSON.parse(event.data);
        if (data.missedContent) {
          console.log(`[SSE] 重连恢复数据，长度: ${data.missedContent.length}`);
          this.contentBuffer += data.missedContent;
          this.receivedContentLength += data.missedContent.length;
          this.config.onData?.(data.missedContent);
        }
      } catch (e) {
        console.error('[SSE] 解析重连数据失败:', e);
      }
    });

    // 完成事件
    this.eventSource.addEventListener('done', () => {
      console.log(`[SSE] 任务完成，taskId: ${this.taskId}`);
      this.config.onComplete?.();
      this.close();
    });

    // 错误事件
    this.eventSource.addEventListener('error', (event: MessageEvent) => {
      try {
        const data = JSON.parse(event.data);
        console.error(`[SSE] 任务失败:`, data.message);
        this.config.onError?.(data.message);
      } catch (e) {
        // 可能是连接错误而非业务错误
      }
      this.close();
    });

    // 取消事件
    this.eventSource.addEventListener('cancelled', () => {
      console.log(`[SSE] 任务已取消，taskId: ${this.taskId}`);
      this.config.onError?.('任务已取消');
      this.close();
    });

    // 连接错误处理
    this.eventSource.onerror = () => {
      console.error(`[SSE] 连接错误，taskId: ${this.taskId}`);
      
      if (this.isManualClose) {
        return;
      }

      this.eventSource?.close();
      this.eventSource = null;

      // 尝试重连
      if (this.config.autoReconnect && this.retryCount < (this.config.maxRetries ?? 5)) {
        this.retryCount++;
        console.log(`[SSE] 将在 ${this.config.retryInterval}ms 后重连，重试次数: ${this.retryCount}`);
        
        setTimeout(() => {
          if (!this.isManualClose) {
            this.connect();
          }
        }, this.config.retryInterval);
      } else {
        this.config.onError?.('连接失败，已达最大重试次数');
        this.config.onClose?.();
      }
    };
  }

  /**
   * 关闭连接
   */
  close(): void {
    this.isManualClose = true;
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
    this.config.onClose?.();
  }

  /**
   * 获取已接收的内容
   */
  getContent(): string {
    return this.contentBuffer;
  }

  /**
   * 获取已接收内容的长度
   */
  getContentLength(): number {
    return this.receivedContentLength;
  }

  /**
   * 检查连接状态
   */
  isConnected(): boolean {
    return this.eventSource !== null && this.eventSource.readyState === EventSource.OPEN;
  }
}
