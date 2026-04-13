import { normalizeSession as normalizeSessionV2, normalizeSessions as normalizeSessionsV2 } from '../utils/session/normalizer'

const AUTH_TOKEN_KEY = 'auth_token'
const USER_PROFILE_KEY = 'user_profile'
const FAVORITES_KEY = 'favorite_boards'
const SESSIONS_KEY = 'werewolf_sessions'
const ASSISTANT_DOCK_KEY = 'assistant_dock_state'

function parseMaybeJson(value) {
  if (typeof value !== 'string') {
    return value
  }
  const trimmed = value.trim()
  if (!trimmed || (!trimmed.startsWith('{') && !trimmed.startsWith('['))) {
    return value
  }
  try {
    return JSON.parse(trimmed)
  } catch (error) {
    return value
  }
}

function getJson(key, fallback) {
  try {
    const raw = uni.getStorageSync(key)
    const value = parseMaybeJson(raw)
    return value === undefined || value === null || value === '' ? fallback : value
  } catch (error) {
    return fallback
  }
}

function setJson(key, value) {
  uni.setStorageSync(key, value)
}

function toArray(value) {
  const source = parseMaybeJson(value)
  if (Array.isArray(source)) {
    return source
  }
  if (!source || typeof source !== 'object') {
    return []
  }
  if (Array.isArray(source.list)) {
    return source.list
  }
  if (Array.isArray(source.sessions)) {
    return source.sessions
  }
  if (Array.isArray(source.data)) {
    return source.data
  }
  return Object.values(source)
}

function normalizeFavorites(value) {
  return toArray(value)
    .map((item) => Number(item))
    .filter((item) => Number.isFinite(item) && item > 0)
}

function normalizeSession(session, index) {
  return normalizeSessionV2(session, index)
}

function normalizeSessions(value) {
  return normalizeSessionsV2(toArray(value))
}

function persistIfChanged(key, raw, normalized) {
  const rawText = JSON.stringify(raw)
  const normalizedText = JSON.stringify(normalized)
  if (rawText !== normalizedText) {
    setJson(key, normalized)
  }
}

function extractTokenFromObject(value) {
  if (!value || typeof value !== 'object') {
    return ''
  }
  const direct =
    value.token ||
    value.accessToken ||
    value.access_token ||
    value.authorization ||
    value.Authorization
  if (typeof direct === 'string') {
    return direct
  }
  if (value.data && typeof value.data === 'object') {
    return extractTokenFromObject(value.data)
  }
  return ''
}

function normalizeAuthToken(raw) {
  const parsed = parseMaybeJson(raw)
  let token = ''

  if (typeof parsed === 'string') {
    token = parsed
  } else {
    token = extractTokenFromObject(parsed)
  }

  token = typeof token === 'string' ? token.trim() : ''

  if ((token.startsWith('"') && token.endsWith('"')) || (token.startsWith("'") && token.endsWith("'"))) {
    token = token.slice(1, -1).trim()
  }

  if (token.startsWith('Bearer ')) {
    token = token.slice(7).trim()
  }

  return token
}

export default {
  getAuthToken() {
    const raw = uni.getStorageSync(AUTH_TOKEN_KEY)
    const normalized = normalizeAuthToken(raw)

    if (!normalized) {
      if (raw !== '' && raw !== null && raw !== undefined) {
        uni.removeStorageSync(AUTH_TOKEN_KEY)
      }
      return ''
    }

    if (raw !== normalized) {
      uni.setStorageSync(AUTH_TOKEN_KEY, normalized)
    }

    return normalized
  },
  setAuthToken(token) {
    const normalized = normalizeAuthToken(token)
    if (!normalized) {
      uni.removeStorageSync(AUTH_TOKEN_KEY)
      return
    }
    uni.setStorageSync(AUTH_TOKEN_KEY, normalized)
  },
  clearAuthToken() {
    uni.removeStorageSync(AUTH_TOKEN_KEY)
  },
  getUserProfile() {
    return getJson(USER_PROFILE_KEY, null)
  },
  setUserProfile(user) {
    setJson(USER_PROFILE_KEY, user)
  },
  getFavorites() {
    const raw = getJson(FAVORITES_KEY, [])
    const normalized = normalizeFavorites(raw)
    persistIfChanged(FAVORITES_KEY, raw, normalized)
    return normalized
  },
  setFavorites(list) {
    setJson(FAVORITES_KEY, normalizeFavorites(list))
  },
  toggleFavorite(boardId) {
    const favorites = this.getFavorites()
    const next = favorites.includes(boardId)
      ? favorites.filter((id) => id !== boardId)
      : favorites.concat(boardId)
    setJson(FAVORITES_KEY, next)
    return next
  },
  getSessions() {
    const raw = getJson(SESSIONS_KEY, [])
    const normalized = normalizeSessions(raw)
    persistIfChanged(SESSIONS_KEY, raw, normalized)
    return normalized
  },
  getSessionById(sessionId) {
    return this.getSessions().find((item) => item.sessionId === sessionId) || null
  },
  setSessions(list) {
    setJson(SESSIONS_KEY, normalizeSessions(list))
  },
  upsertSession(session) {
    const sessions = this.getSessions()
    const next = sessions.slice()
    const normalized = normalizeSession(session, next.length)
    if (!normalized) {
      return null
    }
    const index = next.findIndex((item) => item.sessionId === normalized.sessionId)
    if (index >= 0) {
      next[index] = normalized
    } else {
      next.unshift(normalized)
    }
    this.setSessions(next)
    return normalized
  },
  deleteSession(sessionId) {
    const next = this.getSessions().filter((item) => item.sessionId !== sessionId)
    this.setSessions(next)
    return next
  },
  clearFavorites() {
    uni.removeStorageSync(FAVORITES_KEY)
  },
  clearSessions() {
    uni.removeStorageSync(SESSIONS_KEY)
  },
  getAssistantDockState() {
    return getJson(ASSISTANT_DOCK_KEY, { side: 'right', top: 420 })
  },
  setAssistantDockState(value) {
    setJson(ASSISTANT_DOCK_KEY, value)
  },
  clearAllLocalData() {
    uni.removeStorageSync(AUTH_TOKEN_KEY)
    uni.removeStorageSync(USER_PROFILE_KEY)
    uni.removeStorageSync(FAVORITES_KEY)
    uni.removeStorageSync(SESSIONS_KEY)
    uni.removeStorageSync(ASSISTANT_DOCK_KEY)
  },
}
