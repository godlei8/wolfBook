<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import AdminLogin from './components/AdminLogin.vue'
import OverviewPage from './components/OverviewPage.vue'
import BoardList from './components/boards/BoardList.vue'
import BoardEditor from './components/boards/BoardEditor.vue'
import RoleManagement from './components/RoleManagement.vue'
import CommunityManagement from './components/CommunityManagement.vue'
import AiAssistantManagement from './components/AiAssistantManagement.vue'
import { UNAUTHORIZED_EVENT, api, clearStoredToken, getStoredToken, isUnauthorizedError } from './services/api'
import type { DashboardSummary, LoginResponse } from './types'
import { go, route, type MenuKey } from './utils/route'

const ADMIN_NAME_KEY = 'wolfbook_admin_name'

const authenticated = ref(Boolean(getStoredToken()))
const adminName = ref(localStorage.getItem(ADMIN_NAME_KEY) || 'admin')
const summary = ref<DashboardSummary | null>(null)

const menus: { key: MenuKey; label: string; icon: string }[] = [
  { key: 'overview', label: '总览', icon: 'M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z' },
  { key: 'boards', label: '板子', icon: 'M12 3 3 8l9 5 9-5-9-5zM3 13l9 5 9-5' },
  { key: 'roles', label: '角色图鉴', icon: 'M5 5h9v15H5zM14 7l5 1.4a1.6 1.6 0 0 1 1.1 2l-2.7 9.5' },
  { key: 'community', label: '社区治理', icon: 'M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z' },
  { key: 'ai', label: 'AI 助手', icon: 'M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5 18 18M6 18l2.5-2.5M15.5 8.5 18 6' },
]

async function loadSummary() {
  if (!authenticated.value) return
  try {
    summary.value = await api.getSummary()
  } catch (error) {
    // 只有未认证才退出登录；网络抖动、后端 5xx 不该把管理员踢出去
    if (isUnauthorizedError(error)) {
      logout()
      ElMessage.error('登录已失效，请重新登录')
    } else {
      ElMessage.error(error instanceof Error ? error.message : '后台连接失败，请稍后重试')
    }
  }
}

function handleLoggedIn(payload: LoginResponse) {
  authenticated.value = true
  adminName.value = payload.user?.openid || 'admin'
  localStorage.setItem(ADMIN_NAME_KEY, adminName.value)
  void loadSummary()
}

function logout() {
  clearStoredToken()
  authenticated.value = false
  summary.value = null
}

function handleUnauthorized() {
  if (!authenticated.value) return
  logout()
  ElMessage.warning('登录已失效，请重新登录')
}

onMounted(() => {
  window.addEventListener(UNAUTHORIZED_EVENT, handleUnauthorized)
  void loadSummary()
})

onBeforeUnmount(() => {
  window.removeEventListener(UNAUTHORIZED_EVENT, handleUnauthorized)
})
</script>

<template>
  <AdminLogin v-if="!authenticated" @logged-in="handleLoggedIn" />

  <div v-else class="shell">
    <aside class="side">
      <div class="brand">
        <span class="brand__mark" aria-hidden="true">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z" /></svg>
        </span>
        <span class="brand__text">
          <strong>WOLFBOOK</strong>
          <small>夜镇控制台</small>
        </span>
      </div>

      <nav class="side__nav" aria-label="主菜单">
        <a
          v-for="menu in menus"
          :key="menu.key"
          class="nt-nav"
          :class="{ on: route.menu === menu.key }"
          :href="`#/${menu.key}`"
          :aria-current="route.menu === menu.key ? 'page' : undefined"
        >
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="menu.icon" /></svg>
          {{ menu.label }}
          <span v-if="menu.key === 'community' && summary?.openReportCount" class="side__badge">{{ summary.openReportCount }}</span>
        </a>
      </nav>

      <div class="side__me">
        <span class="nt-token side__avatar" aria-hidden="true">{{ adminName.slice(0, 1).toUpperCase() }}</span>
        <span class="side__who">
          <strong>{{ adminName }}</strong>
          <small>管理员</small>
        </span>
        <button class="nt-btn nt-btn--ghost nt-btn--small side__logout" type="button" @click="logout">退出</button>
      </div>
    </aside>

    <main class="main">
      <OverviewPage v-if="route.menu === 'overview'" :summary="summary" @refresh="loadSummary" />
      <template v-else-if="route.menu === 'boards'">
        <BoardEditor
          v-if="route.boardEdit"
          :key="String(route.boardEdit)"
          :board-id="typeof route.boardEdit === 'number' ? route.boardEdit : null"
          @saved="loadSummary"
          @close="go('boards')"
        />
        <BoardList v-else @changed="loadSummary" />
      </template>
      <RoleManagement v-else-if="route.menu === 'roles'" @changed="loadSummary" />
      <CommunityManagement v-else-if="route.menu === 'community'" @changed="loadSummary" />
      <AiAssistantManagement v-else-if="route.menu === 'ai'" />
    </main>
  </div>
