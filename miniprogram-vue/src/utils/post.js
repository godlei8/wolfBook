// 社区帖子的展示（纯函数，有单测 tests/post-title.test.mjs）。

/**
 * 帖子自己的标题。标题是选填的：早期数据里没填的存成了「未命名帖子」，或者直接存了正文开头，
 * 这两种都不算标题、不显示（和后台 CommunityManagement 同一个口径）。
 */
export function postTitle(post) {
  const title = String(post?.title || '').trim()
  const content = String(post?.content || '')
  if (!title || title === '未命名帖子' || content.startsWith(title)) return ''
  return title
}
