<script setup lang="ts">
import { computed } from 'vue'
import type { UploadRequestOptions } from 'element-plus'
import type { AdminAiDocument } from '../../types'
import type { DocumentSummary } from './formatters'

const props = defineProps<{
  documents: AdminAiDocument[]
  pagedDocuments: AdminAiDocument[]
  documentSummary: DocumentSummary
  uploadedDocumentCount: number
  documentPage: number
  documentPageSize: number
  clearingDocuments: boolean
  uploadRequest: (options: UploadRequestOptions) => void | Promise<void>
  sourceTypeLabel: (sourceType: string) => string
  statusTagType: (status: string) => string
  processingStatusLabel: (status: string) => string
  reviewStatusLabel: (status: string) => string
}>()

const emit = defineEmits<{
  'update:documentPage': [value: number]
  'update:documentPageSize': [value: number]
  review: [document: AdminAiDocument, reviewStatus: string]
  reindex: [document: AdminAiDocument]
  clear: []
}>()

const documentPageModel = computed({
  get: () => props.documentPage,
  set: (value: number) => emit('update:documentPage', value),
})

const documentPageSizeModel = computed({
  get: () => props.documentPageSize,
  set: (value: number) => emit('update:documentPageSize', value),
})
</script>

<template>
  <div class="knowledge-shell">
    <div class="knowledge-hero">
      <div class="knowledge-upload-card">
        <div class="knowledge-upload-copy">
          <div class="knowledge-kicker">Knowledge Intake</div>
          <h4>上传知识文档</h4>
          <p>支持 PDF、Markdown 与 TXT。新文档会先进入待审核区，发布后才会进入线上问答召回。</p>
        </div>
        <div class="knowledge-upload-actions">
          <el-upload :show-file-list="false" :http-request="uploadRequest" accept=".pdf,.md,.markdown,.txt">
            <el-button type="primary" size="large">上传 PDF / MD / TXT</el-button>
          </el-upload>
          <span class="knowledge-upload-tip">上传后会自动解析、切片并等待审核。</span>
        </div>
      </div>

      <div class="knowledge-stats">
        <div class="knowledge-stat-card">
          <span class="knowledge-stat-label">总文档</span>
          <strong>{{ documentSummary.total }}</strong>
          <small>当前全部知识资产</small>
        </div>
        <div class="knowledge-stat-card">
          <span class="knowledge-stat-label">待审核</span>
          <strong>{{ documentSummary.pending }}</strong>
          <small>等待运营确认</small>
        </div>
        <div class="knowledge-stat-card">
          <span class="knowledge-stat-label">已通过</span>
          <strong>{{ documentSummary.approved }}</strong>
          <small>可以进入发布池</small>
        </div>
        <div class="knowledge-stat-card">
          <span class="knowledge-stat-label">已发布</span>
          <strong>{{ documentSummary.published }}</strong>
          <small>线上可被召回</small>
        </div>
      </div>
    </div>

    <div class="knowledge-table-card">
      <div class="knowledge-section-header">
        <div>
          <div class="knowledge-kicker">Knowledge Queue</div>
          <h4>知识文档列表</h4>
        </div>
        <div class="knowledge-section-actions">
          <span class="knowledge-section-meta">共 {{ documents.length }} 条资产，其中上传文档 {{ uploadedDocumentCount }} 条</span>
          <el-button
            text
            type="warning"
            :disabled="!uploadedDocumentCount"
            :loading="clearingDocuments"
            @click="emit('clear')"
          >
            一键清空上传文档
          </el-button>
        </div>
      </div>

      <template v-if="documents.length">
        <div class="table-scroll-shell table-scroll-shell--knowledge">
          <el-table :data="pagedDocuments" class="knowledge-table wide-table wide-table--knowledge" empty-text="暂无知识文档">
            <el-table-column prop="name" label="名称" min-width="220">
              <template #default="{ row }">
                <div class="document-name-cell">
                  <strong>{{ row.name }}</strong>
                  <span>{{ row.fileName || row.sourceKey }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="来源" width="120">
              <template #default="{ row }">
                <el-tag effect="plain">{{ sourceTypeLabel(row.sourceType) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="解析状态" width="126">
              <template #default="{ row }">
                <el-tag :type="statusTagType(row.processingStatus)" effect="dark">
                  {{ processingStatusLabel(row.processingStatus) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="审核状态" width="126">
              <template #default="{ row }">
                <el-tag :type="statusTagType(row.reviewStatus)" effect="dark">
                  {{ reviewStatusLabel(row.reviewStatus) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="切片数" width="92" align="center">
              <template #default="{ row }">
                <span class="document-chunk-count">{{ row.chunkCount }}</span>
              </template>
            </el-table-column>
            <el-table-column label="摘要" min-width="320" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="document-summary">{{ row.summary || '暂无摘要，等待解析完成。' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="220" fixed="right">
              <template #default="{ row }">
                <div class="document-actions">
                  <el-button text type="primary" @click="emit('review', row, 'APPROVED')">通过</el-button>
                  <el-button text type="warning" @click="emit('review', row, 'REJECTED')">驳回</el-button>
                  <el-button text @click="emit('reindex', row)">重建索引</el-button>
                </div>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div class="table-footer">
          <div class="table-total">共 {{ documents.length }} 条知识资产</div>
          <el-pagination
            v-model:current-page="documentPageModel"
            v-model:page-size="documentPageSizeModel"
            background
            size="small"
            layout="sizes, prev, pager, next"
            :pager-count="5"
            :page-sizes="[6, 8, 12, 20]"
            :total="documents.length"
          />
        </div>
      </template>

      <div v-else class="knowledge-empty-state">
        <div class="knowledge-empty-mark">W</div>
        <div class="knowledge-empty-copy">
          <h4>还没有知识文档</h4>
          <p>先上传规则说明、板子手册或 FAQ 文档，审核并发布后，AI 助手才能稳定回答站内问题。</p>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.knowledge-shell {
  display: grid;
  gap: 18px;
  min-width: 0;
}

.knowledge-hero {
  display: grid;
  gap: 16px;
  min-width: 0;
}

.knowledge-upload-card {
  padding: 20px;
  border: 1px solid rgba(255, 192, 0, 0.12);
  border-radius: 18px;
  background:
    radial-gradient(circle at top right, rgba(255, 192, 0, 0.12), transparent 36%),
    linear-gradient(180deg, rgba(28, 28, 28, 0.96), rgba(14, 14, 14, 0.96));
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 18px;
  min-width: 0;
}

.knowledge-upload-copy {
  display: grid;
  gap: 6px;
}

.knowledge-kicker {
  color: #ffc000;
  font-size: 11px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
}

.knowledge-upload-copy h4,
.knowledge-empty-copy h4,
.knowledge-section-header h4 {
  margin: 0;
  font-size: 22px;
  line-height: 1.1;
}

.knowledge-upload-copy p,
.knowledge-empty-copy p {
  margin: 0;
  color: #9d9d9d;
  line-height: 1.6;
}

.knowledge-upload-actions {
  display: grid;
  justify-items: end;
  align-content: center;
  gap: 12px;
}

.knowledge-upload-tip {
  color: #9d9d9d;
  font-size: 12px;
  line-height: 1.5;
}

.knowledge-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  min-width: 0;
}

.knowledge-stat-card {
  padding: 18px;
  border-radius: 16px;
  border: 1px solid rgba(255, 192, 0, 0.1);
  background: linear-gradient(180deg, rgba(30, 30, 30, 0.94), rgba(18, 18, 18, 0.94));
  display: grid;
  gap: 6px;
}

.knowledge-stat-card strong {
  font-size: 28px;
  line-height: 1;
  color: #ffc000;
}

.knowledge-stat-label,
.knowledge-stat-card small,
.knowledge-section-meta,
.document-name-cell span,
.document-summary {
  color: #9d9d9d;
}

.knowledge-stat-label {
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.knowledge-stat-card small {
  font-size: 12px;
}

.knowledge-table-card {
  padding: 18px;
  border: 1px solid rgba(255, 255, 255, 0.04);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.02);
  min-width: 0;
}

.knowledge-section-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
  min-width: 0;
}

.knowledge-section-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
  flex-wrap: wrap;
}

.knowledge-section-meta {
  font-size: 13px;
}

.document-name-cell {
  display: grid;
  gap: 4px;
}

.document-name-cell strong {
  color: #f3f3f3;
  font-size: 14px;
}

.document-name-cell span {
  font-size: 12px;
}

.document-chunk-count {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 34px;
  height: 34px;
  border-radius: 999px;
  background: rgba(255, 192, 0, 0.12);
  color: #ffc000;
  font-weight: 700;
}

.document-summary {
  line-height: 1.5;
}

.document-actions {
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

.table-scroll-shell--knowledge :deep(.el-table),
:deep(.wide-table--knowledge) {
  min-width: 1160px;
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

.knowledge-empty-state {
  min-height: 280px;
  display: grid;
  place-items: center;
  gap: 16px;
  text-align: center;
  border: 1px dashed rgba(255, 192, 0, 0.18);
  border-radius: 16px;
  background: linear-gradient(180deg, rgba(24, 24, 24, 0.88), rgba(14, 14, 14, 0.96));
}

.knowledge-empty-mark {
  width: 74px;
  height: 74px;
  border-radius: 24px;
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, rgba(255, 192, 0, 0.2), rgba(255, 192, 0, 0.06));
  color: #ffc000;
  font-size: 32px;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.knowledge-empty-copy {
  display: grid;
  gap: 10px;
  max-width: 460px;
  padding: 0 16px;
}

:deep(.knowledge-table .el-table__inner-wrapper::before) {
  background-color: rgba(255, 255, 255, 0.08);
}

:deep(.knowledge-table th.el-table__cell) {
  background: rgba(255, 255, 255, 0.02);
}

:deep(.knowledge-table .el-table__row:hover > td.el-table__cell) {
  background: rgba(255, 192, 0, 0.05);
}

@media (max-width: 1200px) {
  .knowledge-upload-card {
    grid-template-columns: 1fr;
  }

  .knowledge-upload-actions {
    justify-items: start;
  }

  .knowledge-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 960px) {
  .knowledge-section-header,
  .knowledge-section-actions,
  .table-footer {
    flex-direction: column;
    align-items: flex-start;
  }

  .table-footer :deep(.el-pagination) {
    margin-left: 0;
  }
}
</style>