</template>

<style scoped>
.shell {
  min-height: 100vh;
  display: flex;
  flex-wrap: wrap;
  background: var(--bg);
}

.side {
  flex: 1 0 232px;
  max-width: 100%;
  padding: 28px 18px;
  background: var(--bar-bg);
  border-right: 2px solid var(--ink);
  display: flex;
  flex-direction: column;
  gap: 28px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 6px;
}

.brand__mark {
  width: 40px;
  height: 40px;
  flex: none;
  border-radius: 12px;
  background: var(--gold);
  color: var(--ink);
  border: 2px solid var(--ink);
  box-shadow: 3px 3px 0 var(--ink);
  display: flex;
  align-items: center;
  justify-content: center;
}

.brand__text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.brand__text strong {
  font: 900 18px var(--serif);
  letter-spacing: 0.08em;
}

.brand__text small {
  font-size: 12px;
  color: var(--text-3);
  letter-spacing: 0.1em;
}

.side__nav {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.nt-nav {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 46px;
  padding: 0 14px;
  border-radius: 14px;
  border: 2px solid transparent;
  color: var(--text-2);
  font: 600 15px var(--sans);
  text-decoration: none;
}

.nt-nav:hover {
  color: var(--text);
  background: var(--panel);
}

.nt-nav.on {
  background: var(--gold);
  color: var(--ink);
  border-color: var(--ink);
  box-shadow: 3px 3px 0 var(--ink);
}

.side__badge {
  margin-left: auto;
  min-width: 22px;
  height: 22px;
  padding: 0 6px;
  border-radius: 11px;
  background: var(--camp-wolf);
  color: var(--ink);
  font-size: 12px;
  font-weight: 900;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.nt-nav.on .side__badge {
  background: var(--ink);
  color: var(--gold);
}

.side__me {
  margin-top: auto;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px;
  border-radius: 14px;
  background: var(--panel);
  border: 1px solid var(--panel-line);
}

.side__avatar {
  width: 36px;
  height: 36px;
  background: var(--moon);
  color: var(--ink);
}

.side__who {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.side__who strong {
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.side__who small {
  font-size: 12px;
  color: var(--text-3);
}

.side__logout {
  margin-left: auto;
  height: 32px;
  padding: 0 10px;
  font-size: 13px;
}

.main {
  flex: 999 1 560px;
  min-width: 0;
  padding: 32px 40px 56px;
  display: flex;
  flex-direction: column;
  gap: 24px;
}

@media (min-width: 900px) {
  /* 宽屏时侧栏固定在左边，内容区单独滚动 */
  .shell {
    flex-wrap: nowrap;
    height: 100vh;
    overflow: hidden;
  }

  .side {
    flex: 0 0 232px;
    overflow-y: auto;
  }

  .main {
    overflow-y: auto;
  }
}

@media (max-width: 720px) {
  .side {
    padding: 18px 16px;
    gap: 16px;
  }

  .side__nav {
    flex-direction: row;
    flex-wrap: wrap;
  }

  .main {
    padding: 22px 16px 40px;
  }
}
</style>
