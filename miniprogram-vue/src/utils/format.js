function pad(value) {
  return String(value).padStart(2, '0')
}

export function formatDateTime(input) {
  if (!input) return ''
  const date = new Date(input)
  if (Number.isNaN(date.getTime())) return input
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export function fromNow(input) {
  if (!input) return ''
  const timestamp = new Date(input).getTime()
  const diff = Date.now() - timestamp
  const hour = 3600 * 1000
  const day = 24 * hour
  if (diff < hour) {
    return `${Math.max(1, Math.floor(diff / (60 * 1000)))}分钟前`
  }
  if (diff < day) {
    return `${Math.floor(diff / hour)}小时前`
  }
  return `${Math.floor(diff / day)}天前`
}
