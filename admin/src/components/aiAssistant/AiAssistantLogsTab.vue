<script setup lang="ts">
import { computed } from 'vue'
import type { AdminAiLog } from '../../types'
import { renderMarkdown } from '../../utils/markdown'
import { answerTypeLabel, failureLabel, latencyTone } from './formatters'

const props = defineProps<{
  logs: AdminAiLog[]
  pagedLogs: AdminAiLog[]
  logPage: number
  logPageSize: number
  clearingLogs: boolean
  formatHitSources: (items: string[]) => string[]
  /** all 全部 / failed 失败 / slow 超过 10 秒 */
  logFilter: string
  totalLogs: number
}>()

const emit = defineEmits<{
  'update:logPage': [value: number]
  'update:logPageSize': [value: number]
  'update:logFilter': [value: string]
  clear: []
}>()

const logPageModel = computed({
  get: () => props.logPage,
  set: (value: number) => emit('update:logPage', value),
})

const logFilterModel = computed({
  get: () => props.logFilter,
  set: (value: string) => emit('update:logFilter', value),
})

const logPageSizeModel = computed({
  get: () => props.logPageSize,
  set: (value: number) => emit('update:logPageSize', value),
})
</script>

<template>
  <div class="logs-toolbar">
    <div class="logs-toolbar-copy">
      <div class="knowledge-kicker">Query Logs</div>
      <span class="knowledge-section-meta">最近 {{ totalLogs }} 条问答，点开一行看当时答成了什么</span>
    </div>
    <el-radio-group v-model="logFilterModel" size="small">
      <el-radio-button value="all">全部</el-radio-button>
      <el-radio-button value="failed">失败</el-radio-button>
      <el-radio-button value="slow">超过 10 秒</el-radio-button>
    </el-radio-group>
    <el-button text type="warning" :disabled="!logs.length" :loading="clearingLogs" @click="emit('clear')">
      一键清空问答日志
    </el-button>
  </div>

  <div class="table-scroll-shell">
    <el-table :data="pagedLogs" class="wide-table wide-table--logs" row-key="id" empty-text="暂无问答日志">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="log-answer">
            <div class="log-answer__label">回答</div>
            <div v-if="row.answer" class="log-answer__body" v-html="renderMarkdown(row.answer)" />
            <div v-else class="knowledge-section-meta">会话已被清空，找不到这次的回答</div>
            <div class="knowledge-section-meta">traceId {{ row.traceId }}</div>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="时间" width="170" />
      <el-table-column prop="userMessage" label="问题" min-width="280" show-overflow-tooltip />
      <el-table-column label="回答类型" width="110">
        <template #default="{ row }">{{ answerTypeLabel(row.answerType) }}</template>
      </el-table-column>
      <el-table-column label="交给模型的资料" min-width="260">
        <template #default="{ row }">
          <div class="tag-row">
            <el-tag v-for="item in formatHitSources(row.hitSources)" :key="item" effect="plain">{{ item }}</el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="联网" width="70">
        <template #default="{ row }">
          <el-tag :type="row.usedWebSearch ? 'warning' : 'info'">
            {{ row.usedWebSearch ? '是' : '否' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="耗时" width="100">
        <template #default="{ row }">
          <el-tag :type="latencyTone(row.latencyMs)" effect="plain">{{ (row.latencyMs / 1000).toFixed(1) }} 秒</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="结果" width="200">
        <template #default="{ row }">
          <el-tag v-if="row.success" type="success">成功</el-tag>
          <el-tooltip v-else :content="row.failureType || ''" placement="top">
            <el-tag type="danger">{{ failureLabel(row.failureType) || '失败' }}</el-tag>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column prop="openid" label="用户" width="160" show-overflow-tooltip />
    </el-table>
  </div>

  <div v-if="logs.length" class="table-footer">
    <div class="table-total">筛出 {{ logs.length }} 条</div>
    <el-pagination
      v-model:current-page="logPageModel"
      v-model:page-size="logPageSizeModel"
      background
      size="small"
      layout="sizes, prev, pager, next"
      :pager-count="5"
      :page-sizes="[10, 20, 30, 50]"
      :total="logs.length"
    />
  </div>
</template>

<style scoped>
.logs-toolbar-copy {
  display: grid;
  gap: 6px;
}

.knowledge-kicker {
  color: var(--gold);
  font-size: 11px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
}

.knowledge-section-meta,
.table-total {
  color: var(--text-3);
  font-size: 13px;
}

.logs-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
  flex-wrap: wrap;
  margin-bottom: 14px;
  padding: 16px 18px;
  border: 1px solid rgba(255, 255, 255, 0.04);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.02);
}

.log-answer {
  display: grid;
  gap: 8px;
  padding: 4px 48px 8px;
}

.log-answer__label {
  color: var(--gold);
  font-size: 12px;
}

.log-answer__body {
  color: var(--text);
  font-size: 14px;
  line-height: 1.8;
}

.log-answer__body :deep(h4) {
  margin: 10px 0 4px;
  color: var(--gold);
}

.log-answer__body :deep(p),
.log-answer__body :deep(ul) {
  margin: 0 0 6px;
}

.log-answer__body :deep(strong) {
  color: var(--gold);
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.table-scroll-shell {
  width: 100%;
  min-width: 0;
  overflow-x: auto;
  overflow-y: hidden;
  padding-bottom: 8px;
  scrollbar-width: thin;
  scrollbar-color: rgba(242, 179, 61, 0.42) rgba(255, 255, 255, 0.05);
}

.table-scroll-shell::-webkit-scrollbar {
  height: 8px;
}

.table-scroll-shell::-webkit-scrollbar-track {
  background: rgba(255, 255, 255, 0.05);
  border-radius: 999px;
}

.table-scroll-shell::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background: linear-gradient(90deg, rgba(242, 179, 61, 0.7), rgba(182, 126, 12, 0.72));
}

.table-scroll-shell :deep(.el-table) {
  min-width: 100%;
}

.table-scroll-shell :deep(.el-scrollbar__wrap) {
  overflow-x: hidden !important;
}

.table-scroll-shell :deep(.el-scrollbar__bar.is-horizontal) {
  display: none !important;
}

.table-scroll-shell :deep(.wide-table--logs) {
  min-width: 1320px;
}

.table-footer {
  margin-top: 14px;
  padding-top: 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

.table-footer :deep(.el-pagination) {
  margin-left: auto;
}

@media (max-width: 960px) {
  .logs-toolbar,
  .table-footer {
    flex-direction: column;
    align-items: flex-start;
  }

  .table-footer :deep(.el-pagination) {
    margin-left: 0;
  }
}
</style>
