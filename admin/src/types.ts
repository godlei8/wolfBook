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
  backgroundImage: string | null
  cardDescription: string
  briefConfig: string
  specialRules: string[]
  tips: string[]
  faqs: FaqItem[]
  ruleDetail: BoardRuleDetail
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
  themeColor: string | null
}

/** 夜间行动顺序里的一步，roleId 必须在本板子的阵容里。 */
export interface NightStep {
  roleId: number
  action: string
}

/** 某个角色在本板子里的细则。 */
export interface RoleRuleNote {
  roleId: number
  items: string[]
}

/** 不绑定角色的规则章节，比如警长竞选、遗言、平票处理。 */
export interface RuleSection {
  title: string
  items: string[]
}

export interface BoardRuleDetail {
  overview: string | null
  nightOrder: NightStep[]
  roleRules: RoleRuleNote[]
  sections: RuleSection[]
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
  content: string
  images: string[]
  likeCount: number
  commentCount: number
  status: number
  liked: boolean
  createTime: string
}

export interface CommentView {
  id: number
  postId: number
  openid: string
  nickname: string
  avatar: string
  content: string
  likeCount: number
  liked: boolean
  status: string
  createTime: string
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

export interface AssistantAppearance {
  mascot: string
  accentColor: string
  dockLabel: string
}

export interface AssistantFeatureFlags {
  webSearchEnabled: boolean
  historyEnabled: boolean
}

export interface AssistantBootstrapResponse {
  enabled: boolean
  welcomeMessage: string
  quickQuestions: string[]
  latestSessionId: string | null
  appearance: AssistantAppearance
  featureFlags: AssistantFeatureFlags
}

export interface AdminAiConfig {
  base: {
    enabled: boolean
    welcomeMessage: string
    quickQuestions: string[]
    temperature: number
    maxSuggestions: number
  }
  provider: {
    platform: string
    model: string
    baseUrl: string
    apiKey: string
  }
  volcengine: {
    baseUrl: string
    embeddingModel: string
    embeddingApiKey: string
    searchModel: string
    searchApiKey: string
  }
  prompt: {
    systemPrompt: string
    recommendationPrompt: string
    refusalPrompt: string
  }
  retrieval: {
    topK: number
    similarityThreshold: number
    historyWindow: number
  }
  search: {
    webSearchEnabled: boolean
  }
  safety: {
    unsupportedMessage: string
    blockedKeywords: string[]
  }
  ui: {
    mascot: string
    dockLabel: string
    accentColor: string
  }
}

export interface AdminAiDocument {
  id: number
  name: string
  fileName: string | null
  sourceType: string
  sourceKey: string
  sourceId: string | null
  summary: string | null
  chunkCount: number
  processingStatus: string
  reviewStatus: string
  publishVersionId: number | null
  lastError: string | null
  createTime: string
  updateTime: string
}

export interface AdminAiVersion {
  id: number
  versionName: string
  notes: string | null
  documentIds: number[]
  current: boolean
  publishedBy: string | null
  createTime: string
}

export interface AdminAiLog {
  id: number
  openid: string | null
  sessionId: string | null
  userMessage: string
  answerType: string
  hitSources: string[]
  usedWebSearch: boolean
  latencyMs: number
  success: boolean
  failureType: string | null
  traceId: string
  createTime: string
}
