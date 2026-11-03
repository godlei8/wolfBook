import { computed, ref } from 'vue'

// 后台只有几个页面，用 hash 记住当前位置（刷新不丢、能前进后退），不引入 vue-router
export type MenuKey = 'overview' | 'boards' | 'roles' | 'world' | 'community' | 'ai'

const MENUS: MenuKey[] = ['overview', 'boards', 'roles', 'world', 'community', 'ai']

function readHash() {
  return window.location.hash.replace(/^#\/?/, '')
}

const path = ref(readHash())
window.addEventListener('hashchange', () => {
  path.value = readHash()
})

export const route = computed(() => {
  const [first = '', second = '', ...rest] = path.value.split('/')
  const menu: MenuKey = (MENUS as string[]).includes(first) ? (first as MenuKey) : 'overview'
  // boards/new 新建，boards/12 编辑
  const boardEdit = menu === 'boards' && second ? (second === 'new' ? 'new' : Number(second) || null) : null
  // ai/knowledge、ai/settings/models：AI 助手页的哪一块、设置的哪一小节（utils/aiAdmin.ts 的 parseAiPath 解析）
  const aiParts = menu === 'ai' ? [second, ...rest].filter(Boolean) : []
  return { menu, boardEdit, aiParts }
})

export function go(target: string) {
  const next = target.replace(/^#?\/?/, '')
  if (next === path.value) return
  window.location.hash = `/${next}`
}
