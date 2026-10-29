import axios from 'axios'
import type {
  AdminAiConfig,
  AdminAiDocument,
  AdminAiLog,
  AdminAiModel,
  AdminAiModelPayload,
  ModelTestResult,
  AdminAiVersion,
  AdminPlaygroundRequest,
  AdminPlaygroundResult,
  ApiResponse,
  Board,
  CommentView,
  DashboardSummary,
  LoginResponse,
  PageResponse,
  PostSummary,
  ReportItem,
  Role,
  RoleDraft,
  RoleDraftRequest,
  WorldLore,
} from '../types'

const TOKEN_KEY = 'wolfbook_admin_token'
const DEFAULT_TIMEOUT = 15000
const AI_UPLOAD_TIMEOUT = 120000
const AI_LONG_TASK_TIMEOUT = 300000
// 「按世界观起草」要等大模型写完一段身世
const ROLE_DRAFT_TIMEOUT = 60000

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? (import.meta.env.DEV ? 'http://localhost:8088' : '/'),
  timeout: DEFAULT_TIMEOUT,
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

export const UNAUTHORIZED_EVENT = 'wolfbook-admin:unauthorized'
// 后台审核列表一次最多拉取的条数（前端在本页内做筛选）
const ADMIN_LIST_SIZE = 500

export class ApiError extends Error {
  code: number

  constructor(code: number, message: string) {
    super(message)
    this.code = code
  }
}

export function isUnauthorizedError(error: unknown) {
  return error instanceof ApiError && error.code === 4001
}

async function unwrap<T>(promise: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  const response = await promise
  if (response.data.code !== 0) {
    if (response.data.code === 4001) {
      // token 失效 / 被禁用：清掉本地登录态并通知 App 回到登录页
      clearStoredToken()
      window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT))
    }
    throw new ApiError(response.data.code, response.data.msg || '请求失败')
  }
  return response.data.data
}

async function uploadMultipart<T>(url: string, file: File, timeout = DEFAULT_TIMEOUT) {
  const formData = new FormData()
  formData.append('file', file)
  return unwrap<T>(
    http.post(url, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout,
    }),
  )
}

export function getStoredToken() {
  return localStorage.getItem(TOKEN_KEY)
}

export function setStoredToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearStoredToken() {
  localStorage.removeItem(TOKEN_KEY)
}

