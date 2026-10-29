<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from './ui/PageHeader.vue'
import { api } from '../services/api'
import type { CommentView, PostSummary, ReportItem } from '../types'
import { formatTime } from '../utils/board'
import { clampPage, paginate } from '../utils/pagination'
import squareScene from '../assets/scenes/square.webp'

const emit = defineEmits<{ changed: [] }>()

const PAGE_SIZE = 12
const CATEGORIES = ['战报', '教学', '讨论']

const tab = ref<'reports' | 'posts' | 'comments'>('reports')
const loading = ref(false)
const busy = ref(false)
const posts = ref<PostSummary[]>([])
const comments = ref<CommentView[]>([])
const reports = ref<ReportItem[]>([])
const keyword = ref('')
const postStatus = ref<'ALL' | 'online' | 'offline'>('ALL')
const postCategory = ref('ALL')
const page = ref(1)

const postsById = computed(() => new Map(posts.value.map((post) => [post.id, post])))
const commentsById = computed(() => new Map(comments.value.map((comment) => [comment.id, comment])))
const openReports = computed(() => reports.value.filter((report) => report.processStatus === 'OPEN'))
const closedReports = computed(() => reports.value.filter((report) => report.processStatus !== 'OPEN'))

const filteredPosts = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  return posts.value.filter((post) => {
    if (postStatus.value === 'online' && post.status !== 1) return false
    if (postStatus.value === 'offline' && post.status === 1) return false
    if (postCategory.value !== 'ALL' && (post.category || '讨论') !== postCategory.value) return false
    if (text && ![post.title, post.content, post.nickname, post.boardName].join(' ').toLowerCase().includes(text)) return false
    return true
  })
})

const filteredComments = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  return comments.value.filter((comment) => !text || [comment.content, comment.nickname].join(' ').toLowerCase().includes(text))
})

const pagedPosts = computed(() => paginate(filteredPosts.value, page.value, PAGE_SIZE))
const pagedComments = computed(() => paginate(filteredComments.value, page.value, PAGE_SIZE))
const total = computed(() => (tab.value === 'posts' ? filteredPosts.value.length : filteredComments.value.length))
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))

watch([tab, keyword, postStatus, postCategory], () => {
  page.value = 1
})

watch(total, (count) => {
  page.value = clampPage(page.value, PAGE_SIZE, count)
})

/** 帖子有自己的标题才显示；早期数据的标题就是正文开头，或者是「未命名帖子」，不重复显示。 */
function ownTitle(post?: PostSummary | null) {
  const title = post?.title?.trim() || ''
  return title && title !== '未命名帖子' && !post?.content.startsWith(title) ? title : ''
}

function postTitle(post?: PostSummary | null) {
  if (!post) return ''
  return ownTitle(post) || post.content.slice(0, 24) || '未命名帖子'
}

/** 举报对象：帖子或评论，可能已经被删掉了。 */
function reportTarget(report: ReportItem) {
  if (report.targetType === 'comment') {
    const comment = commentsById.value.get(report.targetId)
    return { kind: '评论', exists: Boolean(comment), title: comment ? `评论 #${comment.id}` : '', text: comment?.content || '', post: comment ? postsById.value.get(comment.postId) : undefined, comment }
  }
  const post = postsById.value.get(report.targetId)
  return { kind: '帖子', exists: Boolean(post), title: ownTitle(post), text: post?.content || '', post, comment: undefined }
}

async function load() {
  loading.value = true
  try {
    const [postData, commentData, reportData] = await Promise.all([api.getPosts(), api.getComments(), api.getReports()])
    posts.value = postData.list
    comments.value = commentData.list
    reports.value = reportData.list
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '社区数据加载失败')
  } finally {
    loading.value = false
  }
}

async function run(action: () => Promise<unknown>, success: string) {
  busy.value = true
  try {
    await action()
    ElMessage.success(success)
    await load()
    emit('changed')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '操作失败')
  } finally {
    busy.value = false
  }
}

