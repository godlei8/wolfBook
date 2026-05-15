export function paginate<T>(items: T[], page: number, size: number) {
  const safePage = Math.max(page, 1)
  const safeSize = Math.max(size, 1)
  const start = (safePage - 1) * safeSize
  return items.slice(start, start + safeSize)
}

export function clampPage(page: number, size: number, total: number) {
  const maxPage = Math.max(1, Math.ceil(total / Math.max(size, 1)))
  return Math.min(Math.max(page, 1), maxPage)
}
