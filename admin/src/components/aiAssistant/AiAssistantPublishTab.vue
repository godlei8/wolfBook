<script setup lang="ts">
import { computed } from 'vue'
import type { AdminAiVersion } from '../../types'

const props = defineProps<{
  publishNotes: string
  publishing: boolean
  versions: AdminAiVersion[]
  pagedVersions: AdminAiVersion[]
  versionPage: number
  versionPageSize: number
}>()

const emit = defineEmits<{
  'update:publishNotes': [value: string]
  'update:versionPage': [value: number]
  'update:versionPageSize': [value: number]
  publish: []
  rollback: [versionId: number]
}>()

const publishNotesModel = computed({
  get: () => props.publishNotes,
  set: (value: string) => emit('update:publishNotes', value),
})

const versionPageModel = computed({
  get: () => props.versionPage,
  set: (value: number) => emit('update:versionPage', value),
})

const versionPageSizeModel = computed({
  get: () => props.versionPageSize,
  set: (value: number) => emit('update:versionPageSize', value),
})
</script>

<template>
  <el-card class="sub-card">
    <template #header>发布操作</template>
    <div class="publish-box">
      <el-input
        v-model="publishNotesModel"
        type="textarea"
        :rows="3"
        placeholder="写一段本次发布说明，方便后续回滚和版本追踪。"
      />
      <el-button type="primary" :loading="publishing" @click="emit('publish')">发布当前已审核文档</el-button>
    </div>
  </el-card>

  <div class="table-scroll-shell">
    <el-table :data="pagedVersions" class="wide-table wide-table--publish" empty-text="暂无发布版本">
      <el-table-column prop="versionName" label="版本" min-width="180" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="row.current ? 'warning' : 'info'">
            {{ row.current ? '当前线上' : '历史版本' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="发布人" width="120">
        <template #default="{ row }">{{ row.publishedBy || '系统' }}</template>
      </el-table-column>
      <el-table-column prop="notes" label="说明" min-width="280" show-overflow-tooltip />
      <el-table-column label="文档数" width="100">
        <template #default="{ row }">{{ row.documentIds.length }}</template>
      </el-table-column>
      <el-table-column prop="createTime" label="发布时间" width="180" />
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button text :disabled="row.current" @click="emit('rollback', row.id)">回滚到此</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>

  <div v-if="versions.length" class="table-footer">
    <div class="table-total">共 {{ versions.length }} 个版本</div>
    <el-pagination
      v-model:current-page="versionPageModel"
      v-model:page-size="versionPageSizeModel"
      background
      size="small"
      layout="sizes, prev, pager, next"
      :pager-count="5"
      :page-sizes="[6, 8, 12, 20]"
      :total="versions.length"
    />
  </div>
</template>

<style scoped>
.sub-card {
  border-radius: 16px;
}

.publish-box {
  display: grid;
  gap: 12px;
}

.publish-box :deep(.el-button) {
  justify-self: end;
  width: auto;
  min-width: 220px;
  padding-inline: 24px;
}

.table-scroll-shell {
  width: 100%;
  min-width: 0;
  overflow-x: auto;
  overflow-y: hidden;
  padding-bottom: 8px;
  margin-top: 16px;
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

.table-scroll-shell :deep(.wide-table--publish) {
  min-width: 980px;
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

.table-total {
  color: #9d9d9d;
  font-size: 13px;
}

.table-footer :deep(.el-pagination) {
  margin-left: auto;
}

@media (max-width: 960px) {
  .table-footer {
    flex-direction: column;
    align-items: flex-start;
  }

  .table-footer :deep(.el-pagination) {
    margin-left: 0;
  }

  .publish-box :deep(.el-button) {
    justify-self: stretch;
    min-width: 0;
  }
}
</style>
