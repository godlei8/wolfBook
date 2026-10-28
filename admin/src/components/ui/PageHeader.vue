<script setup lang="ts">
// 页头：eyebrow + 宋体大标题 + 一句说明，右边放操作；传 banner 时铺一张场景图
defineProps<{
  eyebrow: string
  title: string
  description?: string
  banner?: string
}>()
</script>

<template>
  <header class="page-header" :class="{ 'page-header--banner': banner }">
    <template v-if="banner">
      <img class="page-header__img" :src="banner" alt="" />
      <div class="page-header__shade"></div>
    </template>
    <div class="page-header__text">
      <slot name="before" />
      <span class="nt-eyebrow">{{ eyebrow }}</span>
      <h1>{{ title }}</h1>
      <p v-if="description || $slots.description"><slot name="description">{{ description }}</slot></p>
    </div>
    <div v-if="$slots.actions" class="page-header__actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<style scoped>
.page-header {
  position: relative;
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
}

.page-header__text {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}

.page-header h1 {
  margin: 0;
  font: 900 40px/1.12 var(--serif);
}

.page-header p {
  margin: 0;
  color: var(--text-2);
  font-size: 15px;
  line-height: 1.6;
}

.page-header__actions {
  position: relative;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}

.page-header--banner {
  min-height: 170px;
  padding: 26px 30px;
  border-radius: 26px;
  overflow: hidden;
  border: 2px solid var(--ink);
  box-shadow: 5px 5px 0 var(--ink);
}

.page-header--banner p {
  color: var(--text-soft);
}

.page-header__img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: 50% 45%;
}

.page-header__shade {
  position: absolute;
  inset: 0;
  background: linear-gradient(90deg, rgba(14, 12, 19, 0.95) 0%, rgba(14, 12, 19, 0.72) 48%, rgba(14, 12, 19, 0.12) 100%);
}

@media (max-width: 720px) {
  .page-header h1 {
    font-size: 32px;
  }
}
</style>
