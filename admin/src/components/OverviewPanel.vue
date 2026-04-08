<script setup lang="ts">
import type { DashboardSummary } from '../types'

withDefaults(
  defineProps<{
    summary: DashboardSummary | null
    compact?: boolean
  }>(),
  {
    compact: false,
  },
)

const cards = [
  { key: 'boardCount', label: '板子总数', hint: 'Boards' },
  { key: 'roleCount', label: '角色总数', hint: 'Roles' },
  { key: 'postCount', label: '社区帖子', hint: 'Posts' },
  { key: 'commentCount', label: '评论总量', hint: 'Comments' },
  { key: 'openReportCount', label: '待处理举报', hint: 'Reports' },
] as const
</script>

<template>
  <div class="summary-grid" :class="{ compact }">
    <div v-for="card in cards" :key="card.key" class="summary-card" :class="{ compact }">
      <div class="summary-hint">{{ card.hint }}</div>
      <div class="summary-label">{{ card.label }}</div>
      <div class="summary-value" :class="{ compact }">{{ summary ? summary[card.key] : '--' }}</div>
    </div>
  </div>
</template>

<style scoped>
.summary-grid {
  margin-top: 14px;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
  gap: 14px;
}

.summary-grid.compact {
  margin-top: 12px;
}

.summary-card {
  min-height: 118px;
  padding: 18px;
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 16px;
  background: linear-gradient(180deg, rgba(24, 24, 24, 0.98), rgba(11, 11, 11, 0.98));
  display: grid;
  align-content: start;
  gap: 8px;
}

.summary-card.compact {
  min-height: 96px;
  padding: 14px 16px;
}

.summary-hint {
  color: #ffc000;
  font-size: 10px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
}

.summary-label {
  color: #9a9a9a;
  font-size: 13px;
}

.summary-value {
  font-size: 34px;
  line-height: 1;
  font-weight: 700;
}

.summary-value.compact {
  font-size: 28px;
}
</style>
