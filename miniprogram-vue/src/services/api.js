import { BASE_URL } from './config'
import { request, uploadFile } from './request'
import storage from './storage'

function authHeader() {
  const token = storage.getAuthToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

function withBaseUrl(url) {
  if (!url) return url
  if (url.startsWith('http://') || url.startsWith('https://') || url.startsWith('wxfile://')) {
    return url
  }
  if (url.startsWith('/')) {
    return `${BASE_URL}${url}`
  }
  return url
}

function shouldShowCount(role) {
  return role.count > 1 || role.faction === '狼人' || role.faction === '第三方' || role.roleType === '平民'
}

function buildLineupSummary(roles = []) {
  const parts = roles.map((role) => (shouldShowCount(role) ? `${role.count}${role.name}` : role.name))
  return parts.join(' ')
}

function buildCardRoles(roles = [], featuredRoles = []) {
  const source = featuredRoles.length ? featuredRoles : roles
  return source.slice(0, 7).map((role) => ({
    ...role,
    toneClass: role.faction === '狼人' ? 'wolf' : role.faction === '第三方' ? 'third' : 'good',
  }))
}

function normalizeBoardRole(role) {
  return {
    ...role,
    portrait: withBaseUrl(role.portrait),
  }
}

function normalizeBoard(board) {
  if (!board) return board
  const roles = (board.roles || []).map(normalizeBoardRole)
  const featuredRoles = (board.featuredRoles || []).map(normalizeBoardRole)
  return {
    ...board,
    coverImage: withBaseUrl(board.coverImage),
    roles,
    featuredRoles,
    cardDescription: board.cardDescription || board.summary || '',
    lineupSummary: board.lineupSummary || buildLineupSummary(roles),
    cardRoles: buildCardRoles(roles, featuredRoles),
  }
}

function normalizeRole(role) {
  if (!role) return role
  return {
    ...role,
    portrait: withBaseUrl(role.portrait),
    fullIllustration: withBaseUrl(role.fullIllustration),
    boards: (role.boards || []).map((board) => ({
      ...board,
      coverImage: withBaseUrl(board.coverImage),
    })),
  }
}

function normalizeComment(comment) {
  return {
    ...comment,
    avatar: withBaseUrl(comment.avatar),
  }
}

function normalizeUser(user) {
  if (!user) return user
  return {
    ...user,
    avatar: withBaseUrl(user.avatar),
  }
}

function normalizeNoteRecord(record) {
  if (!record) return record
  return {
    ...record,
    id: record.id || record.recordId,
    timestamp: record.timestamp || record.createTime,
  }
}

function normalizeNoteSession(session) {
  if (!session) return session
  return {
    ...session,
    records: (session.records || []).map(normalizeNoteRecord),
  }
}

function normalizePost(post) {
  if (!post) return post
  return {
    ...post,
    avatar: withBaseUrl(post.avatar),
    images: (post.images || []).map(withBaseUrl),
    comments: (post.comments || []).map(normalizeComment),
  }
}

export default {
  async getBoards(params = {}) {
    const data = await request({ url: '/api/boards', data: params, header: authHeader() })
    return {
      ...data,
      list: (data.list || []).map(normalizeBoard),
    }
  },
  async getBoardDetail(id) {
    return normalizeBoard(await request({ url: `/api/boards/${id}`, header: authHeader() }))
  },
  async getRoles(camp = '') {
    return (await request({ url: '/api/roles', data: { camp }, header: authHeader() })).map(normalizeRole)
  },
  async getRoleDetail(id) {
    return normalizeRole(await request({ url: `/api/roles/${id}`, header: authHeader() }))
  },
  async getPosts(page = 1, size = 20) {
    const data = await request({ url: '/api/posts', data: { page, size }, header: authHeader() })
    return {
      ...data,
      list: (data.list || []).map(normalizePost),
    }
  },
  async getPostDetail(id) {
    return normalizePost(await request({ url: `/api/posts/${id}`, header: authHeader() }))
  },
  async createPost(payload) {
    return request({ url: '/api/posts', method: 'POST', data: payload, header: authHeader() })
  },
  async togglePostLike(id) {
    return request({ url: `/api/posts/${id}/like`, method: 'POST', header: authHeader() })
  },
  async createComment(payload) {
    return request({ url: '/api/comments', method: 'POST', data: payload, header: authHeader() })
  },
  async toggleCommentLike(id) {
    return request({ url: `/api/comments/${id}/like`, method: 'POST', header: authHeader() })
  },
  async createReport(payload) {
    return request({ url: '/api/reports', method: 'POST', data: payload, header: authHeader() })
  },
  async login(code) {
    const result = await request({ url: '/api/login', method: 'POST', data: { code } })
    return {
      ...result,
      user: normalizeUser(result.user),
    }
  },
  async getUserInfo() {
    return normalizeUser(await request({ url: '/api/user/info', header: authHeader() }))
  },
  async updateUserInfo(payload) {
    return normalizeUser(await request({ url: '/api/user/info', method: 'PUT', data: payload, header: authHeader() }))
  },
  async getFavoriteBoards() {
    const data = await request({ url: '/api/user/favorites', header: authHeader() })
    return {
      boardIds: data.boardIds || [],
      boards: (data.boards || []).map(normalizeBoard),
    }
  },
  async addFavoriteBoard(boardId) {
    const data = await request({ url: `/api/user/favorites/${boardId}`, method: 'POST', header: authHeader() })
    return {
      boardIds: data.boardIds || [],
      boards: (data.boards || []).map(normalizeBoard),
    }
  },
  async removeFavoriteBoard(boardId) {
    const data = await request({ url: `/api/user/favorites/${boardId}`, method: 'DELETE', header: authHeader() })
    return {
      boardIds: data.boardIds || [],
      boards: (data.boards || []).map(normalizeBoard),
    }
  },
  async getUserSessions() {
    return (await request({ url: '/api/user/sessions', header: authHeader() })).map(normalizeNoteSession)
  },
  async getUserSessionDetail(sessionId) {
    return normalizeNoteSession(await request({ url: `/api/user/sessions/${sessionId}`, header: authHeader() }))
  },
  async saveUserSession(session) {
    return normalizeNoteSession(
      await request({
        url: `/api/user/sessions/${session.sessionId}`,
        method: 'PUT',
        data: session,
        header: authHeader(),
      }),
    )
  },
  async deleteUserSession(sessionId) {
    return request({ url: `/api/user/sessions/${sessionId}`, method: 'DELETE', header: authHeader() })
  },
  async uploadImage(filePath) {
    const result = await uploadFile(filePath, storage.getAuthToken())
    return { url: withBaseUrl(result.url) }
  },
}
