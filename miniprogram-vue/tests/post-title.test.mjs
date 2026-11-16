import test from 'node:test'
import assert from 'node:assert/strict'
import { postTitle } from '../src/utils/post.js'

// 标题是选填的：早期数据里没填的存成了「未命名帖子」，或者直接存了正文开头；这两种都不显示标题（和后台同一个口径）
test('onlyAPostsOwnTitleIsShown', () => {
  assert.equal(postTitle({ title: '预女猎白复盘：警徽流怎么留', content: '昨晚 12 人预女猎白……' }), '预女猎白复盘：警徽流怎么留')
  assert.equal(postTitle({ title: '未命名帖子', content: '测试一下' }), '')
  assert.equal(postTitle({ title: '昨晚的狼美人骑士局太精彩了，最后', content: '昨晚的狼美人骑士局太精彩了，最后一轮悍跳成功' }), '')
  assert.equal(postTitle({ title: '', content: '你好' }), '')
  assert.equal(postTitle({ content: '你好' }), '')
  assert.equal(postTitle(null), '')
})
