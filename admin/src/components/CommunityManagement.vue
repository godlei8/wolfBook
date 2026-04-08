<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../services/api'
import type { CommentView, PostSummary, ReportItem } from '../types'

const emit = defineEmits<{ changed: [] }>()

const activeTab = ref('posts')
const posts = ref<PostSummary[]>([])
const comments = ref<CommentView[]>([])
const reports = ref<ReportItem[]>([])
const loading = ref(false)

const postKeyword = ref('')
const postStatusFilter = ref('ALL')
const commentKeyword = ref('')
const reportKeyword = ref('')
const reportStatusFilter = ref('ALL')

const postPage = ref(1)
const postPageSize = ref(8)
const commentPage = ref(1)
const commentPageSize = ref(10)
const reportPage = ref(1)
const reportPageSize = ref(8)

const filteredPosts = computed(() =>
  posts.value.filter((post) => {
    const normalizedKeyword = postKeyword.value.trim().toLowerCase()
    const searchableText = [post.content, post.nickname, post.openid].join(' ').toLowerCase()
    const matchesKeyword = !normalizedKeyword || searchableText.includes(normalizedKeyword)
    const normalizedStatus = post.status === 1 ? 'PUBLISHED' : 'OFFLINE'
    const matchesStatus = postStatusFilter.value === 'ALL' || normalizedStatus === postStatusFilter.value
    return matchesKeyword && matchesStatus
  }),
)

const filteredComments = computed(() =>
  comments.value.filter((comment) => {
    const normalizedKeyword = commentKeyword.value.trim().toLowerCase()
    const searchableText = [comment.content, comment.nickname, comment.openid, String(comment.postId)]
      .join(' ')
      .toLowerCase()
    return !normalizedKeyword || searchableText.includes(normalizedKeyword)
  }),
)

const filteredReports = computed(() =>
  reports.value.filter((report) => {
    const normalizedKeyword = reportKeyword.value.trim().toLowerCase()
    const searchableText = [report.reason, report.openid, report.targetType, String(report.targetId)]
      .join(' ')
      .toLowerCase()
    const matchesKeyword = !normalizedKeyword || searchableText.includes(normalizedKeyword)
    const matchesStatus = reportStatusFilter.value === 'ALL' || report.processStatus === reportStatusFilter.value
    return matchesKeyword && matchesStatus
  }),
)

const pagedPosts = computed(() => {
  const start = (postPage.value - 1) * postPageSize.value
  return filteredPosts.value.slice(start, start + postPageSize.value)
})

const pagedComments = computed(() => {
  const start = (commentPage.value - 1) * commentPageSize.value
  return filteredComments.value.slice(start, start + commentPageSize.value)
})

const pagedReports = computed(() => {
  const start = (reportPage.value - 1) * reportPageSize.value
  return filteredReports.value.slice(start, start + reportPageSize.value)
})

watch([postKeyword, postStatusFilter, postPageSize], () => {
  postPage.value = 1
})

watch([commentKeyword, commentPageSize], () => {
  commentPage.value = 1
})

watch([reportKeyword, reportStatusFilter, reportPageSize], () => {
  reportPage.value = 1
})

function resetPostFilters() {
  postKeyword.value = ''
  postStatusFilter.value = 'ALL'
  postPage.value = 1
}

function resetCommentFilters() {
  commentKeyword.value = ''
  commentPage.value = 1
}

function resetReportFilters() {
  reportKeyword.value = ''
  reportStatusFilter.value = 'ALL'
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
    ElMessage.error(error instanceof Error ? error.message : '社区数据加载失败')
  } finally {
    loading.value = false
  }
}

async function toggleStatus(post: PostSummary) {
  try {
    await api.updatePostStatus(post.id, post.status === 1 ? 'OFFLINE' : 'PUBLISHED')
    ElMessage.success('帖子状态已更新')
    await load()
    emit('changed')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '状态更新失败')
  }
}

async function removePost(id: number) {
  try {
    await api.deletePost(id)
    ElMessage.success('帖子已删除')
    await load()
    emit('changed')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '帖子删除失败')
  }
}

async function removeComment(id: number) {
  try {
    await api.deleteComment(id)
    ElMessage.success('评论已删除')
    await load()
    emit('changed')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '评论删除失败')
  }
}

async function processReport(id: number, status: string) {
  try {
    await api.processReport(id, status)
    ElMessage.success('举报已处理')
    await load()
    emit('changed')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '举报处理失败')
  }
}

onMounted(load)
</script>

