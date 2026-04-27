export interface ApiResponse<T> {
  code: number
  msg: string
  data: T
}

export interface PageResponse<T> {
  list: T[]
  total: number
  page: number
  size: number
}

export interface FaqItem {
  question: string
  answer: string
}

export interface BoardRoleItem {
  roleId: number
  count: number
}

export interface Board {
  id: number
  name: string
  playerCount: number
  difficulty: string
  tags: string[]
  coverImage: string
  cardDescription: string
  briefConfig: string
  specialRules: string[]
  tips: string[]
  faqs: FaqItem[]
  winCondition: string
  ruleType: string
  status: number
  roles: BoardRoleItem[]
}

export interface Role {
  id: number
  name: string
  alias: string | null
  faction: string
  roleType: string
  camp: string
  skill: string
  background: string
  faqs: FaqItem[]
  portrait: string
  fullIllustration: string
}

export interface UserView {
  openid: string
  nickname: string
  avatar: string
  status: number
  createTime: string
}

export interface LoginResponse {
  token: string
  user: UserView
}

export interface DashboardSummary {
  boardCount: number
  roleCount: number
  postCount: number
  commentCount: number
  openReportCount: number
}

export interface PostSummary {
  id: number
  openid: string
  nickname: string
  avatar: string
  postType: string
  title: string
  summary: string
  content: string
  images: string[]
  boardId: number | null
  boardName: string | null
  roleTags: string[]
  tagList: string[]
  sessionId: string | null
  qualityScore: number
  hotScore: number
  viewCount: number
  likeCount: number
  commentCount: number
  favoriteCount: number
  status: string
  featured: boolean
  pinned: boolean
  liked: boolean
  favorited: boolean
  owned: boolean
  createTime: string
  updateTime: string
}

export interface CommentView {
  id: number
  postId: number
  openid: string
  nickname: string
  avatar: string
  parentCommentId: number | null
  replyToOpenid: string | null
  replyToNickname: string | null
  content: string
  likeCount: number
  liked: boolean
  owned: boolean
  postAuthor: boolean
  status: string
  createTime: string
  updateTime: string
}

export interface ReportItem {
  id: number
  targetType: string
  targetId: number
  openid: string
  reason: string
  processStatus: string
  processBy: string | null
  processTime: string | null
  createTime: string
}

export type AiAnswerType =
  | 'RAG_ANSWER'
  | 'WEB_AUGMENTED_ANSWER'
  | 'WEB_ONLY_ANSWER'
  | 'CLARIFICATION'
  | 'NO_EVIDENCE'
  | 'OUT_OF_SCOPE'
  | 'ERROR'

export interface AiSource {
  chunkUid: string
  title: string
  sectionPath: string
  subject: string
  content: string
  score: number
  sourceType: string
  url?: string | null
}

export interface AiBootstrap {
  enabled: boolean
  knowledgeBaseEnabled: boolean
  webSearchEnabled: boolean
  postgresReady: boolean
  chatReady: boolean
  currentMode: string
  unavailableReason: string | null
  quickQuestions: string[]
}

export interface AiAdminConfig {
  enabled: boolean
  knowledgeBaseEnabled: boolean
  webSearchEnabled: boolean
  postgresReady: boolean
  chatReady: boolean
  embeddingReady: boolean
  topK: number
  minScore: number
  maxEvidenceChars: number
  chatModel: string
  embeddingModel: string
  chatApiKeyMasked: string | null
  embeddingApiKeyMasked: string | null
}

export interface AiAdminConfigUpdate {
  enabled?: boolean
  knowledgeBaseEnabled?: boolean
  webSearchEnabled?: boolean
  topK?: number
  minScore?: number
  maxEvidenceChars?: number
  chatModel?: string
  embeddingModel?: string
  chatApiKey?: string
  embeddingApiKey?: string
}

export interface AiAdminDocument {
  id: number
  documentUid: string
  domain: string
  title: string
  sourceType: string
  reviewStatus: string
  parseStatus: string
  summary: string
  chunkCount: number
  active: boolean
  createdAt: string
  updatedAt: string
}

export interface AiAdminPublish {
  versionKey: string
  status: string
  active: boolean
  documentCount: number
  chunkCount: number
  createdAt: string
  activatedAt: string | null
}

export interface AiAdminLog {
  traceId: string
  sessionId: string
  question: string
  answerType: AiAnswerType | string
  subject: string | null
  retrievalMode: string
  hitCount: number
  webUsed: boolean
  failureReason: string | null
  latencyMs: number | null
  createdAt: string
}

export interface AiAdminDebugResponse {
  query: string
  subject: string | null
  intent: string
  outOfScope: boolean
  hits: AiSource[]
  meta: Record<string, unknown>
}

export interface AiAdminEvalCase {
  id: number
  question: string
  expectedSubject: string | null
  expectedKeywords: string | null
  category: string | null
  enabled: boolean
  createdAt: string
}
