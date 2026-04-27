<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import AdminLogin from './components/AdminLogin.vue'
import OverviewPanel from './components/OverviewPanel.vue'
import BoardManagement from './components/BoardManagement.vue'
import RoleManagement from './components/RoleManagement.vue'
import CommunityManagement from './components/CommunityManagement.vue'
import AiAssistantManagement from './components/AiAssistantManagement.vue'
import { api, clearStoredToken, getStoredToken } from './services/api'
import { resolveErrorMessage } from './utils/errors'
import type { DashboardSummary, LoginResponse } from './types'

type MenuKey = 'overview' | 'boards' | 'roles' | 'community' | 'ai'

const activeMenu = ref<MenuKey>('overview')
const authenticated = ref(Boolean(getStoredToken()))
const summary = ref<DashboardSummary | null>(null)
const loadingSummary = ref(false)
const mountedMenus = reactive<Record<MenuKey, boolean>>({
  overview: true,
  boards: false,
  roles: false,
  community: false,
  ai: false,
})

const menuItems: Array<{ key: MenuKey; label: string; hint: string }> = [
  { key: 'overview', label: '总览', hint: 'Overview' },
  { key: 'boards', label: '板子管理', hint: 'Boards' },
  { key: 'roles', label: '角色管理', hint: 'Roles' },
  { key: 'community', label: '社区治理', hint: 'Community' },
  { key: 'ai', label: 'AI 助手', hint: 'Assistant' },
]

const pageMeta = computed(() => {
  const meta: Record<MenuKey, { title: string; description: string }> = {
    overview: {
      title: '运营总览',
      description: '围绕板子、角色与社区查看整体运行状态。',
    },
    boards: {
      title: '板子管理',
      description: '维护封面、阵容、规则、FAQ 与卡片摘要。',
    },
    roles: {
      title: '角色管理',
      description: '统一维护角色技能、阵营、FAQ 与插画资源。',
    },
    community: {
      title: '社区治理',
      description: '处理帖子、评论、举报与内容状态，维持社区秩序。',
    },
    ai: {
      title: 'AI 助手管理',
      description: '配置知识库、上传发布文档、调试检索并跟踪问答质量。',
    },
  }
  return meta[activeMenu.value]
})

async function loadSummary() {
  if (!authenticated.value) {
    return
  }
  loadingSummary.value = true
  try {
    summary.value = await api.getSummary()
  } catch (error) {
    clearStoredToken()
    authenticated.value = false
    summary.value = null
    ElMessage.error(resolveErrorMessage(error, '后台连接失败，请重新登录'))
  } finally {
    loadingSummary.value = false
  }
}

function handleLoggedIn(_payload: LoginResponse) {
  authenticated.value = true
  void loadSummary()
}

function logout() {
  clearStoredToken()
  authenticated.value = false
  summary.value = null
}

watch(
  activeMenu,
  (menu) => {
    mountedMenus[menu] = true
  },
  { immediate: true },
)

onMounted(() => {
  void loadSummary()
})
</script>

