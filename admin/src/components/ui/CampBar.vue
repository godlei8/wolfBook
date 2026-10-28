<script setup lang="ts">
// 阵营比例条：神职 / 平民 / 狼人 / 第三方，每段宽度按人数
defineProps<{
  segments: { key: string; count: number; label: string; color: string }[]
  height?: number
  legend?: boolean
}>()
</script>

<template>
  <div class="campbar">
    <div class="nt-campbar" :style="{ height: `${height || 8}px` }" role="img" :aria-label="segments.map((s) => `${s.label} ${s.count}`).join('，')">
      <span v-for="segment in segments" :key="segment.key" :style="{ flexGrow: segment.count, background: segment.color }"></span>
    </div>
    <div v-if="legend !== false" class="campbar__legend">
      <span v-for="segment in segments" :key="segment.key">{{ segment.label }} {{ segment.count }}</span>
    </div>
  </div>
</template>

<style scoped>
.campbar {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.campbar__legend {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  font-size: 12px;
  color: var(--text-3);
}
</style>