export const api = {
  async adminLogin(username: string, password: string) {
    return unwrap<LoginResponse>(http.post('/admin/login', { username, password }))
  },
  async getSummary() {
    return unwrap<DashboardSummary>(http.get('/admin/summary'))
  },
  async getBoards() {
    return unwrap<Board[]>(http.get('/admin/boards'))
  },
  async createBoard(payload: Partial<Board>) {
    return unwrap<Board>(http.post('/admin/boards', payload))
  },
  async updateBoard(id: number, payload: Partial<Board>) {
    return unwrap<Board>(http.put(`/admin/boards/${id}`, payload))
  },
  async updateBoardStatus(id: number, online: boolean) {
    return unwrap<Board>(http.patch(`/admin/boards/${id}/status`, { status: online ? '1' : '0' }))
  },
  async deleteBoard(id: number) {
    return unwrap<void>(http.delete(`/admin/boards/${id}`))
  },
  async getRoles() {
    return unwrap<Role[]>(http.get('/admin/roles'))
  },
  async draftRole(payload: RoleDraftRequest) {
    return unwrap<RoleDraft>(http.post('/admin/roles/draft', payload, { timeout: ROLE_DRAFT_TIMEOUT }))
  },
  async createRole(payload: Partial<Role>) {
    return unwrap<Role>(http.post('/admin/roles', payload))
  },
  async updateRole(id: number, payload: Partial<Role>) {
    return unwrap<Role>(http.put(`/admin/roles/${id}`, payload))
  },
  async deleteRole(id: number) {
    return unwrap<void>(http.delete(`/admin/roles/${id}`))
  },
  async getWorld() {
    return unwrap<WorldLore>(http.get('/admin/world'))
  },
  async saveWorld(payload: WorldLore) {
    return unwrap<WorldLore>(http.put('/admin/world', payload))
  },
  async getPosts() {
    return unwrap<PageResponse<PostSummary>>(http.get('/admin/posts', { params: { page: 1, size: ADMIN_LIST_SIZE } }))
  },
  async updatePostStatus(id: number, status: string) {
    return unwrap<void>(http.patch(`/admin/posts/${id}/status`, { status }))
  },
  async deletePost(id: number) {
    return unwrap<void>(http.delete(`/admin/posts/${id}`))
  },
  async getComments() {
    return unwrap<PageResponse<CommentView>>(http.get('/admin/comments', { params: { page: 1, size: ADMIN_LIST_SIZE } }))
  },
  async deleteComment(id: number) {
    return unwrap<void>(http.delete(`/admin/comments/${id}`))
  },
  async getReports() {
    return unwrap<PageResponse<ReportItem>>(http.get('/admin/reports', { params: { page: 1, size: ADMIN_LIST_SIZE } }))
  },
  async processReport(id: number, processStatus: string) {
    return unwrap<ReportItem>(http.patch(`/admin/reports/${id}`, { processStatus }))
  },
  async upload(file: File) {
    return uploadMultipart<{ url: string }>('/admin/upload', file)
  },
  async getAiConfig() {
    return unwrap<AdminAiConfig>(http.get('/admin/ai/config'))
  },
  async saveAiConfig(payload: AdminAiConfig) {
    return unwrap<AdminAiConfig>(http.put('/admin/ai/config', payload))
  },
  async getAiModels() {
    return unwrap<AdminAiModel[]>(http.get('/admin/ai/models'))
  },
  async createAiModel(payload: AdminAiModelPayload) {
    return unwrap<AdminAiModel>(http.post('/admin/ai/models', payload))
  },
  async updateAiModel(id: number, payload: AdminAiModelPayload) {
    return unwrap<AdminAiModel>(http.put(`/admin/ai/models/${id}`, payload))
  },
  async deleteAiModel(id: number) {
    return unwrap<void>(http.delete(`/admin/ai/models/${id}`))
  },
  async activateAiModel(id: number) {
    return unwrap<AdminAiModel>(http.post(`/admin/ai/models/${id}/activate`))
  },
  // 测试连接会真的请求一次模型，慢模型可能要十几秒
  async testAiModel(payload: { id?: number; baseUrl?: string; model?: string; apiKey?: string; disableThinking?: boolean }) {
    return unwrap<ModelTestResult>(http.post('/admin/ai/models/test', payload, { timeout: 90000 }))
  },
  async listRemoteAiModels(payload: { id?: number; baseUrl?: string; apiKey?: string }) {
    return unwrap<string[]>(http.post('/admin/ai/models/remote', payload, { timeout: 30000 }))
  },
  async getAiDocuments() {
    return unwrap<AdminAiDocument[]>(http.get('/admin/ai/documents'))
  },
  async uploadAiDocument(file: File) {
    return uploadMultipart<AdminAiDocument>('/admin/ai/documents', file, AI_UPLOAD_TIMEOUT)
  },
  async updateAiDocumentReview(id: number, reviewStatus: string) {
    return unwrap<AdminAiDocument>(http.patch(`/admin/ai/documents/${id}`, { reviewStatus }))
  },
  async reindexAiDocument(id: number) {
    return unwrap<AdminAiDocument>(
      http.post(`/admin/ai/documents/${id}/reindex`, undefined, {
        timeout: AI_LONG_TASK_TIMEOUT,
      }),
    )
  },
  async clearAiDocuments() {
    return unwrap<number>(http.delete('/admin/ai/documents'))
  },
  async getAiVersions() {
    return unwrap<AdminAiVersion[]>(http.get('/admin/ai/publish'))
  },
  async publishAiVersion(notes: string) {
    return unwrap<AdminAiVersion>(
      http.post('/admin/ai/publish', { notes }, {
        timeout: AI_LONG_TASK_TIMEOUT,
      }),
    )
  },
  async rollbackAiVersion(versionId: number) {
    return unwrap<AdminAiVersion>(
      http.post('/admin/ai/publish/rollback', { versionId }, {
        timeout: AI_LONG_TASK_TIMEOUT,
      }),
    )
  },
  async runAiPlayground(payload: AdminPlaygroundRequest) {
    return unwrap<AdminPlaygroundResult>(http.post('/admin/ai/playground', payload, { timeout: 120000 }))
  },
  async getAiLogs() {
    return unwrap<AdminAiLog[]>(http.get('/admin/ai/logs'))
  },
  async clearAiLogs() {
    return unwrap<number>(http.delete('/admin/ai/logs'))
  },
}
