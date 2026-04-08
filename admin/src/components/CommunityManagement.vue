<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
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
const reportStatusFilter = ref('ALL')

const filteredPosts = computed(() =>
  posts.value.filter((post) => {
    const matchesKeyword = !postKeyword.value || post.content.includes(postKeyword.value) || post.nickname.includes(postKeyword.value)
    const normalizedStatus = post.status === 1 ? 'PUBLISHED' : 'OFFLINE'
    const matchesStatus = postStatusFilter.value === 'ALL' || normalizedStatus === postStatusFilter.value
    return matchesKeyword && matchesStatus
  }),
)

const filteredComments = computed(() =>
  comments.value.filter(
    (comment) =>
      !commentKeyword.value ||
      comment.content.includes(commentKeyword.value) ||
      comment.nickname.includes(commentKeyword.value) ||
      String(comment.postId).includes(commentKeyword.value),
  ),
)

const filteredReports = computed(() =>
  reports.value.filter((report) => reportStatusFilter.value === 'ALL' || report.processStatus === reportStatusFilter.value),
)

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
    <template #header>
      <div class="panel-header">
        <div>
          <div class="panel-kicker">Community</div>
          <h3>社区治理</h3>
          <p>对帖子、评论、举报进行统一治理，处理结果会直接沉淀到后端数据。</p>
        </div>
      </div>
    </template>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="帖子审核" name="posts">
        <div class="toolbar">
          <el-input v-model="postKeyword" placeholder="搜索作者或帖子内容" clearable />
          <el-select v-model="postStatusFilter">
            <el-option label="全部状态" value="ALL" />
            <el-option label="已发布" value="PUBLISHED" />
            <el-option label="已下线" value="OFFLINE" />
          </el-select>
        </div>

        <el-table :data="filteredPosts">
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
      </el-tab-pane>

      <el-tab-pane label="评论管理" name="comments">
        <div class="toolbar">
          <el-input v-model="commentKeyword" placeholder="搜索评论、作者或帖子 ID" clearable />
        </div>

        <el-table :data="filteredComments">
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
      </el-tab-pane>

      <el-tab-pane label="举报处理" name="reports">
        <div class="toolbar">
          <el-select v-model="reportStatusFilter">
            <el-option label="全部状态" value="ALL" />
            <el-option label="OPEN" value="OPEN" />
            <el-option label="RESOLVED" value="RESOLVED" />
            <el-option label="DISMISSED" value="DISMISSED" />
          </el-select>
        </div>

        <el-table :data="filteredReports">
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
  align-items: center;
}

.panel-kicker {
  color: #ffc000;
  font-size: 12px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
}

.panel-header h3 {
  margin: 8px 0 6px;
  font-size: 28px;
}

.panel-header p {
  margin: 0;
  color: #8d8d8d;
  line-height: 1.7;
}

.toolbar {
  display: grid;
  grid-template-columns: minmax(240px, 420px) 180px;
  gap: 12px;
  margin-bottom: 16px;
}

@media (max-width: 900px) {
  .toolbar {
    grid-template-columns: 1fr;
  }
}
</style>