<template>
  <el-card class="panel-card" v-loading="loading">
    <el-tabs v-model="activeTab">
      <el-tab-pane label="帖子审核" name="posts">
        <div class="toolbar">
          <el-input v-model="postKeyword" clearable placeholder="搜索作者、openid 或帖子内容" />
          <el-select v-model="postStatusFilter">
            <el-option label="全部状态" value="ALL" />
            <el-option label="已发布" value="PUBLISHED" />
            <el-option label="已下线" value="OFFLINE" />
          </el-select>
          <el-button @click="resetPostFilters">重置</el-button>
          <div class="toolbar-summary">当前 {{ filteredPosts.length }} 条</div>
        </div>

        <el-table :data="pagedPosts" empty-text="暂无符合条件的帖子">
          <el-table-column prop="nickname" label="作者" width="130" />
          <el-table-column prop="content" label="内容" min-width="280" show-overflow-tooltip />
          <el-table-column prop="likeCount" label="点赞" width="80" />
          <el-table-column prop="commentCount" label="评论" width="80" />
          <el-table-column prop="createTime" label="发布时间" min-width="170" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="row.status === 1 ? 'warning' : 'info'">
                {{ row.status === 1 ? '已发布' : '已下线' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <el-button text type="primary" @click="toggleStatus(row)">
                {{ row.status === 1 ? '下线' : '发布' }}
              </el-button>
              <el-button text type="danger" @click="removePost(row.id)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="table-footer">
          <div class="table-total">共 {{ filteredPosts.length }} 条帖子</div>
          <el-pagination
            v-model:current-page="postPage"
            v-model:page-size="postPageSize"
            background
            size="small"
            layout="sizes, prev, pager, next"
            :pager-count="5"
            :page-sizes="[6, 8, 10, 20]"
            :total="filteredPosts.length"
          />
        </div>
      </el-tab-pane>

      <el-tab-pane label="评论管理" name="comments">
        <div class="toolbar comments-toolbar">
          <el-input v-model="commentKeyword" clearable placeholder="搜索评论内容、评论人或帖子 ID" />
          <el-button @click="resetCommentFilters">重置</el-button>
          <div class="toolbar-summary">当前 {{ filteredComments.length }} 条</div>
        </div>

        <el-table :data="pagedComments" empty-text="暂无符合条件的评论">
          <el-table-column prop="nickname" label="评论人" width="130" />
          <el-table-column prop="postId" label="帖子 ID" width="90" />
          <el-table-column prop="content" label="内容" min-width="320" show-overflow-tooltip />
          <el-table-column prop="likeCount" label="点赞" width="80" />
          <el-table-column prop="createTime" label="评论时间" min-width="170" />
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-button text type="danger" @click="removeComment(row.id)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="table-footer">
          <div class="table-total">共 {{ filteredComments.length }} 条评论</div>
          <el-pagination
            v-model:current-page="commentPage"
            v-model:page-size="commentPageSize"
            background
            size="small"
            layout="sizes, prev, pager, next"
            :pager-count="5"
            :page-sizes="[8, 10, 20, 30]"
            :total="filteredComments.length"
          />
        </div>
      </el-tab-pane>

      <el-tab-pane label="举报处理" name="reports">
        <div class="toolbar">
          <el-input v-model="reportKeyword" clearable placeholder="搜索举报原因、举报人或目标 ID" />
          <el-select v-model="reportStatusFilter">
            <el-option label="全部状态" value="ALL" />
            <el-option label="OPEN" value="OPEN" />
            <el-option label="RESOLVED" value="RESOLVED" />
            <el-option label="DISMISSED" value="DISMISSED" />
          </el-select>
          <el-button @click="resetReportFilters">重置</el-button>
          <div class="toolbar-summary">当前 {{ filteredReports.length }} 条</div>
        </div>

        <el-table :data="pagedReports" empty-text="暂无符合条件的举报">
          <el-table-column prop="targetType" label="类型" width="110" />
          <el-table-column prop="targetId" label="目标 ID" width="100" />
          <el-table-column prop="openid" label="举报人" min-width="180" />
          <el-table-column prop="reason" label="原因" min-width="240" show-overflow-tooltip />
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag :type="row.processStatus === 'OPEN' ? 'danger' : row.processStatus === 'RESOLVED' ? 'warning' : 'info'">
                {{ row.processStatus }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="processBy" label="处理人" width="140" />
          <el-table-column prop="processTime" label="处理时间" min-width="170" />
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <el-button text type="primary" @click="processReport(row.id, 'RESOLVED')">标记已处理</el-button>
              <el-button text @click="processReport(row.id, 'DISMISSED')">驳回</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="table-footer">
          <div class="table-total">共 {{ filteredReports.length }} 条举报</div>
          <el-pagination
            v-model:current-page="reportPage"
            v-model:page-size="reportPageSize"
            background
            size="small"
            layout="sizes, prev, pager, next"
            :pager-count="5"
            :page-sizes="[6, 8, 10, 20]"
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

.panel-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
}

.panel-copy {
  display: grid;
  gap: 4px;
}

.panel-kicker {
  color: #ffc000;
  font-size: 11px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
}

.panel-header h3 {
  margin: 0;
  font-size: 22px;
  line-height: 1.1;
}

.panel-header p {
  margin: 0;
  color: #8d8d8d;
  line-height: 1.55;
}

.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 14px;
  align-items: center;
  padding: 12px;
  border: 1px solid rgba(255, 255, 255, 0.05);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.02);
}

.toolbar > :nth-child(1) {
  flex: 1 1 360px;
  min-width: 280px;
}

.toolbar > :nth-child(2) {
  flex: 0 0 160px;
}

.toolbar > :nth-child(3) {
  flex: 0 0 90px;
}

.comments-toolbar > :nth-child(2) {
  flex: 0 0 90px;
}

.toolbar-summary {
  margin-left: auto;
  flex: 0 0 auto;
  color: #8d8d8d;
  font-size: 13px;
  white-space: nowrap;
}

.table-footer {
  margin-top: 14px;
  padding-top: 14px;
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  border-top: 1px solid rgba(255, 255, 255, 0.05);
}

.table-total {
  color: #8d8d8d;
  font-size: 13px;
}

.table-footer :deep(.el-pagination) {
  margin-left: auto;
}

@media (max-width: 1080px) {
  .toolbar > :nth-child(1) {
    flex-basis: 100%;
  }

  .toolbar-summary {
    margin-left: 0;
  }

  .table-footer {
    flex-direction: column;
    align-items: flex-start;
  }
}

@media (max-width: 900px) {
  .toolbar,
  .comments-toolbar {
    display: grid;
    grid-template-columns: 1fr;
  }

  .toolbar > :nth-child(1),
  .toolbar > :nth-child(2),
  .toolbar > :nth-child(3),
  .comments-toolbar > :nth-child(2),
  .toolbar-summary {
    flex: initial;
    min-width: 0;
    margin-left: 0;
  }
}
</style>
