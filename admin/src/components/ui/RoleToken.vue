<script setup lang="ts">
import { computed } from 'vue'
import type { Role } from '../../types'
import { CAMP_META, campOf, roleImage } from '../../utils/board'
import { resolveMediaUrl } from '../../utils/media'

// 圆形角色头像：有立绘用立绘，没有就写名字首字；ring 时外圈描阵营色，count > 1 时右下角标数量
const props = withDefaults(
  defineProps<{
    role?: Role | null
    size?: number
    ring?: boolean
    count?: number
  }>(),
  { role: null, size: 34, ring: false, count: 1 },
)

const image = computed(() => resolveMediaUrl(props.role?.portrait || roleImage(props.role)))
const style = computed(() => ({
  width: `${props.size}px`,
  height: `${props.size}px`,
  fontSize: `${Math.round(props.size * 0.4)}px`,
  boxShadow: props.ring ? `0 0 0 2px ${CAMP_META[campOf(props.role)].color}` : undefined,
}))
</script>

<template>
  <span class="token-wrap">
    <span class="nt-token" :style="style" :title="role?.name">
      <img v-if="image" :src="image" :alt="role?.name || ''" loading="lazy" />
      <span v-else>{{ role?.name?.slice(0, 1) || '?' }}</span>
    </span>
    <span v-if="count > 1" class="token-count">×{{ count }}</span>
  </span>
</template>

<style scoped>
.token-wrap {
  position: relative;
  display: inline-flex;
  flex: none;
}

.token-count {
  position: absolute;
  right: -6px;
  bottom: -5px;
  min-width: 20px;
  height: 18px;
  padding: 0 3px;
  border-radius: 9px;
  border: 2px solid var(--ink);
  background: var(--text);
  color: var(--ink);
  font-size: 11px;
  font-weight: 900;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
</style>
