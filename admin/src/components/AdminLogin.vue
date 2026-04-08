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
  password: 'wolf123',
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
      <div class="eyebrow">WOLFBOOK ADMIN</div>
      <h1>BLACK GOLD CONTROL</h1>
      <p>管理板子资料、角色设定、社区内容与举报处理。</p>
    </div>

    <div class="login-panel">
      <div class="panel-head">
        <div class="panel-kicker">SIGN IN</div>
        <div class="panel-title">管理员登录</div>
        <div class="panel-sub">默认账号已预置，可直接进入联调流程。</div>
      </div>

      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="请输入管理员账号" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" show-password placeholder="请输入密码" />
        </el-form-item>
        <el-button class="login-button" type="primary" :loading="loading" @click="submit">
          登录后台
        </el-button>
      </el-form>

      <div class="login-tip">默认账号：`admin` / `wolf123`</div>
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
  padding: 48px;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  min-height: 520px;
  background:
    linear-gradient(180deg, rgba(0, 0, 0, 0.16), rgba(0, 0, 0, 0.8)),
    radial-gradient(circle at top right, rgba(255, 192, 0, 0.14), transparent 22%),
    linear-gradient(180deg, #121212 0%, #050505 100%);
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

.login-tip {
  margin-top: 18px;
  color: #8d8d8d;
  font-size: 13px;
}

@media (max-width: 980px) {
  .login-shell {
    grid-template-columns: 1fr;
  }

  .login-hero {
    min-height: 320px;
  }

  .login-hero h1 {
    font-size: 48px;
  }
}
</style>