<template>
  <AdminLogin v-if="!authenticated" @loggedIn="handleLoggedIn" />

  <div v-else class="app-shell">
    <aside class="side-nav">
      <div class="nav-top">
        <div class="brand-mark">WOLFBOOK</div>
        <div class="brand-title">OPERATIONS CONSOLE</div>
        <div class="brand-sub">狼人杀内容运营、板子角色与社区治理控制台</div>
      </div>

      <nav class="menu-stack">
        <button
          v-for="item in menuItems"
          :key="item.key"
          class="menu-item"
          :class="{ active: activeMenu === item.key }"
          @click="activeMenu = item.key"
        >
          <span class="menu-label">{{ item.label }}</span>
          <span class="menu-hint">{{ item.hint }}</span>
        </button>
      </nav>

      <div class="nav-bottom">
        <button class="ghost-action" @click="loadSummary">刷新摘要</button>
        <el-button class="logout-button" type="primary" @click="logout">退出登录</el-button>
      </div>
    </aside>

    <main class="main-panel" v-loading="loadingSummary">
      <header class="page-header">
        <div class="eyebrow">BLACK GOLD CONTROL ROOM</div>
        <h1>{{ pageMeta.title }}</h1>
        <p>{{ pageMeta.description }}</p>
      </header>

      <section v-show="activeMenu === 'overview'" class="panel-view">
        <OverviewPanel v-show="mountedMenus.overview" :summary="summary" />

        <section class="content-stack">
          <el-card class="welcome-card">
            <div class="welcome-grid">
              <div>
                <div class="welcome-eyebrow">Deployment</div>
                <h3>三端闭环已经成型</h3>
                <p>
                  当前后台已接入真实后端接口，管理动作会直接回写数据库。建议联调顺序为：先启动
                  <code>backend</code>，再启动 <code>admin</code>，最后在微信开发者工具中加载
                  <code>miniprogram-vue/dist/build/mp-weixin</code>。
                </p>
              </div>
              <div class="welcome-panel">
                <div class="welcome-item">
                  <span>管理员账号</span>
                  <strong>已初始化，请使用独立密码登录</strong>
                </div>
                <div class="welcome-item">
                  <span>主视觉</span>
                  <strong>#000000 / #FFC000</strong>
                </div>
                <div class="welcome-item">
                  <span>数据事实源</span>
                  <strong>MySQL + MyBatis-Plus</strong>
                </div>
              </div>
            </div>
          </el-card>
        </section>
      </section>

      <section v-show="activeMenu === 'boards'" class="content-stack content-stack--tight panel-view">
        <BoardManagement v-if="mountedMenus.boards" @changed="loadSummary" />
      </section>

      <section v-show="activeMenu === 'roles'" class="content-stack content-stack--tight panel-view">
        <RoleManagement v-if="mountedMenus.roles" @changed="loadSummary" />
      </section>

      <section v-show="activeMenu === 'community'" class="content-stack content-stack--tight panel-view">
        <CommunityManagement v-if="mountedMenus.community" @changed="loadSummary" />
      </section>

      <section v-show="activeMenu === 'ai'" class="content-stack content-stack--tight panel-view">
        <AiAssistantManagement v-if="mountedMenus.ai" />
      </section>
    </main>
  </div>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 272px minmax(0, 1fr);
  background:
    radial-gradient(circle at top left, rgba(255, 192, 0, 0.12), transparent 34%),
    linear-gradient(135deg, #070707 0%, #111111 46%, #050505 100%);
  color: #f7f0dd;
}

.side-nav {
  height: 100vh;
  position: sticky;
  top: 0;
  padding: 22px 18px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  border-right: 1px solid rgba(255, 255, 255, 0.08);
  background: linear-gradient(180deg, rgba(14, 14, 14, 0.98), rgba(4, 4, 4, 0.98));
  box-shadow: 18px 0 42px rgba(0, 0, 0, 0.22);
}

.nav-top {
  display: grid;
  gap: 8px;
}

.brand-mark {
  width: fit-content;
  padding: 5px 10px;
  border-radius: 999px;
  background: rgba(255, 192, 0, 0.12);
  color: #ffc000;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.18em;
}

.brand-title {
  font-size: 22px;
  font-weight: 800;
  letter-spacing: 0.06em;
}

.brand-sub {
  max-width: 210px;
  color: #999;
  font-size: 13px;
  line-height: 1.7;
}

.menu-stack {
  display: grid;
  gap: 12px;
  margin: 32px 0 auto;
}

.menu-item {
  width: 100%;
  padding: 16px;
  display: grid;
  gap: 4px;
  text-align: left;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.035);
  color: #f5f1e6;
  cursor: pointer;
  transition: 0.18s ease;
}

.menu-item:hover,
.menu-item.active {
  border-color: rgba(255, 192, 0, 0.42);
  background: linear-gradient(135deg, rgba(255, 192, 0, 0.18), rgba(255, 255, 255, 0.04));
  transform: translateX(4px);
}

.menu-label {
  font-size: 16px;
  font-weight: 800;
}

