<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import AdminInfoStack from './shared/AdminInfoStack.vue'
import { usePagedItems, useResetPageOnChange } from '../composables/pagination'
import { FILTER_ALL, PAGE_SIZES_COMPACT, PAGE_SIZES_STANDARD } from '../constants/filters'
import { api } from '../services/api'
import { resolveErrorMessage } from '../utils/errors'
import type { CommentView, PostSummary, ReportItem } from '../types'

const emit = defineEmits<{ changed: [] }>()

const activeTab = ref('posts')
const posts = ref<PostSummary[]>([])
const comments = ref<CommentView[]>([])
const reports = ref<ReportItem[]>([])
const loading = ref(false)

const postKeyword = ref('')
const postStatusFilter = ref<string | typeof FILTER_ALL>(FILTER_ALL)
const commentKeyword = ref('')
const reportKeyword = ref('')
const reportStatusFilter = ref<string | typeof FILTER_ALL>(FILTER_ALL)

const postPage = ref(1)
const postPageSize = ref(8)
const commentPage = ref(1)
const commentPageSize = ref(10)
const reportPage = ref(1)
const reportPageSize = ref(8)

const typeLabelMap: Record<string, string> = {
  general: '分享',
  review: '复盘',
  board_discussion: '板型',
  qa: '问答',
  strategy: '战术',
  help: '新手',
  recruit: '组局',
}

const postStatusLabelMap: Record<string, string> = {
  PUBLISHED: '已发布',
  OFFLINE: '已下线',
  PENDING_REVIEW: '待审核',
  REJECTED: '已拒绝',
}

const reportStatusLabelMap: Record<string, string> = {
  OPEN: '待处理',
  RESOLVED: '已处理',
  DISMISSED: '已驳回',
}

const postOverviewItems = computed(() => {
  const total = filteredPosts.value.length
  const publishedCount = filteredPosts.value.filter((post) => post.status === 'PUBLISHED').length
  const operatedCount = filteredPosts.value.filter((post) => post.featured || post.pinned).length
  const averageHot = total
    ? Math.round(filteredPosts.value.reduce((sum, post) => sum + (post.hotScore || 0), 0) / total)
    : 0
  return [
    { label: '当前范围', value: total, hint: '筛选后的帖子数' },
    { label: '在线内容', value: publishedCount, hint: '当前已发布' },
    { label: '运营内容', value: operatedCount, hint: '已有精选或置顶' },
    { label: '平均热度', value: averageHot, hint: '当前内容表现' },
  ]
})

const filteredPosts = computed(() =>
  posts.value.filter((post) => {
    const normalizedKeyword = postKeyword.value.trim().toLowerCase()
    const searchableText = [
      post.title,
      post.summary,
      post.content,
      post.nickname,
      post.openid,
      post.boardName,
      ...(post.tagList || []),
    ]
      .filter(Boolean)
      .join(' ')
      .toLowerCase()
    const matchesKeyword = !normalizedKeyword || searchableText.includes(normalizedKeyword)
    const matchesStatus = postStatusFilter.value === FILTER_ALL || post.status === postStatusFilter.value
    return matchesKeyword && matchesStatus
  }),
)

const filteredComments = computed(() =>
  comments.value.filter((comment) => {
    const normalizedKeyword = commentKeyword.value.trim().toLowerCase()
    const searchableText = [
      comment.content,
      comment.nickname,
      comment.openid,
      comment.replyToNickname,
      String(comment.postId),
    ]
      .filter(Boolean)
      .join(' ')
      .toLowerCase()
    return !normalizedKeyword || searchableText.includes(normalizedKeyword)
  }),
)

const filteredReports = computed(() =>
  reports.value.filter((report) => {
    const normalizedKeyword = reportKeyword.value.trim().toLowerCase()
    const searchableText = [report.reason, report.openid, report.targetType, String(report.targetId)]
      .filter(Boolean)
      .join(' ')
      .toLowerCase()
    const matchesKeyword = !normalizedKeyword || searchableText.includes(normalizedKeyword)
    const matchesStatus = reportStatusFilter.value === FILTER_ALL || report.processStatus === reportStatusFilter.value
    return matchesKeyword && matchesStatus
  }),
)

