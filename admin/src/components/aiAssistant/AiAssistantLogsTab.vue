<script setup lang="ts">
import { computed } from 'vue'
import type { AdminAiLog } from '../../types'

const props = defineProps<{
  logs: AdminAiLog[]
  pagedLogs: AdminAiLog[]
  logPage: number
  logPageSize: number
  clearingLogs: boolean
  formatHitSources: (items: string[]) => string[]
}>()

const emit = defineEmits<{
  'update:logPage': [value: number]
  'update:logPageSize': [value: number]
  clear: []
}>()

const logPageModel = computed({
  get: () => props.logPage,
  set: (value: number) => emit('update:logPage', value),
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
      <span class="knowledge-section-meta">共 {{ logs.length }} 条问答日志</span>
    </div>
    <el-button text type="warning" :disabled="!logs.length" :loading="clearingLogs" @click="emit('clear')">
      一键清空问答日志
    </el-button>
  </div>

  <div class="table-scroll-shell">
    <el-table :data="pagedLogs" class="wide-table wide-table--logs" empty-text="暂无问答日志">
      <el-table-column prop="createTime" label="时间" width="180" />
      <el-table-column prop="openid" label="用户" width="180" show-overflow-tooltip />
      <el-table-column prop="userMessage" label="问题" min-width="280" show-overflow-tooltip />
      <el-table-column prop="answerType" label="答案类型" width="180" />
      <el-table-column label="命中来源" min-width="260">
        <template #default="{ row }">
          <div class="tag-row">
            <el-tag v-for="item in formatHitSources(row.hitSources)" :key="item" effect="plain">{{ item }}</el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="联网" width="80">
        <template #default="{ row }">
          <el-tag :type="row.usedWebSearch ? 'warning' : 'info'">
            {{ row.usedWebSearch ? '是' : '否' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="耗时" width="90">
        <template #default="{ row }">{{ row.latencyMs }}ms</template>
      </el-table-column>
      <el-table-column label="结果" width="90">
        <template #default="{ row }">
          <el-tag :type="row.success ? 'success' : 'danger'">
            {{ row.success ? '成功' : '失败' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="failureType" label="失败类型" width="160" show-overflow-tooltip />
    </el-table>
  </div>

  <div v-if="logs.length" class="table-footer">
    <div class="table-total">共 {{ logs.length }} 条问答日志</div>
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
  color: #ffc000;
  font-size: 11px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
}

.knowledge-section-meta,
.table-total {
  color: #9d9d9d;
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
  scrollbar-color: rgba(255, 192, 0, 0.42) rgba(255, 255, 255, 0.05);
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
  background: linear-gradient(90deg, rgba(255, 211, 92, 0.7), rgba(182, 126, 12, 0.72));
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
