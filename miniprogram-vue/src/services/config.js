const fallbackBaseUrl = import.meta.env.PROD
  ? 'https://wolfbook.godlei8.top'
  : 'http://127.0.0.1:8088'

export const BASE_URL = (import.meta.env.VITE_API_BASE_URL || fallbackBaseUrl).replace(/\/$/, '')
