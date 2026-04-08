const AUTH_TOKEN_KEY = 'auth_token'
const USER_PROFILE_KEY = 'user_profile'
const FAVORITES_KEY = 'favorite_boards'
const SESSIONS_KEY = 'werewolf_sessions'

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

function normalizeRecord(record, index) {
  if (!record || typeof record !== 'object') {
    return null
  }
  return {
    id: String(record.id || `rec_${Date.now()}_${index}`),
    type: record.type || 'speech',
    round: Number(record.round) || 1,
    content: typeof record.content === 'string' ? record.content : '',
    player: typeof record.player === 'string' ? record.player : '',
    timestamp: record.timestamp || new Date().toISOString(),
  }
}

function normalizeSession(session, index) {
  if (!session || typeof session !== 'object') {
    return null
  }
  const boardId = Number(session.boardId)
  const hasBoardId = Number.isFinite(boardId) && boardId > 0
  const records = toArray(session.records)
    .map(normalizeRecord)
    .filter(Boolean)

  return {
    sessionId: String(session.sessionId || session.id || `session_${Date.now()}_${index}`),
    boardMode: session.boardMode === 'library' || (hasBoardId && session.boardMode !== 'custom') ? 'library' : 'custom',
    boardId: hasBoardId ? boardId : null,
    boardName: typeof session.boardName === 'string' ? session.boardName : '',
    playerCount: Number(session.playerCount) || 12,
    createTime: session.createTime || session.updateTime || new Date().toISOString(),
    updateTime: session.updateTime || session.createTime || new Date().toISOString(),
    records,
  }
}

function normalizeSessions(value) {
  return toArray(value)
    .map(normalizeSession)
    .filter(Boolean)
    .sort((left, right) => new Date(right.updateTime).getTime() - new Date(left.updateTime).getTime())
}

function persistIfChanged(key, raw, normalized) {
  const rawText = JSON.stringify(raw)
  const normalizedText = JSON.stringify(normalized)
  if (rawText !== normalizedText) {
    setJson(key, normalized)
  }
}

export default {
  getAuthToken() {
    return uni.getStorageSync(AUTH_TOKEN_KEY) || ''
  },
  setAuthToken(token) {
    uni.setStorageSync(AUTH_TOKEN_KEY, token)
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
  clearAllLocalData() {
    uni.removeStorageSync(AUTH_TOKEN_KEY)
    uni.removeStorageSync(USER_PROFILE_KEY)
    uni.removeStorageSync(FAVORITES_KEY)
    uni.removeStorageSync(SESSIONS_KEY)
  },
}
