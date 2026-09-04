/** 后端统一响应 Result（3.2）：code=0 成功；否则业务错误码（10xxx-16xxx）。 */
export interface Result<T = unknown> {
  code: number
  message: string
  requestId?: string
  data: T
}

export interface PageResult<T> {
  items: T[]
  nextPageToken?: string
  total: number
}

export interface LoginResponse {
  token: string
  tenantId: number
  userId: number
  name: string
  role: string
}

export interface AgentRun {
  id: number
  tenantId: number
  sessionId: string
  userId: number
  goal: string
  /** 模型最终回复摘要（完成时由后端回写） */
  summary?: string
  status: string
  tokensUsed?: number
  createdAt?: string
  updatedAt?: string
}

export interface Row {
  [key: string]: unknown
}