async function takeDown(report: ReportItem) {
  const target = reportTarget(report)
  if (target.comment) {
    try {
      await ElMessageBox.confirm('评论没有下线状态，会直接删除这条评论。', '删除被举报的评论', {
        type: 'warning',
        confirmButtonText: '删除并结案',
        cancelButtonText: '取消',
      })
    } catch {
      return
    }
    const commentId = target.comment.id
    await run(async () => {
      await api.deleteComment(commentId)
      await api.processReport(report.id, 'RESOLVED')
    }, '评论已删除，举报已结案')
    return
  }
  await run(async () => {
    if (target.post && target.post.status === 1) await api.updatePostStatus(target.post.id, 'OFFLINE')
    await api.processReport(report.id, 'RESOLVED')
  }, '帖子已下线，举报已结案')
}

function dismiss(report: ReportItem) {
  return run(() => api.processReport(report.id, 'DISMISSED'), '已驳回举报')
}

function togglePost(post: PostSummary) {
  const online = post.status !== 1
  return run(() => api.updatePostStatus(post.id, online ? 'PUBLISHED' : 'OFFLINE'), online ? '帖子已恢复' : '帖子已下线')
}

async function removePost(post: PostSummary) {
  try {
    await ElMessageBox.confirm('会同时删除这条帖子的全部评论和点赞，不能恢复。只想先藏起来请用下线。', '删除帖子', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  await run(() => api.deletePost(post.id), '帖子已删除')
}

async function removeComment(comment: CommentView) {
  try {
    await ElMessageBox.confirm('删除后不能恢复。', '删除评论', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  await run(() => api.deleteComment(comment.id), '评论已删除')
}

onMounted(load)
</script>

<template>
  <PageHeader eyebrow="COMMUNITY" title="社区治理" description="处理举报，下线或恢复帖子，删除不合适的评论。" :banner="squareScene" />

  <div class="tabs" role="tablist" aria-label="内容类型">
    <button type="button" role="tab" class="tab" :class="{ on: tab === 'reports' }" :aria-selected="tab === 'reports'" @click="tab = 'reports'">
      举报<span class="count" :class="{ 'count--hot': openReports.length }">{{ openReports.length }}</span>
    </button>
    <button type="button" role="tab" class="tab" :class="{ on: tab === 'posts' }" :aria-selected="tab === 'posts'" @click="tab = 'posts'">
      帖子<span class="count">{{ posts.length }}</span>
    </button>
    <button type="button" role="tab" class="tab" :class="{ on: tab === 'comments' }" :aria-selected="tab === 'comments'" @click="tab = 'comments'">
      评论<span class="count">{{ comments.length }}</span>
    </button>
  </div>

  <p v-if="loading && !reports.length && !posts.length" class="nt-empty">正在巡视社区…</p>

  <!-- 举报 -->
  <template v-else-if="tab === 'reports'">
    <p v-if="!openReports.length" class="nt-empty">没有待处理的举报</p>
    <article v-for="report in openReports" :key="report.id" class="report">
      <div class="report__main">
        <div class="report__meta">
          <span class="nt-badge nt-badge--danger">待处理</span>
          <span>举报{{ reportTarget(report).kind }} #{{ report.targetId }} · {{ formatTime(report.createTime) }}</span>
        </div>
        <div class="report__reason">
          <small>举报理由</small>
          <span>{{ report.reason || '没写理由' }}</span>
        </div>
        <div v-if="reportTarget(report).exists" class="report__target">
          <div class="report__target-head">
            <span v-if="reportTarget(report).post" class="nt-chip report__cat">{{ reportTarget(report).post?.category || '讨论' }}</span>
            <strong v-if="reportTarget(report).title">{{ reportTarget(report).title }}</strong>
            <span v-if="reportTarget(report).post?.status === 0" class="nt-badge nt-badge--off">已下线</span>
            <span v-if="reportTarget(report).post" class="report__stat">赞 {{ reportTarget(report).post?.likeCount }} · 评论 {{ reportTarget(report).post?.commentCount }}</span>
          </div>
          <p>{{ reportTarget(report).text }}</p>
        </div>
        <p v-else class="nt-muted">被举报的内容已经删掉了，可以直接结案。</p>
      </div>
      <div class="report__actions">
        <button v-if="reportTarget(report).exists" class="nt-btn nt-btn--gold" type="button" :disabled="busy" @click="takeDown(report)">
          {{ reportTarget(report).comment ? '删除评论并结案' : '下线帖子并结案' }}
        </button>
        <button v-else class="nt-btn nt-btn--gold" type="button" :disabled="busy" @click="run(() => api.processReport(report.id, 'RESOLVED'), '举报已结案')">结案</button>
        <button class="nt-btn nt-btn--ghost" type="button" :disabled="busy" @click="dismiss(report)">内容没问题，驳回</button>
      </div>
    </article>

    <section v-if="closedReports.length" class="nt-panel">
      <h2 class="nt-h2">处理过的举报</h2>
      <div v-for="report in closedReports" :key="report.id" class="closed">
        <span class="nt-badge" :class="report.processStatus === 'RESOLVED' ? 'nt-badge--on' : 'nt-badge--off'">{{ report.processStatus === 'RESOLVED' ? '已处理' : '已驳回' }}</span>
        <span class="closed__text">{{ reportTarget(report).kind }} #{{ report.targetId }} · {{ report.reason }}</span>
        <span class="closed__by">{{ report.processBy || '' }} {{ formatTime(report.processTime) }}</span>
      </div>
    </section>
  </template>

  <!-- 帖子 / 评论 -->
  <template v-else>
    <div class="filters">
      <template v-if="tab === 'posts'">
        <button type="button" class="nt-chip" :class="{ 'nt-chip--on': postStatus === 'ALL' }" @click="postStatus = 'ALL'">全部 {{ posts.length }}</button>
        <button type="button" class="nt-chip" :class="{ 'nt-chip--on': postStatus === 'online' }" @click="postStatus = 'online'">显示中 {{ posts.filter((post) => post.status === 1).length }}</button>
        <button type="button" class="nt-chip" :class="{ 'nt-chip--on': postStatus === 'offline' }" @click="postStatus = 'offline'">已下线 {{ posts.filter((post) => post.status !== 1).length }}</button>
        <span class="nt-divider"></span>
        <button v-for="category in ['ALL', ...CATEGORIES]" :key="category" type="button" class="nt-chip" :class="{ 'nt-chip--on': postCategory === category }" @click="postCategory = category">{{ category === 'ALL' ? '全部分类' : category }}</button>
      </template>
      <label class="search">
        <span class="nt-sr-only">搜索</span>
        <input v-model="keyword" type="search" class="nt-input" :placeholder="tab === 'posts' ? '搜标题、内容、作者、板子' : '搜评论内容、作者'" />
      </label>
    </div>

    <div class="table-wrap">
      <table v-if="tab === 'posts'">
        <thead>
          <tr><th>状态</th><th>内容</th><th>分类 / 关联板子</th><th>作者</th><th>互动</th><th>发布时间</th><th class="right">操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="post in pagedPosts" :key="post.id" :class="{ off: post.status !== 1 }">
            <td><span class="nt-badge" :class="post.status === 1 ? 'nt-badge--on' : 'nt-badge--off'">{{ post.status === 1 ? '显示中' : '已下线' }}</span></td>
            <td class="content">
              <strong v-if="ownTitle(post)">{{ ownTitle(post) }}</strong>
              <span>{{ post.content }}</span>
            </td>
            <td class="soft">{{ post.category || '讨论' }} · <span class="dim">{{ post.boardName || '未关联' }}</span></td>
            <td class="soft">{{ post.nickname || '玩家' }}</td>
            <td class="soft nowrap">赞 {{ post.likeCount }} · 评 {{ post.commentCount }}</td>
            <td class="dim nowrap">{{ formatTime(post.createTime) }}</td>
            <td class="right nowrap"><div class="ops">
              <button class="nt-btn nt-btn--ghost nt-btn--small" type="button" :disabled="busy" @click="togglePost(post)">{{ post.status === 1 ? '下线' : '恢复' }}</button>
              <button class="nt-iconbtn del" type="button" :aria-label="`删除帖子 ${post.id}`" title="删除" :disabled="busy" @click="removePost(post)">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" /></svg>
              </button>
            </div></td>
          </tr>
        </tbody>
      </table>

      <table v-else>
        <thead>
          <tr><th>评论</th><th>所在帖子</th><th>作者</th><th>点赞</th><th>时间</th><th class="right">操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="comment in pagedComments" :key="comment.id">
            <td class="content"><span>{{ comment.content }}</span></td>
            <td class="soft">{{ postTitle(postsById.get(comment.postId)) || `#${comment.postId}` }}</td>
            <td class="soft">{{ comment.nickname || '玩家' }}</td>
            <td class="soft">{{ comment.likeCount }}</td>
            <td class="dim nowrap">{{ formatTime(comment.createTime) }}</td>
            <td class="right">
              <button class="nt-iconbtn del" type="button" :aria-label="`删除评论 ${comment.id}`" title="删除" :disabled="busy" @click="removeComment(comment)">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" /></svg>
              </button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-if="!total" class="nt-empty table-empty">没有符合条件的内容</p>
    </div>

    <footer v-if="pageCount > 1" class="pager">
      <span>共 {{ total }} 条</span>
      <div class="pager__btns">
        <button v-for="n in pageCount" :key="n" type="button" class="nt-chip" :class="{ 'nt-chip--on': n === page }" @click="page = n">{{ n }}</button>
      </div>
    </footer>
  </template>
</template>

<style scoped>
.tabs {
  display: flex;
  gap: 28px;
  border-bottom: 1px solid var(--panel-line);
}

.tab {
  height: 48px;
  padding: 0 6px;
  border: 0;
  border-bottom: 3px solid transparent;
  background: transparent;
  color: var(--text-2);
  font: 700 16px var(--sans);
  display: inline-flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}

.tab.on {
  color: var(--text);
  border-bottom-color: var(--gold);
}

.count {
  min-width: 22px;
  height: 22px;
  padding: 0 6px;
  border-radius: 11px;
  background: var(--panel-raised);
  color: var(--text-2);
  font-size: 12px;
  font-weight: 900;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.count--hot {
  background: var(--camp-wolf);
  color: var(--ink);
}

.report {
  padding: 22px;
  border-radius: 22px;
  background: var(--panel);
  border: 2px solid var(--ink);
  box-shadow: 5px 5px 0 var(--ink);
  display: flex;
  flex-wrap: wrap;
  gap: 22px;
}

.report__main {
  flex: 3 1 420px;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.report__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  font-size: 14px;
  color: var(--text-2);
}

.report__reason {
  padding: 16px 18px;
  border-radius: 16px;
  background: var(--bg);
  border: 1px solid var(--panel-line);
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 16px;
  line-height: 1.7;
}

.report__reason small {
  font-size: 13px;
  color: var(--text-3);
}

.report__target {
  padding: 16px 18px;
  border-radius: 16px;
  background: var(--panel-raised);
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.report__target-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.report__target-head strong {
  font: 900 16px var(--serif);
}

.report__cat {
  height: 24px;
  padding: 0 10px;
  font-size: 12px;
  cursor: default;
}

.report__stat {
  margin-left: auto;
  font-size: 13px;
  color: var(--text-3);
}

.report__target p {
  margin: 0;
  font-size: 15px;
  line-height: 1.75;
  color: var(--text-soft);
  white-space: pre-wrap;
}

.report__actions {
  flex: 1 0 220px;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  gap: 12px;
}

.closed {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid var(--panel-line);
  font-size: 14px;
}

.closed:last-child {
  border-bottom: 0;
}

.closed__text {
  flex: 1;
  min-width: 0;
  color: var(--text-soft);
}

.closed__by {
  color: var(--text-3);
  font-size: 13px;
}

.filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.search {
  margin-left: auto;
  width: 260px;
}

.search .nt-input {
  height: 36px;
  border-radius: 999px;
}

.table-wrap {
  overflow-x: auto;
  border-radius: 18px;
  border: 1px solid var(--panel-line);
  background: var(--panel);
}

table {
  width: 100%;
  min-width: 820px;
  border-collapse: collapse;
  font-size: 14px;
}

th {
  padding: 12px 16px;
  background: var(--bar-bg);
  color: var(--text-3);
  font-weight: 700;
  text-align: left;
}

td {
  padding: 14px 16px;
  border-top: 1px solid var(--panel-line);
  vertical-align: middle;
}

tr.off td {
  opacity: 0.7;
}

.content {
  max-width: 380px;
}

.content strong {
  display: block;
  margin-bottom: 4px;
  font-family: var(--serif);
}

.content span {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  line-height: 1.6;
}

.soft {
  color: var(--text-2);
}

.dim {
  color: var(--text-3);
}

.nowrap {
  white-space: nowrap;
}

.right {
  text-align: right;
}

.ops {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
}

.del {
  width: 36px;
  height: 36px;
  border-radius: 12px;
}

.table-empty {
  margin: 16px;
}

.pager {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: var(--text-3);
  font-size: 14px;
}

.pager__btns {
  display: flex;
  gap: 8px;
}
</style>
