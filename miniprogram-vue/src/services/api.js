import { BASE_URL } from './config'
import { request, uploadFile } from './request'
import storage from './storage'
import { normalizeSession as normalizeNoteSessionV2, normalizeRecord as normalizeNoteRecordV2 } from '../utils/session/normalizer'

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
  if (!comment) return comment
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
  return normalizeNoteRecordV2(record)
}

function normalizeNoteSession(session) {
  return normalizeNoteSessionV2({
    ...session,
    records: (session?.records || []).map(normalizeNoteRecord),
  })
}

function safeCommunityText(value) {
  return typeof value === 'string' ? value.trim() : ''
}

function buildCommunityExcerpt(value, maxLength = 120) {
  const normalized = safeCommunityText(value).replace(/\s+/g, ' ')
  if (!normalized) return ''
  return normalized.length > maxLength ? normalized.slice(0, maxLength) : normalized
}

function isPlaceholderPostTitle(title) {
  const normalized = safeCommunityText(title)
  return !normalized || normalized.toLowerCase() === 'untitled post'
}

function normalizePost(post) {
  if (!post) return post
  const content = safeCommunityText(post.content)
  const summary = buildCommunityExcerpt(post.summary, 120) || buildCommunityExcerpt(content, 120)
  const title = isPlaceholderPostTitle(post.title)
    ? buildCommunityExcerpt(summary || content, 40) || '未命名帖子'
    : safeCommunityText(post.title)
  return {
    ...post,
    title,
    summary,
    content,
    avatar: withBaseUrl(post.avatar),
    images: (post.images || []).map(withBaseUrl),
    board: post.board
      ? {
          ...post.board,
          coverImage: withBaseUrl(post.board.coverImage),
        }
      : null,
    comments: (post.comments || []).map(normalizeComment),
    relatedPosts: (post.relatedPosts || []).map((item) => normalizePost({ ...item, comments: [] })),
  }
}

function normalizeCommunityFeed(data) {
  if (!data) {
    return {
      posts: { list: [], total: 0, page: 1, size: 10 },
      hotBoards: [],
      suggestedTags: [],
    }
  }
  const posts = data.posts || { list: [], total: 0, page: 1, size: 10 }
  return {
    ...data,
    posts: {
      ...posts,
      list: (posts.list || []).map((item) => normalizePost({ ...item, comments: [] })),
    },
    hotBoards: (data.hotBoards || []).map((item) => ({
      ...item,
      coverImage: withBaseUrl(item.coverImage),
    })),
    suggestedTags: data.suggestedTags || [],
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
  async getPosts(page = 1, size = 20, options = {}) {
    const params = {
      page,
      size,
      tab: options.tab || 'recommend',
      type: options.type || '',
      q: options.q || '',
      sort: options.sort || 'hot',
    }
    if (options.boardId) {
      params.boardId = options.boardId
    }
    const data = await request({
      url: '/api/posts',
      data: params,
      header: authHeader(),
    })
    return normalizeCommunityFeed(data)
  },
  async searchPosts(q, options = {}) {
    const params = {
      q,
      type: options.type || '',
      sort: options.sort || 'hot',
      page: options.page || 1,
      size: options.size || 10,
    }
    if (options.boardId) {
      params.boardId = options.boardId
    }
    const data = await request({
      url: '/api/posts/search',
      data: params,
      header: authHeader(),
    })
    return normalizeCommunityFeed(data)
  },
  async getPostDetail(id) {
    return normalizePost(await request({ url: `/api/posts/${id}`, header: authHeader() }))
  },
  async createPost(payload) {
    return request({ url: '/api/posts', method: 'POST', data: payload, header: authHeader() })
  },
  async deletePost(id) {
    return request({ url: `/api/posts/${id}`, method: 'DELETE', header: authHeader() })
  },
  async togglePostLike(id) {
    return request({ url: `/api/posts/${id}/like`, method: 'POST', header: authHeader() })
  },
  async favoritePost(id) {
    return request({ url: `/api/posts/${id}/favorite`, method: 'POST', header: authHeader() })
  },
  async unfavoritePost(id) {
    return request({ url: `/api/posts/${id}/favorite`, method: 'DELETE', header: authHeader() })
  },
  async createComment(payload) {
    return request({ url: '/api/comments', method: 'POST', data: payload, header: authHeader() })
  },
  async deleteComment(id) {
    return request({ url: `/api/comments/${id}`, method: 'DELETE', header: authHeader() })
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
