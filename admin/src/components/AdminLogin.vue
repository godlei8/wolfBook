<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, setStoredToken } from '../services/api'
import type { LoginResponse } from '../types'

const emit = defineEmits<{
  loggedIn: [payload: LoginResponse]
}>()

const loading = ref(false)
const form = reactive({
  username: 'admin',
  password: '',
})

async function submit() {
  loading.value = true
  try {
    const result = await api.adminLogin(form.username, form.password)
    setStoredToken(result.token)
    emit('loggedIn', result)
    ElMessage.success('登录成功')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-shell">
    <div class="login-hero">
      <div class="hero-visual" aria-hidden="true">
        <svg viewBox="0 0 520 520" class="wolf-crest" role="presentation">
          <defs>
            <linearGradient id="wolfGold" x1="0%" y1="0%" x2="100%" y2="100%">
              <stop offset="0%" stop-color="#ffe287" />
              <stop offset="45%" stop-color="#ffc000" />
              <stop offset="100%" stop-color="#8f5d00" />
            </linearGradient>
            <radialGradient id="wolfGlow" cx="50%" cy="38%" r="52%">
              <stop offset="0%" stop-color="#ffda6a" stop-opacity="0.32" />
              <stop offset="100%" stop-color="#ffda6a" stop-opacity="0" />
            </radialGradient>
          </defs>

          <circle cx="260" cy="220" r="160" fill="url(#wolfGlow)" />
          <path
            d="M150 120 220 182 248 154 274 186 370 124 332 238 376 286 314 320 286 404 260 356 232 404 204 320 142 286 186 238Z"
            fill="rgba(255, 192, 0, 0.04)"
            stroke="url(#wolfGold)"
            stroke-width="10"
            stroke-linejoin="round"
          />
          <path
            d="M205 225 236 256M315 225 284 256M220 309 260 338 300 309"
            fill="none"
            stroke="url(#wolfGold)"
            stroke-width="10"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
          <path
            d="M203 188 188 142M317 188 332 142"
            fill="none"
            stroke="url(#wolfGold)"
            stroke-width="8"
            stroke-linecap="round"
          />
          <circle cx="224" cy="234" r="8" fill="#ffc000" />
          <circle cx="296" cy="234" r="8" fill="#ffc000" />
        </svg>
      </div>

      <div class="hero-copy">
        <div class="eyebrow">WOLFBOOK ADMIN</div>
        <h1>BLACK GOLD CONTROL</h1>
        <p>管理板子资料、角色设定、社区内容与 AI 狼人顾问，保持整套运营控制台统一运转。</p>
      </div>
    </div>

    <div class="login-panel">
      <div class="panel-head">
        <div class="panel-kicker">SIGN IN</div>
        <div class="panel-title">管理员登录</div>
        <div class="panel-sub">输入当前管理员账号与密码后进入后台，不再在界面上展示默认口令。</div>
      </div>

      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="请输入管理员账号" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" show-password placeholder="请输入登录密码" />
        </el-form-item>
        <el-button class="login-button" type="primary" :loading="loading" @click="submit">
          登录后台
        </el-button>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.login-shell {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 1.1fr 460px;
  gap: 24px;
  align-items: stretch;
  padding: 32px;
}

.login-hero,
.login-panel {
  border: 1px solid rgba(255, 255, 255, 0.08);
  background: linear-gradient(180deg, rgba(17, 17, 17, 0.98), rgba(4, 4, 4, 0.98));
}

.login-hero {
  position: relative;
  overflow: hidden;
  padding: 40px 48px 48px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  min-height: 520px;
  background:
    linear-gradient(180deg, rgba(0, 0, 0, 0.14), rgba(0, 0, 0, 0.84)),
    radial-gradient(circle at top right, rgba(255, 192, 0, 0.14), transparent 24%),
    linear-gradient(180deg, #121212 0%, #050505 100%);
}

.hero-visual {
  display: flex;
  justify-content: center;
  align-items: flex-start;
  pointer-events: none;
}

.wolf-crest {
  width: min(100%, 430px);
  filter:
    drop-shadow(0 0 28px rgba(255, 192, 0, 0.14))
    drop-shadow(0 24px 44px rgba(0, 0, 0, 0.46));
}

.hero-copy {
  display: grid;
  gap: 0;
}

.eyebrow,
.panel-kicker {
  color: #ffc000;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.22em;
  text-transform: uppercase;
}

.login-hero h1 {
  margin: 18px 0 12px;
  font-size: 64px;
  line-height: 0.94;
  max-width: 520px;
}

.login-hero p {
  margin: 0;
  max-width: 520px;
  color: #9e9e9e;
  line-height: 1.8;
}

.login-panel {
  padding: 32px;
  display: grid;
  align-content: center;
}

.panel-head {
  margin-bottom: 18px;
}

.panel-title {
  margin-top: 12px;
  font-size: 34px;
  font-weight: 700;
}

.panel-sub {
  margin-top: 10px;
  color: #8d8d8d;
  line-height: 1.7;
}

.login-button {
  width: 100%;
  margin-top: 8px;
  font-weight: 700;
}

@media (max-width: 980px) {
  .login-shell {
    grid-template-columns: 1fr;
  }

  .login-hero {
    min-height: 320px;
    padding: 32px;
  }

  .login-hero h1 {
    font-size: 48px;
  }

  .hero-visual {
    justify-content: flex-start;
  }

  .wolf-crest {
    width: min(100%, 280px);
  }
}
</style>
