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

/** 知识库条目（租户维度，对话检索注入上下文）。
 *  V14 本体化：recordType 记录类别、lifecycle 生命周期(active 现行/superseded 被取代/obsolete 废弃)、
 *  supersedesId 取代边（本条取代哪条旧条目）。 */
export interface KnowledgeEntry {
  id: number
  tenantId: number
  title: string
  content: string
  tags?: string[]
  enabled: boolean
  recordType?: string
  lifecycle?: string
  supersedesId?: number | null
  createdAt?: string
  updatedAt?: string
}

/** 知识库试检索命中（与 Agent 注入同源打分）。 */
export interface KnowledgeHit {
  id: number
  title: string
  tags: string[]
  score: number
  recordType?: string
  lifecycle?: string
}

/** 知识图谱关系边（V15 knowledge_link + supersedes 取代边合并；supersedes 边 linkId 为空）。 */
export interface KnowledgeGraphEdge {
  source: number
  target: number
  /** related | supports | refines | conflicts | supersedes */
  relation: string
  linkId?: number | null
}

/** 知识图谱（V15）：节点=全部条目，边=类型化关系+取代链。 */
export interface KnowledgeGraph {
  nodes: KnowledgeEntry[]
  edges: KnowledgeGraphEdge[]
}

/** 消息模板（租户维度，审核流 DRAFT→PENDING→APPROVED|REJECTED，REVIEWER 审批）。 */
export interface Template {
  id: number
  tenantId: number
  channel: string
  title: string
  content: string
  /** 由后端从 content 的 {{占位符}} 自动提取 */
  vars?: string[]
  /** DRAFT|PENDING|APPROVED|REJECTED */
  reviewStatus?: string
  createdAt?: string
}

export interface Row {
  [key: string]: unknown
}