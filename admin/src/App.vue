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
import type { DashboardSummary, LoginResponse } from './types'

const activeMenu = ref<'overview' | 'boards' | 'roles' | 'community' | 'ai'>('overview')
const authenticated = ref(Boolean(getStoredToken()))
const summary = ref<DashboardSummary | null>(null)
const loadingSummary = ref(false)
const mountedMenus = reactive({
  overview: true,
  boards: false,
  roles: false,
  community: false,
  ai: false,
})

const pageMeta = computed(() => {
  if (activeMenu.value === 'boards') {
    return {
      title: '板子管理',
      description: '维护封面、阵容、规则、FAQ 与卡片摘要。',
    }
  }
  if (activeMenu.value === 'roles') {
    return {
      title: '角色管理',
      description: '统一维护角色技能、阵营、FAQ 与插画资源。',
    }
  }
  if (activeMenu.value === 'community') {
    return {
      title: '社区治理',
      description: '处理帖子、评论、举报与内容状态，维持社区秩序。',
    }
  }
  if (activeMenu.value === 'ai') {
    return {
      title: 'AI 助手',
      description: '管理知识库、发布版本、联网搜索与问答日志，给站内狼人杀 AI 助手提供运营控制台。',
    }
  }
  return {
    title: '运营总览',
    description: '围绕板子、角色、社区与 AI 助手查看整体运行状态。',
  }
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
    ElMessage.error(error instanceof Error ? error.message : '后台连接失败，请重新登录')
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
        <div class="brand-sub">狼人杀内容运营、知识库治理与 AI 助手控制台</div>
      </div>

      <nav class="menu-stack">
        <button class="menu-item" :class="{ active: activeMenu === 'overview' }" @click="activeMenu = 'overview'">
          <span class="menu-label">总览</span>
          <span class="menu-hint">Overview</span>
        </button>
        <button class="menu-item" :class="{ active: activeMenu === 'boards' }" @click="activeMenu = 'boards'">
          <span class="menu-label">板子管理</span>
          <span class="menu-hint">Boards</span>
        </button>
        <button class="menu-item" :class="{ active: activeMenu === 'roles' }" @click="activeMenu = 'roles'">
          <span class="menu-label">角色管理</span>
          <span class="menu-hint">Roles</span>
        </button>
        <button class="menu-item" :class="{ active: activeMenu === 'community' }" @click="activeMenu = 'community'">
          <span class="menu-label">社区治理</span>
          <span class="menu-hint">Community</span>
        </button>
        <button class="menu-item" :class="{ active: activeMenu === 'ai' }" @click="activeMenu = 'ai'">
          <span class="menu-label">AI 助手</span>
          <span class="menu-hint">Assistant</span>
        </button>
      </nav>

      <div class="nav-bottom">
        <button class="ghost-action" @click="loadSummary">刷新摘要</button>
        <el-button class="logout-button" type="primary" @click="logout">退出登录</el-button>
      </div>
    </aside>

    <main class="main-panel" :class="{ 'main-panel--ai': activeMenu === 'ai' }" v-loading="loadingSummary">
      <header class="page-header" :class="{ 'page-header--static': activeMenu === 'ai' }">
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
                  当前后台已经接入真实后端接口，管理动作会直接回写数据库。建议联调顺序为：先启动
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
  height: 100vh;
  display: grid;
  grid-template-columns: 272px 1fr;
  overflow: hidden;
}

.side-nav {
  height: 100vh;
  padding: 20px 18px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  border-right: 1px solid rgba(255, 255, 255, 0.06);
  background: linear-gradient(180deg, rgba(14, 14, 14, 0.98), rgba(4, 4, 4, 0.98));
  overflow: hidden;
}

.nav-top {
  display: grid;
  gap: 8px;
}

.brand-mark {
  display: inline-flex;
  width: fit-content;
  padding: 5px 10px;
  background: rgba(255, 192, 0, 0.12);
  color: #ffc000;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.18em;
}

.brand-title {
  font-size: 22px;
  font-weight: 700;
  letter-spacing: 0.06em;
}

.brand-sub {
  color: #8d8d8d;
  font-size: 13px;
  line-height: 1.6;
}

.menu-stack {
  display: grid;
  gap: 10px;
  margin: 24px 0 auto;
}

.menu-item {
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 12px;
  text-align: left;
  background: rgba(255, 255, 255, 0.02);
  color: #ffffff;
  cursor: pointer;
}

.menu-item.active {
  border-color: rgba(255, 192, 0, 0.36);
  background: linear-gradient(180deg, rgba(255, 192, 0, 0.12), rgba(255, 255, 255, 0.02));
}

.menu-label {
  font-size: 15px;
  font-weight: 700;
}

.menu-hint {
  color: #8d8d8d;
  font-size: 11px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.nav-bottom {
  display: grid;
  gap: 10px;
}

.ghost-action {
  display: none;
}

.logout-button {
  width: 100%;
  font-weight: 700;
}

.main-panel {
  --panel-sticky-offset: 108px;
  min-width: 0;
  height: 100vh;
  padding: 0 24px 24px;
  overflow-y: auto;
  overflow-x: auto;
  scrollbar-gutter: stable both-edges;
  scrollbar-width: thin;
  scrollbar-color: rgba(255, 192, 0, 0.4) rgba(255, 255, 255, 0.04);
  -ms-overflow-style: auto;
}

.main-panel::-webkit-scrollbar {
  width: 10px;
  height: 10px;
}

.main-panel::-webkit-scrollbar-track {
  background: rgba(255, 255, 255, 0.04);
}

.main-panel::-webkit-scrollbar-thumb {
  border-radius: 999px;
  border: 2px solid rgba(10, 10, 10, 0.96);
  background: linear-gradient(180deg, rgba(255, 211, 92, 0.72), rgba(182, 126, 12, 0.72));
}

.main-panel::-webkit-scrollbar-corner {
  background: rgba(255, 255, 255, 0.04);
}

.page-header {
  display: grid;
  gap: 6px;
  position: sticky;
  top: 0;
  z-index: 12;
  margin: 0 -24px 0;
  padding: 14px 24px 16px;
  background:
    linear-gradient(180deg, rgba(7, 7, 7, 0.98), rgba(7, 7, 7, 0.95) 68%, rgba(7, 7, 7, 0.8) 100%);
  backdrop-filter: blur(14px);
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.eyebrow {
  color: #ffc000;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.2em;
  text-transform: uppercase;
}

.page-header h1 {
  margin: 0;
  font-size: 34px;
  line-height: 1.02;
}

.page-header p {
  margin: 0;
  max-width: 680px;
  color: #8d8d8d;
  line-height: 1.55;
}

.content-stack {
  margin-top: 12px;
  display: grid;
  gap: 16px;
  min-width: 0;
}

.content-stack--tight {
  margin-top: 12px;
}

.panel-view {
  min-width: 0;
}

.welcome-card {
  border-radius: 18px;
}

.welcome-grid {
  display: grid;
  grid-template-columns: 1.3fr 0.9fr;
  gap: 18px;
}

.welcome-eyebrow {
  color: #ffc000;
  font-size: 11px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
}

.welcome-grid h3 {
  margin: 10px 0 8px;
  font-size: 24px;
}

.welcome-grid p {
  margin: 0;
  color: #b5b5b5;
  line-height: 1.7;
}

.welcome-grid code {
  padding: 1px 6px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.06);
}

.welcome-panel {
  display: grid;
  gap: 10px;
}

.welcome-item {
  padding: 14px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.03);
  display: grid;
  gap: 6px;
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

.main-panel--ai {
  --panel-sticky-offset: 0px;
}

:deep(.el-tag) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 32px;
  padding: 0 14px;
  border-radius: 12px;
  border: 1px solid rgba(255, 192, 0, 0.14);
  background:
    radial-gradient(circle at top left, rgba(255, 208, 96, 0.08), transparent 58%),
    linear-gradient(180deg, rgba(39, 34, 22, 0.96), rgba(24, 20, 14, 0.98));
  color: #e7d4a2;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.04em;
  line-height: 1;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.05),
    0 10px 18px rgba(0, 0, 0, 0.14);
}