.menu-hint {
  color: #a8a8a8;
  font-size: 11px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.nav-bottom {
  display: grid;
  gap: 12px;
}

.ghost-action,
.logout-button {
  width: 100%;
  min-height: 42px;
  border-radius: 14px;
  font-weight: 800;
}

.ghost-action {
  border: 1px solid rgba(255, 192, 0, 0.25);
  background: rgba(255, 192, 0, 0.08);
  color: #f3cc71;
  cursor: pointer;
}

.main-panel {
  --panel-gutter: 34px;
  min-width: 0;
  position: relative;
  height: 100vh;
  overflow: auto;
  padding: 0 var(--panel-gutter) 40px;
  scroll-padding-top: 160px;
}

.page-header {
  position: sticky;
  top: 0;
  z-index: 8;
  isolation: isolate;
  margin: 0 calc(var(--panel-gutter) * -1) 18px;
  padding: 18px var(--panel-gutter) 28px;
  background:
    radial-gradient(circle at top left, rgba(255, 192, 0, 0.12), transparent 38%),
    linear-gradient(180deg, rgba(7, 7, 7, 0.99) 0%, rgba(9, 9, 9, 0.97) 56%, rgba(9, 9, 9, 0.94) 100%);
  backdrop-filter: blur(16px) saturate(120%);
}

.eyebrow,
.welcome-eyebrow {
  color: #ffc000;
  font-size: 12px;
  font-weight: 900;
  letter-spacing: 0.22em;
  text-transform: uppercase;
}

.page-header h1 {
  margin: 10px 0 8px;
  font-size: clamp(34px, 4vw, 58px);
  line-height: 0.96;
  letter-spacing: -0.03em;
}

.page-header p {
  max-width: 760px;
  margin: 0;
  color: #aaa;
  font-size: 15px;
  line-height: 1.8;
}

.page-header::before {
  content: '';
  position: absolute;
  left: var(--panel-gutter);
  right: var(--panel-gutter);
  bottom: 10px;
  height: 1px;
  background: linear-gradient(90deg, rgba(255, 192, 0, 0.32), rgba(255, 255, 255, 0.08) 46%, transparent 100%);
  opacity: 0.7;
}

.page-header::after {
  content: '';
  position: absolute;
  left: calc(var(--panel-gutter) - 6px);
  right: calc(var(--panel-gutter) - 6px);
  bottom: -8px;
  height: 26px;
  border-radius: 999px;
  background: linear-gradient(180deg, rgba(255, 214, 94, 0.12), rgba(9, 9, 9, 0));
  filter: blur(14px);
  pointer-events: none;
  z-index: -1;
}

.panel-view,
.content-stack {
  display: grid;
  gap: 18px;
}

.panel-view {
  padding-top: 8px;
}

.content-stack--tight {
  gap: 16px;
}

.welcome-card {
  border-radius: 24px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  background:
    radial-gradient(circle at top right, rgba(255, 192, 0, 0.1), transparent 38%),
    rgba(14, 14, 14, 0.88);
}

.welcome-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: 24px;
  align-items: stretch;
}

.welcome-grid h3 {
  margin: 12px 0;
  font-size: 24px;
}

.welcome-grid p {
  margin: 0;
  color: #b6b6b6;
  line-height: 1.9;
}

.welcome-grid code {
  color: #ffc000;
}

.welcome-panel {
  display: grid;
  gap: 12px;
}

.welcome-item {
  padding: 16px;
  display: grid;
  gap: 8px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.05);
}

.welcome-item span {
  color: #8d8d8d;
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.12em;
}

.welcome-item strong {
  font-size: 17px;
}

@media (max-width: 1140px) {
  .app-shell {
    grid-template-columns: 1fr;
  }

  .side-nav {
    height: auto;
    position: static;
  }

  .main-panel {
    --panel-gutter: 18px;
    height: auto;
    overflow: visible;
    padding: 0 var(--panel-gutter) 24px;
  }

  .page-header {
    position: static;
    margin: 0 0 18px;
    padding: 18px 0 12px;
    background: transparent;
    backdrop-filter: none;
  }

  .page-header::before,
  .page-header::after {
    display: none;
  }

  .welcome-grid {
    grid-template-columns: 1fr;
  }
}
</style>
