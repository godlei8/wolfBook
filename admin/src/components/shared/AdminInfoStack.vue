<script setup lang="ts">
defineProps<{
  eyebrow?: string
  title: string
  subtitle?: string
}>()
</script>

<template>
  <div class="admin-info-stack">
    <div v-if="eyebrow" class="admin-info-stack__eyebrow">{{ eyebrow }}</div>
    <div class="admin-info-stack__title">{{ title }}</div>
    <div v-if="subtitle" class="admin-info-stack__subtitle">{{ subtitle }}</div>
  </div>
</template>

<style scoped>
.admin-info-stack {
  display: grid;
  gap: var(--admin-info-stack-gap, 6px);
  min-width: 0;
}

.admin-info-stack__eyebrow {
  color: var(--admin-info-stack-eyebrow-color, #8d8d8d);
  font-size: var(--admin-info-stack-eyebrow-size, 11px);
  font-weight: var(--admin-info-stack-eyebrow-weight, 600);
  letter-spacing: var(--admin-info-stack-eyebrow-spacing, 0.12em);
  line-height: 1.4;
  text-transform: uppercase;
}

.admin-info-stack__title {
  color: var(--admin-info-stack-title-color, #f0f0f0);
  font-size: var(--admin-info-stack-title-size, 15px);
  font-weight: var(--admin-info-stack-title-weight, 700);
  line-height: 1.45;
  word-break: break-word;
}

.admin-info-stack__subtitle {
  color: var(--admin-info-stack-subtitle-color, #8d8d8d);
  font-size: var(--admin-info-stack-subtitle-size, 12px);
  line-height: 1.55;
  word-break: break-word;
}
</style>
