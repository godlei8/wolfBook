const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080').replace(/\/+$/, '')

export function resolveMediaUrl(url?: string | null) {
  const value = (url || '').trim()
  if (!value) {
    return ''
  }
  if (/^(https?:)?\/\//i.test(value)) {
    return value.startsWith('//') ? `http:${value}` : value
  }
  if (value.startsWith('/')) {
    return `${apiBaseUrl}${value}`
  }
  return value
}
