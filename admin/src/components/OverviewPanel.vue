<script setup lang="ts">
import type { DashboardSummary } from '../types'

defineProps<{
  summary: DashboardSummary | null
}>()

const cards = [
  { key: 'boardCount', label: '板子总数', hint: 'Boards' },
  { key: 'roleCount', label: '角色总数', hint: 'Roles' },
  { key: 'postCount', label: '社区帖子', hint: 'Posts' },
  { key: 'commentCount', label: '评论总量', hint: 'Comments' },
  { key: 'openReportCount', label: '待处理举报', hint: 'Reports' },
] as const
</script>

<template>
  <div class="summary-grid">
    <div v-for="card in cards" :key="card.key" class="summary-card">
      <div class="summary-hint">{{ card.hint }}</div>
      <div class="summary-label">{{ card.label }}</div>
      <div class="summary-value">{{ summary ? summary[card.key] : '--' }}</div>
    </div>
  </div>
</template>

<style scoped>
.summary-grid {
  margin-top: 24px;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 16px;
}

.summary-card {
  min-height: 150px;
  padding: 22px;
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 16px;
  background: linear-gradient(180deg, rgba(24, 24, 24, 0.98), rgba(11, 11, 11, 0.98));
  display: grid;
  align-content: start;
  gap: 12px;
}

.summary-hint {
  color: #ffc000;
  font-size: 11px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
}

.summary-label {
  color: #9a9a9a;
  font-size: 14px;
}

.summary-value {
  font-size: 40px;
  line-height: 1;
  font-weight: 700;
}
</style>
