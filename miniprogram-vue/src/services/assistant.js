import { request } from './request'
import storage from './storage'

function authHeader() {
  const token = storage.getAuthToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

export default {
  async bootstrap() {
    return request({ url: '/api/assistant/bootstrap', header: authHeader() })
  },
  async getSessions() {
    return request({ url: '/api/assistant/sessions', header: authHeader() })
  },
  async getMessages(sessionId) {
    return request({ url: `/api/assistant/sessions/${sessionId}/messages`, header: authHeader() })
  },
  async ask(payload) {
    return request({
      url: '/api/assistant/ask',
      method: 'POST',
      data: payload,
      header: authHeader(),
    })
  },
  async resetSession(sessionId) {
    return request({
      url: `/api/assistant/sessions/${sessionId}/reset`,
      method: 'POST',
      header: authHeader(),
    })
  },
}
