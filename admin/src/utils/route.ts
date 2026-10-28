import { computed, ref } from 'vue'

// 后台只有几个页面，用 hash 记住当前位置（刷新不丢、能前进后退），不引入 vue-router
export type MenuKey = 'overview' | 'boards' | 'roles' | 'community' | 'ai'

const MENUS: MenuKey[] = ['overview', 'boards', 'roles', 'community', 'ai']

function readHash() {
  return window.location.hash.replace(/^#\/?/, '')
}

const path = ref(readHash())
window.addEventListener('hashchange', () => {
  path.value = readHash()
})

export const route = computed(() => {
  const [first = '', second = ''] = path.value.split('/')
  const menu: MenuKey = (MENUS as string[]).includes(first) ? (first as MenuKey) : 'overview'
  // boards/new 新建，boards/12 编辑
  const boardEdit = menu === 'boards' && second ? (second === 'new' ? 'new' : Number(second) || null) : null
  return { menu, boardEdit }
})

export function go(target: string) {
  const next = target.replace(/^#?\/?/, '')
  if (next === path.value) return
  window.location.hash = `/${next}`
}
