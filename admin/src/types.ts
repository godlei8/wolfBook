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
  status: number
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