const pagedPosts = usePagedItems(filteredPosts, postPage, postPageSize)
const pagedComments = usePagedItems(filteredComments, commentPage, commentPageSize)
const pagedReports = usePagedItems(filteredReports, reportPage, reportPageSize)

useResetPageOnChange(postPage, [postKeyword, postStatusFilter, postPageSize])
useResetPageOnChange(commentPage, [commentKeyword, commentPageSize])
useResetPageOnChange(reportPage, [reportKeyword, reportStatusFilter, reportPageSize])

function resetPostFilters() {
  postKeyword.value = ''
  postStatusFilter.value = FILTER_ALL
  postPage.value = 1
}

function resetCommentFilters() {
  commentKeyword.value = ''
  commentPage.value = 1
}

function resetReportFilters() {
  reportKeyword.value = ''
  reportStatusFilter.value = FILTER_ALL
  reportPage.value = 1
}

async function load() {
  loading.value = true
  try {
    const [postData, commentData, reportData] = await Promise.all([
      api.getPosts(),
      api.getComments(),
      api.getReports(),
    ])
    posts.value = postData.list
    comments.value = commentData.list
    reports.value = reportData.list
  } catch (error) {
    ElMessage.error(resolveErrorMessage(error, '社区数据加载失败'))
  } finally {
    loading.value = false
  }
}

async function toggleStatus(post: PostSummary) {
  try {
    await api.updatePostStatus(post.id, post.status === 'PUBLISHED' ? 'OFFLINE' : 'PUBLISHED')
    ElMessage.success('帖子状态已更新')
    await load()
    emit('changed')
  } catch (error) {
    ElMessage.error(resolveErrorMessage(error, '状态更新失败'))
  }
}

async function toggleFeatured(post: PostSummary) {
  try {
    await api.updatePostFeatured(post.id, !post.featured)
    ElMessage.success(post.featured ? '已取消精选' : '已设为精选')
    await load()
    emit('changed')
  } catch (error) {
    ElMessage.error(resolveErrorMessage(error, '精选状态更新失败'))
  }
}

async function togglePinned(post: PostSummary) {
  try {
    await api.updatePostPinned(post.id, !post.pinned)
    ElMessage.success(post.pinned ? '已取消置顶' : '已设为置顶')
    await load()
    emit('changed')
  } catch (error) {
    ElMessage.error(resolveErrorMessage(error, '置顶状态更新失败'))
  }
}

async function removePost(id: number) {
  try {
    await api.deletePost(id)
    ElMessage.success('帖子已删除')
    await load()
    emit('changed')
  } catch (error) {
    ElMessage.error(resolveErrorMessage(error, '帖子删除失败'))
  }
}

async function removeComment(id: number) {
  try {
    await api.deleteComment(id)
    ElMessage.success('评论已删除')
    await load()
    emit('changed')
  } catch (error) {
    ElMessage.error(resolveErrorMessage(error, '评论删除失败'))
  }
}

async function processReport(id: number, status: string) {
  try {
    await api.processReport(id, status)
    ElMessage.success('举报已处理')
    await load()
    emit('changed')
  } catch (error) {
    ElMessage.error(resolveErrorMessage(error, '举报处理失败'))
  }
}

function formatDateTime(value: string | null | undefined) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  const hour = String(date.getHours()).padStart(2, '0')
  const minute = String(date.getMinutes()).padStart(2, '0')
  return `${year}-${month}-${day} ${hour}:${minute}`
}

function formatOpenid(openid: string) {
  if (!openid) return '匿名用户'
  return `用户 ${openid.slice(-6).toUpperCase()}`
}

function postTypeLabel(value: string) {
  return typeLabelMap[value] || value || '未知'
}

function postStatusLabel(value: string) {
  return postStatusLabelMap[value] || value || '未知状态'
}

function postStatusTagType(value: string) {
  if (value === 'PUBLISHED') return 'success'
  if (value === 'OFFLINE') return 'info'
  if (value === 'REJECTED') return 'danger'
  return 'warning'
}