:deep(.el-tag .el-tag__content) {
  line-height: 1;
}

:deep(.el-tag.el-tag--warning) {
  border-color: rgba(255, 192, 0, 0.26);
  background:
    radial-gradient(circle at top left, rgba(255, 212, 90, 0.18), transparent 55%),
    linear-gradient(180deg, rgba(70, 53, 14, 0.94), rgba(38, 29, 8, 0.98));
  color: #f3cc71;
}

:deep(.el-tag.el-tag--danger) {
  border-color: rgba(255, 98, 84, 0.26);
  background:
    radial-gradient(circle at top left, rgba(255, 130, 112, 0.14), transparent 54%),
    linear-gradient(180deg, rgba(68, 20, 20, 0.94), rgba(39, 11, 11, 0.98));
  color: #ff8f7e;
}

:deep(.el-tag.el-tag--info) {
  border-color: rgba(134, 144, 166, 0.22);
  background:
    radial-gradient(circle at top left, rgba(149, 163, 193, 0.12), transparent 58%),
    linear-gradient(180deg, rgba(33, 36, 43, 0.96), rgba(19, 21, 27, 0.98));
  color: #c4cad6;
}

:deep(.el-tag.el-tag--success) {
  border-color: rgba(84, 191, 130, 0.22);
  background:
    radial-gradient(circle at top left, rgba(120, 222, 158, 0.12), transparent 54%),
    linear-gradient(180deg, rgba(21, 51, 36, 0.94), rgba(11, 29, 20, 0.98));
  color: #9fe0b6;
}

@media (max-width: 1140px) {
  .app-shell {
    height: auto;
    grid-template-columns: 1fr;
    overflow: visible;
  }

  .side-nav {
    height: auto;
    overflow: visible;
  }

  .main-panel {
    --panel-sticky-offset: 0px;
    height: auto;
    overflow: visible;
  }

  .welcome-grid {
    grid-template-columns: 1fr;
  }

.page-header {
    margin: 0;
    padding: 0;
    position: static;
    background: transparent;
    backdrop-filter: none;
    border-bottom: none;
  }
}

.page-header--static {
  position: static;
  top: auto;
  z-index: auto;
}
</style>
