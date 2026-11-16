const fallbackBaseUrl = import.meta.env.PROD
  ? 'https://wolfbook.godlei8.top'
  : 'http://127.0.0.1:8088'

export const BASE_URL = (import.meta.env.VITE_API_BASE_URL || fallbackBaseUrl).replace(/\/$/, '')

// 夜晚背景音乐的地址（房主 / 法官手机在夜里放）。音乐不打进小程序包，放在 COS 上；没配就没有这个功能
export const NIGHT_BGM_URL = (import.meta.env.VITE_NIGHT_BGM_URL || '').trim()