function reportStatusLabel(value: string) {
  return reportStatusLabelMap[value] || value || '未知状态'
}

function reportStatusTagType(value: string) {
  if (value === 'OPEN') return 'danger'
  if (value === 'RESOLVED') return 'success'
  return 'info'
}

function interactionItems(post: PostSummary) {
  return [
    { label: '浏览', value: post.viewCount ?? 0 },
    { label: '点赞', value: post.likeCount ?? 0 },
    { label: '评论', value: post.commentCount ?? 0 },
    { label: '收藏', value: post.favoriteCount ?? 0 },
  ]
}

function postAuthorLabel(post: PostSummary) {
  return post.nickname || formatOpenid(post.openid)
}

onMounted(load)
</script>

<template>
  <el-card class="panel-card" v-loading="loading">
    <el-tabs v-model="activeTab" class="community-tabs">
      <el-tab-pane label="帖子治理" name="posts">
        <div class="toolbar">
          <el-input v-model="postKeyword" clearable placeholder="搜索标题、作者、板子、标签或内容" />
          <el-select v-model="postStatusFilter" class="filter-select admin-filter-select">
            <el-option label="全部状态" :value="FILTER_ALL" />
            <el-option label="已发布" value="PUBLISHED" />
            <el-option label="已下线" value="OFFLINE" />
            <el-option label="待审核" value="PENDING_REVIEW" />
            <el-option label="已拒绝" value="REJECTED" />
          </el-select>
          <el-button @click="resetPostFilters">重置</el-button>
          <div class="admin-toolbar-summary">当前 {{ filteredPosts.length }} 条</div>
        </div>

        <div class="post-overview-grid">
          <div v-for="item in postOverviewItems" :key="item.label" class="overview-chip">
            <div class="overview-chip__label">{{ item.label }}</div>
            <div class="overview-chip__value">{{ item.value }}</div>
            <div class="overview-chip__hint">{{ item.hint }}</div>
          </div>
        </div>

        <div v-if="pagedPosts.length" class="post-list">
          <article v-for="row in pagedPosts" :key="row.id" class="post-entry">
            <div class="post-entry__hero">
              <div class="post-entry__badges">
                <el-tag round effect="plain" class="type-tag">{{ postTypeLabel(row.postType) }}</el-tag>
                <el-tag round :type="postStatusTagType(row.status)">{{ postStatusLabel(row.status) }}</el-tag>
                <span class="entry-time">发布于 {{ formatDateTime(row.createTime) }}</span>
              </div>
              <div class="post-entry__score">
                <span class="score-cell">{{ Math.round(row.hotScore || 0) }}</span>
                <span class="score-caption">热度</span>
              </div>
            </div>

            <div class="post-entry__body">
              <div class="post-entry__content">
                <div class="post-title-cell">{{ row.title || '未命名帖子' }}</div>
                <div class="post-summary-block">{{ row.summary || row.content || '暂无正文摘要' }}</div>
                <div class="post-meta-grid">
                  <AdminInfoStack class="meta-pill" eyebrow="作者" :title="postAuthorLabel(row)" />
                  <AdminInfoStack class="meta-pill" eyebrow="板子" :title="row.boardName || '未关联板子'" />
                  <AdminInfoStack class="meta-pill" eyebrow="更新" :title="formatDateTime(row.updateTime)" />
                </div>
                <div v-if="row.tagList?.length" class="inline-tags">
                  <span v-for="tag in row.tagList.slice(0, 6)" :key="tag" class="inline-tag"># {{ tag }}</span>
                </div>
              </div>

              <aside class="post-side-panel">
                <section class="side-panel-block">
                  <div class="side-panel-title">互动概览</div>
                  <div class="metric-grid metric-grid--compact">
                    <div v-for="item in interactionItems(row)" :key="item.label" class="metric-chip">
                      <span class="metric-value">{{ item.value }}</span>
                      <span class="metric-label">{{ item.label }}</span>
                    </div>
                  </div>
                </section>

                <section class="side-panel-block">
                  <div class="side-panel-title">运营位</div>
                  <div class="flag-row flag-row--spacious">
                    <el-tag v-if="row.pinned" round type="warning">置顶</el-tag>
                    <el-tag v-if="row.featured" round type="danger">精选</el-tag>
                    <span v-if="!row.pinned && !row.featured" class="table-subcopy">当前未挂运营位</span>
                  </div>
                </section>

                <section class="side-panel-block">
                  <div class="side-panel-title">运营操作</div>
                  <div class="action-grid">
                    <el-button size="small" type="primary" plain @click="toggleStatus(row)">
                      {{ row.status === 'PUBLISHED' ? '下线内容' : '恢复发布' }}
                    </el-button>
                    <el-button size="small" plain @click="toggleFeatured(row)">
                      {{ row.featured ? '取消精选' : '设为精选' }}
                    </el-button>
                    <el-button size="small" plain @click="togglePinned(row)">
                      {{ row.pinned ? '取消置顶' : '设为置顶' }}
                    </el-button>
                    <el-button size="small" type="danger" plain @click="removePost(row.id)">删除帖子</el-button>
                  </div>
                </section>
              </aside>
            </div>
          </article>
        </div>

        <el-empty v-else description="暂无符合条件的帖子" class="posts-empty" />

        <div class="admin-table-footer">
          <div class="admin-table-total">共 {{ filteredPosts.length }} 条帖子</div>
          <el-pagination
            v-model:current-page="postPage"
            v-model:page-size="postPageSize"
            background
            size="small"
            layout="sizes, prev, pager, next"
            :pager-count="5"
            :page-sizes="PAGE_SIZES_COMPACT"
            :total="filteredPosts.length"
          />
        </div>
      </el-tab-pane>

      <el-tab-pane label="评论管理" name="comments">
        <div class="toolbar toolbar--compact">
          <el-input v-model="commentKeyword" clearable placeholder="搜索评论内容、评论人、回复对象或帖子 ID" />
          <el-button @click="resetCommentFilters">重置</el-button>
          <div class="admin-toolbar-summary">当前 {{ filteredComments.length }} 条</div>
        </div>

        <el-table
          :data="pagedComments"
          class="community-table"
          table-layout="auto"
          empty-text="暂无符合条件的评论"
        >
          <el-table-column label="评论人" min-width="150">
            <template #default="{ row }">
              <AdminInfoStack
                class="compact-user"
                :title="row.nickname || formatOpenid(row.openid)"
                :subtitle="formatOpenid(row.openid)"
              />
            </template>
          </el-table-column>
          <el-table-column prop="postId" label="帖子 ID" width="92" align="center" />
          <el-table-column label="内容" min-width="380">
            <template #default="{ row }">
              <div class="comment-cell">
                <div>{{ row.content }}</div>
                <div v-if="row.replyToNickname" class="table-subcopy">回复 {{ row.replyToNickname }}</div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="互动" width="100" align="center">
            <template #default="{ row }">
              <div class="score-cell">{{ row.likeCount }}</div>
              <div class="table-subcopy">点赞</div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="120" align="center">
            <template #default="{ row }">
              <el-tag round :type="row.status === 'VISIBLE' ? 'success' : 'info'">
                {{ row.status === 'VISIBLE' ? '可见' : '隐藏' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="时间" min-width="170">
            <template #default="{ row }">
              {{ formatDateTime(row.createTime) }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="112" fixed="right">
            <template #default="{ row }">
              <el-button size="small" type="danger" plain @click="removeComment(row.id)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="admin-table-footer">
          <div class="admin-table-total">共 {{ filteredComments.length }} 条评论</div>
          <el-pagination
            v-model:current-page="commentPage"
            v-model:page-size="commentPageSize"
            background
            size="small"
            layout="sizes, prev, pager, next"
            :pager-count="5"
            :page-sizes="PAGE_SIZES_STANDARD"
            :total="filteredComments.length"
          />
        </div>
      </el-tab-pane>

      <el-tab-pane label="举报处理" name="reports">
        <div class="toolbar">
          <el-input v-model="reportKeyword" clearable placeholder="搜索举报原因、举报人或目标 ID" />
          <el-select v-model="reportStatusFilter" class="filter-select admin-filter-select">
            <el-option label="全部状态" :value="FILTER_ALL" />
            <el-option label="待处理" value="OPEN" />
            <el-option label="已处理" value="RESOLVED" />
            <el-option label="已驳回" value="DISMISSED" />
          </el-select>
          <el-button @click="resetReportFilters">重置</el-button>
          <div class="admin-toolbar-summary">当前 {{ filteredReports.length }} 条</div>
        </div>

        <el-table
          :data="pagedReports"
          class="community-table"
          table-layout="auto"
          empty-text="暂无符合条件的举报"
        >
          <el-table-column label="目标" min-width="150">
            <template #default="{ row }">
              <AdminInfoStack class="compact-user" :title="row.targetType" :subtitle="`ID ${row.targetId}`" />
            </template>
          </el-table-column>
          <el-table-column label="举报人" min-width="180">
            <template #default="{ row }">
              {{ formatOpenid(row.openid) }}
            </template>
          </el-table-column>
          <el-table-column label="原因" min-width="260">
            <template #default="{ row }">
              <div class="report-reason-cell">{{ row.reason || '未填写原因' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="120" align="center">
            <template #default="{ row }">
              <el-tag round :type="reportStatusTagType(row.processStatus)">
                {{ reportStatusLabel(row.processStatus) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="处理信息" min-width="170">
            <template #default="{ row }">
              <AdminInfoStack
                class="compact-user"
                :title="row.processBy || '-'"
                :subtitle="formatDateTime(row.processTime)"
              />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="198" fixed="right">
            <template #default="{ row }">
              <div class="action-group action-group--report">
                <el-button size="small" type="primary" plain @click="processReport(row.id, 'RESOLVED')">
                  标记处理
                </el-button>
                <el-button size="small" plain @click="processReport(row.id, 'DISMISSED')">驳回</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>

        <div class="admin-table-footer">
          <div class="admin-table-total">共 {{ filteredReports.length }} 条举报</div>
          <el-pagination
            v-model:current-page="reportPage"
            v-model:page-size="reportPageSize"
            background
            size="small"
            layout="sizes, prev, pager, next"
            :pager-count="5"
            :page-sizes="PAGE_SIZES_COMPACT"
            :total="filteredReports.length"
          />
        </div>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<style scoped>
.panel-card {
  border-radius: 18px;
}

.toolbar {
  display: grid;
  grid-template-columns: minmax(280px, 1fr) 160px auto auto;
  gap: 12px;
  margin-bottom: 14px;
  align-items: center;
  padding: 12px;
  border: 1px solid rgba(255, 255, 255, 0.05);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.02);
}

.toolbar--compact {
  grid-template-columns: minmax(280px, 1fr) auto auto;
}

.post-overview-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.overview-chip {
  padding: 16px 18px;
  border-radius: 18px;
  border: 1px solid rgba(255, 255, 255, 0.05);
  background:
    radial-gradient(circle at top right, rgba(255, 206, 92, 0.12), transparent 52%),
    linear-gradient(180deg, rgba(24, 24, 24, 0.96), rgba(12, 12, 12, 0.98));
  display: grid;
  gap: 6px;
  min-width: 0;
}

.overview-chip__label {
  color: #9d9d9d;
  font-size: 12px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.overview-chip__value {
  font-size: 30px;
  font-weight: 700;
  line-height: 1;
}

.overview-chip__hint {
  color: #8d8d8d;
  font-size: 12px;
  line-height: 1.5;
}

.post-list {
  display: grid;
  gap: 16px;
}

.post-entry {
  padding: 18px;
  border-radius: 22px;
  border: 1px solid rgba(255, 255, 255, 0.05);
  background:
    radial-gradient(circle at top right, rgba(255, 204, 82, 0.08), transparent 36%),
    linear-gradient(180deg, rgba(20, 20, 20, 0.98), rgba(9, 9, 9, 0.98));
  box-shadow: 0 18px 40px rgba(0, 0, 0, 0.16);
}

.post-entry__hero {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
  margin-bottom: 16px;
}

.post-entry__badges {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.entry-time {
  color: #8d8d8d;
  font-size: 12px;
  line-height: 1.5;
}

.post-entry__score {
  min-width: 104px;
  padding: 12px 14px;
  border-radius: 16px;
  border: 1px solid rgba(255, 192, 0, 0.08);
  background: rgba(255, 255, 255, 0.03);
  display: grid;
  justify-items: end;
  gap: 4px;
}

.score-caption {
  color: #8d8d8d;
  font-size: 12px;
}

.post-entry__body {
  display: grid;
  grid-template-columns: minmax(0, 1.7fr) minmax(280px, 0.95fr);
  gap: 18px;
  align-items: start;
}

.post-entry__content,
.post-side-panel,
.side-panel-block {
  display: grid;
  gap: 12px;
}

.post-entry__content {
  min-width: 0;
}

.post-summary-block {
  padding: 14px 16px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.035);
  color: #d3d3d3;
  font-size: 14px;
  line-height: 1.7;
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
}

.post-meta-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.meta-pill {
  padding: 12px 14px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.03);
  min-width: 0;
  --admin-info-stack-gap: 6px;
  --admin-info-stack-title-size: 13px;
  --admin-info-stack-title-weight: 600;
}

.post-side-panel {
  padding: 14px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.04);
}

.side-panel-block {
  padding: 12px;
  border-radius: 14px;
  background: rgba(0, 0, 0, 0.18);
}

.side-panel-title {
  color: #f2d391;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.metric-grid--compact {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.flag-row--spacious {
  min-height: 32px;
}

.action-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.action-grid :deep(.el-button) {
  margin-left: 0;
  width: 100%;
}

.posts-empty {
  margin: 10px 0 4px;
  border-radius: 18px;
  border: 1px dashed rgba(255, 255, 255, 0.08);
  background: rgba(255, 255, 255, 0.02);
}

.community-table :deep(.el-table__cell) {
  vertical-align: top;
}

.community-table :deep(.cell) {
  padding-top: 14px;
  padding-bottom: 14px;
}

.post-main,
.status-stack,
.comment-cell {
  display: grid;
  gap: 8px;
}

.post-title-cell {
  font-size: 15px;
  font-weight: 700;
  line-height: 1.45;
}

.compact-user {
  --admin-info-stack-gap: 8px;
}

.table-subcopy {
  color: #8d8d8d;
  font-size: 12px;
  line-height: 1.55;
}

.table-subcopy--clamp {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.post-inline-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 12px;
  color: #a3a3a3;
  font-size: 12px;
  line-height: 1.5;
}

.inline-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.inline-tag {
  padding: 4px 10px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.05);
  color: #c9c9c9;
  font-size: 12px;
  line-height: 1;
}

.type-tag {
  min-width: 56px;
  justify-content: center;
}

.score-cell {
  font-size: 22px;
  font-weight: 700;
  line-height: 1.2;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.metric-chip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.04);
}

.metric-value {
  font-size: 14px;
  font-weight: 700;
}

.metric-label {
  color: #8d8d8d;
  font-size: 12px;
}

.status-row,
.flag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.status-muted {
  color: #8d8d8d;
  font-size: 12px;
  line-height: 1.5;
}

.action-group {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.action-group :deep(.el-button) {
  margin-left: 0;
  min-width: 88px;
}

.action-group--report :deep(.el-button) {
  min-width: 78px;
}

.report-reason-cell {
  line-height: 1.6;
}

@media (max-width: 1280px) {
  .toolbar {
    grid-template-columns: minmax(240px, 1fr) 150px auto auto;
  }

  .post-overview-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .post-entry__body {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 1080px) {
  .toolbar,
  .toolbar--compact {
    grid-template-columns: 1fr;
  }

  .post-meta-grid,
  .action-grid,
  .post-overview-grid {
    grid-template-columns: 1fr;
  }

  .post-entry {
    padding: 16px;
  }

  .post-entry__hero {
    flex-direction: column;
  }

  .post-entry__score {
    width: 100%;
    justify-items: start;
  }
}
</style>
