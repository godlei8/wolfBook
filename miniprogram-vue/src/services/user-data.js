import api from './api'
import storage from './storage'
import { normalizeSession, normalizeSessions } from '../utils/session/normalizer'

let favoriteCache = null
let sessionCache = null
let favoriteCacheToken = ''
let sessionCacheToken = ''

function currentAuthToken() {
  return storage.getAuthToken() || ''
}

function syncCacheScope() {
  const token = currentAuthToken()
  if (token !== favoriteCacheToken) {
    favoriteCache = null
    favoriteCacheToken = token
  }
  if (token !== sessionCacheToken) {
    sessionCache = null
    sessionCacheToken = token
  }
  return token
}

function hasAuthToken() {
  return !!syncCacheScope()
}

function mergeIds(remoteIds = [], localIds = []) {
  const merged = remoteIds.slice()
  localIds.forEach((id) => {
    if (!merged.includes(id)) {
      merged.push(id)
    }
  })
  return merged
}

function sortSessions(list = []) {
  return normalizeSessions(list)
    .slice()
    .sort((left, right) => new Date(right.updateTime || right.createTime || 0).getTime() - new Date(left.updateTime || left.createTime || 0).getTime())
}

function mergeSessions(remoteSessions = [], localSessions = []) {
  const merged = new Map(normalizeSessions(remoteSessions).map((item) => [item.sessionId, item]))
  normalizeSessions(localSessions).forEach((localItem) => {
    const remoteItem = merged.get(localItem.sessionId)
    if (!remoteItem) {
      merged.set(localItem.sessionId, localItem)
      return
    }
    const remoteTime = new Date(remoteItem.updateTime || remoteItem.createTime || 0).getTime()
    const localTime = new Date(localItem.updateTime || localItem.createTime || 0).getTime()
    if (localTime > remoteTime) {
      merged.set(localItem.sessionId, localItem)
    }
  })
  return sortSessions(Array.from(merged.values()))
}

async function migrateFavoritesIfNeeded() {
  syncCacheScope()
  if (!hasAuthToken()) {
    return {
      boardIds: storage.getFavorites(),
      boards: [],
      cloud: false,
    }
  }

  let remote = await api.getFavoriteBoards()
  const localIds = storage.getFavorites()
  if (localIds.length) {
    const mergedIds = mergeIds(remote.boardIds, localIds)
    const missingIds = mergedIds.filter((id) => !remote.boardIds.includes(id))
    for (const boardId of missingIds) {
      remote = await api.addFavoriteBoard(boardId)
    }
    storage.clearFavorites()
  }
  favoriteCache = {
    ...remote,
    cloud: true,
  }
  return favoriteCache
}

async function migrateSessionsIfNeeded() {
  syncCacheScope()
  if (!hasAuthToken()) {
    return sortSessions(storage.getSessions())
  }

  let remoteSessions = await api.getUserSessions()
  const localSessions = storage.getSessions()
  if (localSessions.length) {
    const merged = mergeSessions(remoteSessions, localSessions)
    const remoteMap = new Map(remoteSessions.map((item) => [item.sessionId, item]))
    for (const item of merged) {
      const remoteItem = remoteMap.get(item.sessionId)
      const remoteTime = remoteItem ? new Date(remoteItem.updateTime || remoteItem.createTime || 0).getTime() : 0
      const currentTime = new Date(item.updateTime || item.createTime || 0).getTime()
      if (!remoteItem || currentTime > remoteTime) {
        await api.saveUserSession(item)
      }
    }
    storage.clearSessions()
    remoteSessions = await api.getUserSessions()
  }
  sessionCache = sortSessions(remoteSessions)
  return sessionCache
}

export default {
  hasAuthToken,
  async loadFavoriteBoards() {
    return migrateFavoritesIfNeeded()
  },
  async toggleFavorite(boardId) {
    syncCacheScope()
    if (!hasAuthToken()) {
      const boardIds = storage.toggleFavorite(boardId)
      favoriteCache = {
        boardIds,
        boards: [],
        cloud: false,
      }
      return favoriteCache
    }
    const current = favoriteCache || (await migrateFavoritesIfNeeded())
    const next = current.boardIds.includes(boardId)
      ? await api.removeFavoriteBoard(boardId)
      : await api.addFavoriteBoard(boardId)
    favoriteCache = {
      ...next,
      cloud: true,
    }
    return favoriteCache
  },
  async loadSessions() {
    return migrateSessionsIfNeeded()
  },
  async getSessionById(sessionId) {
    syncCacheScope()
    if (!hasAuthToken()) {
      return normalizeSession(storage.getSessionById(sessionId))
    }
    const session = normalizeSession(await api.getUserSessionDetail(sessionId))
    if (sessionCache) {
      const next = sessionCache.slice()
      const index = next.findIndex((item) => item.sessionId === session.sessionId)
      if (index >= 0) {
        next[index] = session
      } else {
        next.unshift(session)
      }
      sessionCache = sortSessions(next)
    }
    return session
  },
  async saveSession(session) {
    syncCacheScope()
    const normalized = normalizeSession(session)
    if (!hasAuthToken()) {
      return storage.upsertSession(normalized)
    }
    const saved = normalizeSession(await api.saveUserSession(normalized))
    const source = sessionCache ? sessionCache.slice() : []
    const index = source.findIndex((item) => item.sessionId === saved.sessionId)
    if (index >= 0) {
      source[index] = saved
    } else {
      source.unshift(saved)
    }
    sessionCache = sortSessions(source)
    storage.deleteSession(saved.sessionId)
    return saved
  },
  async deleteSession(sessionId) {
    syncCacheScope()
    if (!hasAuthToken()) {
      return storage.deleteSession(sessionId)
    }
    await api.deleteUserSession(sessionId)
    sessionCache = (sessionCache || []).filter((item) => item.sessionId !== sessionId)
    storage.deleteSession(sessionId)
    return sessionCache
  },
}
