<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, setStoredToken } from '../services/api'
import type { LoginResponse } from '../types'
import moonScene from '../assets/scenes/moon.webp'

const emit = defineEmits<{
  loggedIn: [payload: LoginResponse]
}>()

const loading = ref(false)
const form = reactive({
  username: 'admin',
  password: '',
})

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请填写账号和密码')
    return
  }
  loading.value = true
  try {
    const result = await api.adminLogin(form.username, form.password)
    setStoredToken(result.token)
    emit('loggedIn', result)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login">
    <img class="login__bg" :src="moonScene" alt="" />
    <div class="login__shade"></div>

    <form class="login__card" aria-labelledby="login-title" @submit.prevent="submit">
      <div class="login__head">
        <span class="login__mark" aria-hidden="true">
          <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z" /></svg>
        </span>
        <h1 id="login-title">WOLFBOOK</h1>
        <p>夜镇控制台 · 管理员登录</p>
      </div>

      <div class="nt-field">
        <label class="nt-label" for="login-user">账号</label>
        <input id="login-user" v-model.trim="form.username" class="nt-input" autocomplete="username" placeholder="管理员账号" />
      </div>
      <div class="nt-field">
        <label class="nt-label" for="login-pass">密码</label>
        <input id="login-pass" v-model="form.password" class="nt-input" type="password" autocomplete="current-password" placeholder="密码" />
      </div>

      <button class="nt-btn nt-btn--gold login__submit" type="submit" :disabled="loading">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z" /></svg>
        {{ loading ? '进门中…' : '进入夜镇' }}
      </button>
      <p class="login__foot">登录 30 天内有效，换浏览器需要重新登录</p>
    </form>
  </div>
</template>

<style scoped>
.login {
  position: relative;
  min-height: 100vh;
  padding: 40px 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: var(--bg);
}

.login__bg {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: 50% 30%;
}

.login__shade {
  position: absolute;
  inset: 0;
  background: radial-gradient(ellipse at 50% 45%, rgba(21, 18, 28, 0.55) 0%, rgba(21, 18, 28, 0.92) 70%);
}

.login__card {
  position: relative;
  width: 420px;
  max-width: 100%;
  padding: 36px 34px 30px;
  border-radius: 28px;
  background: var(--panel);
  border: 3px solid var(--ink);
  box-shadow: 8px 8px 0 var(--ink);
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.login__head {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  text-align: center;
}

.login__mark {
  width: 60px;
  height: 60px;
  border-radius: 18px;
  background: var(--gold);
  color: var(--ink);
  border: 3px solid var(--ink);
  box-shadow: 4px 4px 0 var(--ink);
  display: flex;
  align-items: center;
  justify-content: center;
}

.login__head h1 {
  margin: 0;
  font: 900 30px var(--serif);
  letter-spacing: 0.1em;
}

.login__head p {
  margin: 0;
  font-size: 15px;
  color: var(--text-2);
}

.login .nt-input {
  height: 50px;
  font-size: 16px;
}

.login__submit {
  height: 52px;
  font-size: 17px;
  font-weight: 900;
  border-radius: 16px;
}

.login__foot {
  margin: 0;
  text-align: center;
  font-size: 13px;
  color: var(--text-3);
}
</style>
