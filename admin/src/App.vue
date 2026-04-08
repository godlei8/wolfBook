<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import AdminLogin from './components/AdminLogin.vue'
import OverviewPanel from './components/OverviewPanel.vue'
import BoardManagement from './components/BoardManagement.vue'
import RoleManagement from './components/RoleManagement.vue'
import CommunityManagement from './components/CommunityManagement.vue'
import { api, clearStoredToken, getStoredToken } from './services/api'
import type { DashboardSummary, LoginResponse } from './types'

const activeMenu = ref<'overview' | 'boards' | 'roles' | 'community'>('overview')
const authenticated = ref(Boolean(getStoredToken()))
const summary = ref<DashboardSummary | null>(null)
const loadingSummary = ref(false)

const pageMeta = computed(() => ({
  title: '运营总览',
  description: '围绕板子、角色、社区三条主线查看整体运行状态。',
}))

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
        <div class="brand-sub">狼人杀内容运营与数据治理后台</div>
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
        <button
          class="menu-item"
          :class="{ active: activeMenu === 'community' }"
          @click="activeMenu = 'community'"
        >
          <span class="menu-label">社区治理</span>
          <span class="menu-hint">Community</span>
        </button>
      </nav>

      <div class="nav-bottom">
        <button class="ghost-action" @click="loadSummary">刷新摘要</button>
        <el-button class="logout-button" type="primary" @click="logout">退出登录</el-button>
      </div>
    </aside>

    <main class="main-panel" v-loading="loadingSummary">
      <template v-if="activeMenu === 'overview'">
        <header class="page-header">
          <div class="eyebrow">BLACK GOLD CONTROL ROOM</div>
          <h1>{{ pageMeta.title }}</h1>
          <p>{{ pageMeta.description }}</p>
        </header>

        <OverviewPanel :summary="summary" />
      </template>

      <section class="content-stack" :class="{ 'content-stack--tight': activeMenu !== 'overview' }">
        <el-card v-if="activeMenu === 'overview'" class="welcome-card">
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
                <strong>admin / wolf123</strong>
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
        <BoardManagement v-if="activeMenu === 'boards'" @changed="loadSummary" />
        <RoleManagement v-if="activeMenu === 'roles'" @changed="loadSummary" />
        <CommunityManagement v-if="activeMenu === 'community'" @changed="loadSummary" />
      </section>
    </main>
  </div>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 272px 1fr;
}

.side-nav {
  position: sticky;
  top: 0;
  min-height: 100vh;
  padding: 20px 18px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  border-right: 1px solid rgba(255, 255, 255, 0.06);
  background: linear-gradient(180deg, rgba(14, 14, 14, 0.98), rgba(4, 4, 4, 0.98));
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
  height: 40px;
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 10px;
  background: transparent;
  color: #ffffff;
  cursor: pointer;
}

.logout-button {
  width: 100%;
  font-weight: 700;
}

.main-panel {
  padding: 24px;
}

.page-header {
  display: grid;
  gap: 6px;
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
  max-width: 640px;
  color: #8d8d8d;
  line-height: 1.55;
}

.content-stack {
  margin-top: 16px;
  display: grid;
  gap: 16px;
}

.content-stack--tight {
  margin-top: 0;
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

@media (max-width: 1140px) {
  .app-shell {
    grid-template-columns: 1fr;
  }

  .side-nav {
    position: static;
    min-height: auto;
  }

  .welcome-grid {
    grid-template-columns: 1fr;
  }
}
</style>
