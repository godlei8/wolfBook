import storage from '../services/storage'

const USER_TAB_URL = '/pages/user/index'

export function getAuthToken() {
  return storage.getAuthToken() || ''
}

export function hasAuthToken() {
  return !!getAuthToken()
}

export function goToUserTab(delay = 0) {
  const open = () => {
    uni.switchTab({ url: USER_TAB_URL })
  }

  if (delay > 0) {
    setTimeout(open, delay)
    return
  }

  open()
}

export function requireAuth(options = {}) {
  const {
    title = '请先登录',
    redirect = false,
    delay = 0,
  } = options

  if (hasAuthToken()) {
    return true
  }

  if (title) {
    uni.showToast({ title, icon: 'none' })
  }

  if (redirect) {
    goToUserTab(delay)
  }

  return false
}
