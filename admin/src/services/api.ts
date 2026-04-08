import axios from 'axios'
import type {
  ApiResponse,
  Board,
  CommentView,
  DashboardSummary,
  LoginResponse,
  PageResponse,
  PostSummary,
  ReportItem,
  Role,
} from '../types'

const TOKEN_KEY = 'wolfbook_admin_token'

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080',
  timeout: 15000,
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

async function unwrap<T>(promise: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  const response = await promise
  if (response.data.code !== 0) {
    throw new Error(response.data.msg || '请求失败')
  }
  return response.data.data
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
  async deleteBoard(id: number) {
    return unwrap<void>(http.delete(`/admin/boards/${id}`))
  },
  async getRoles() {
    return unwrap<Role[]>(http.get('/admin/roles'))
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
  async getPosts() {
    return unwrap<PageResponse<PostSummary>>(http.get('/admin/posts'))
  },
  async updatePostStatus(id: number, status: string) {
    return unwrap<void>(http.patch(`/admin/posts/${id}/status`, { status }))
  },
  async deletePost(id: number) {
    return unwrap<void>(http.delete(`/admin/posts/${id}`))
  },
  async getComments() {
    return unwrap<PageResponse<CommentView>>(http.get('/admin/comments'))
  },
  async deleteComment(id: number) {
    return unwrap<void>(http.delete(`/admin/comments/${id}`))
  },
  async getReports() {
    return unwrap<PageResponse<ReportItem>>(http.get('/admin/reports'))
  },
  async processReport(id: number, processStatus: string) {
    return unwrap<ReportItem>(http.patch(`/admin/reports/${id}`, { processStatus }))
  },
  async upload(file: File) {
    const formData = new FormData()
    formData.append('file', file)
    return unwrap<{ url: string }>(
      http.post('/admin/upload', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      }),
    )
  },
}
