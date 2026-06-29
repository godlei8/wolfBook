import { BASE_URL } from './config'
import { createAuthHeader, request, uploadFile } from './request'
import storage from './storage'

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

function normalizeJudgePlayer(player) {
  if (!player) return player
  return {
    ...player,
    avatar: withBaseUrl(player.avatar),
  }
}

function normalizeJudgeRoomSummary(room) {
  if (!room) return room
  return {
    ...room,
    judgeSupportLevel: room.judgeSupportLevel || 'manual_only',
  }
}

function normalizeJudgeRoomSnapshot(snapshot) {
  if (!snapshot) return snapshot
  return {
    ...snapshot,
    judgeSupportLevel: snapshot.judgeSupportLevel || 'manual_only',
    selfPlayer: normalizeJudgePlayer(snapshot.selfPlayer),
    players: (snapshot.players || []).map(normalizeJudgePlayer),
    timeline: snapshot.timeline || [],
    voteTallies: snapshot.voteTallies || [],
    nightActions: snapshot.nightActions || [],
    pendingNightAction: snapshot.pendingNightAction || { submitted: false, targetSeatNo: null, note: '' },
  }
}

export default {
  async getBoards(params = {}) {
    const data = await request({ url: '/api/boards', data: params, header: createAuthHeader() })
    return {
      ...data,
      list: (data.list || []).map(normalizeBoard),
    }
  },
  async getBoardDetail(id) {
    return normalizeBoard(await request({ url: `/api/boards/${id}`, header: createAuthHeader() }))
  },
  async getRoles(camp = '') {
    return (await request({ url: '/api/roles', data: { camp }, header: createAuthHeader() })).map(normalizeRole)
  },
  async getRoleDetail(id) {
    return normalizeRole(await request({ url: `/api/roles/${id}`, header: createAuthHeader() }))
  },
  async getPosts(page = 1, size = 20) {
    const data = await request({ url: '/api/posts', data: { page, size }, header: createAuthHeader() })
    return {
      ...data,
      list: (data.list || []).map(normalizePost),
    }
  },
  async getPostDetail(id) {
    return normalizePost(await request({ url: `/api/posts/${id}`, header: createAuthHeader() }))
  },
  async createPost(payload) {
    return request({ url: '/api/posts', method: 'POST', data: payload, header: createAuthHeader() })
  },
  async togglePostLike(id) {
    return request({ url: `/api/posts/${id}/like`, method: 'POST', header: createAuthHeader() })
  },
  async createComment(payload) {
    return request({ url: '/api/comments', method: 'POST', data: payload, header: createAuthHeader() })
  },
  async toggleCommentLike(id) {
    return request({ url: `/api/comments/${id}/like`, method: 'POST', header: createAuthHeader() })
  },
  async createReport(payload) {
    return request({ url: '/api/reports', method: 'POST', data: payload, header: createAuthHeader() })
  },
  async login(code) {
    const result = await request({ url: '/api/login', method: 'POST', data: { code } })
    return {
      ...result,
      user: normalizeUser(result.user),
    }
  },
  async getUserInfo() {
    return normalizeUser(await request({ url: '/api/user/info', header: createAuthHeader() }))
  },
  async updateUserInfo(payload) {
    return normalizeUser(await request({ url: '/api/user/info', method: 'PUT', data: payload, header: createAuthHeader() }))
  },
  async getFavoriteBoards() {
    const data = await request({ url: '/api/user/favorites', header: createAuthHeader() })
    return {
      boardIds: data.boardIds || [],
      boards: (data.boards || []).map(normalizeBoard),
    }
  },
  async addFavoriteBoard(boardId) {
    const data = await request({ url: `/api/user/favorites/${boardId}`, method: 'POST', header: createAuthHeader() })
    return {
      boardIds: data.boardIds || [],
      boards: (data.boards || []).map(normalizeBoard),
    }
  },
  async removeFavoriteBoard(boardId) {
    const data = await request({ url: `/api/user/favorites/${boardId}`, method: 'DELETE', header: createAuthHeader() })
    return {
      boardIds: data.boardIds || [],
      boards: (data.boards || []).map(normalizeBoard),
    }
  },
  async getUserSessions() {
    return (await request({ url: '/api/user/sessions', header: createAuthHeader() })).map(normalizeNoteSession)
  },
  async getUserSessionDetail(sessionId) {
    return normalizeNoteSession(await request({ url: `/api/user/sessions/${sessionId}`, header: createAuthHeader() }))
  },
  async saveUserSession(session) {
    return normalizeNoteSession(
      await request({
        url: `/api/user/sessions/${session.sessionId}`,
        method: 'PUT',
        data: session,
        header: createAuthHeader(),
      }),
    )
  },
  async deleteUserSession(sessionId) {
    return request({ url: `/api/user/sessions/${sessionId}`, method: 'DELETE', header: createAuthHeader() })
  },
  async getJudgeRecentRooms() {
    return (await request({ url: '/api/judge/rooms/recent', header: createAuthHeader() })).map(normalizeJudgeRoomSummary)
  },
  async createJudgeRoom(payload) {
    return normalizeJudgeRoomSnapshot(
      await request({ url: '/api/judge/rooms', method: 'POST', data: payload, header: createAuthHeader() }),
    )
  },
  async getJudgeRoom(roomId) {
    return normalizeJudgeRoomSnapshot(await request({ url: `/api/judge/rooms/${roomId}`, header: createAuthHeader() }))
  },
  async joinJudgeRoom(roomId) {
    return normalizeJudgeRoomSnapshot(
      await request({ url: `/api/judge/rooms/${roomId}/join`, method: 'POST', header: createAuthHeader() }),
    )
  },
  async toggleJudgeReady(roomId) {
    return normalizeJudgeRoomSnapshot(
      await request({ url: `/api/judge/rooms/${roomId}/ready`, method: 'POST', header: createAuthHeader() }),
    )
  },
  async startJudgeRoom(roomId) {
    return normalizeJudgeRoomSnapshot(
      await request({ url: `/api/judge/rooms/${roomId}/start`, method: 'POST', header: createAuthHeader() }),
    )
  },
  async advanceJudgeRoom(roomId, payload) {
    return normalizeJudgeRoomSnapshot(
      await request({ url: `/api/judge/rooms/${roomId}/advance`, method: 'POST', data: payload, header: createAuthHeader() }),
    )
  },
  async submitJudgeNightAction(roomId, payload) {
    return normalizeJudgeRoomSnapshot(
      await request({
        url: `/api/judge/rooms/${roomId}/night-action`,
        method: 'POST',
        data: payload,
        header: createAuthHeader(),
      }),
    )
  },
  async submitJudgeVote(roomId, payload) {
    return normalizeJudgeRoomSnapshot(
      await request({ url: `/api/judge/rooms/${roomId}/vote`, method: 'POST', data: payload, header: createAuthHeader() }),
    )
  },
  async uploadImage(filePath) {
    const result = await uploadFile(filePath, storage.getAuthToken())
    return { url: withBaseUrl(result.url) }
  },
}
