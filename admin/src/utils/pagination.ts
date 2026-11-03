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

/**
 * 页码按钮：首页、末页、当前页前后各一页；中间只隔一页就直接补上，隔得多用「…」。
 * 比如第 25 / 50 页 → 1 … 24 25 26 … 50
 */
export function pageNumbers(current: number, total: number): (number | '…')[] {
  const last = Math.max(1, total)
  const page = Math.min(Math.max(current, 1), last)
  let start = Math.max(1, page - 1)
  let end = Math.min(last, page + 1)
  // 靠边时也凑满三个，翻页按钮不会忽多忽少
  if (end - start < 2) {
    if (start === 1) end = Math.min(last, 3)
    else start = Math.max(1, last - 2)
  }
  const result: (number | '…')[] = []
  const push = (from: number, to: number) => {
    for (let n = from; n <= to; n++) result.push(n)
  }
  if (start > 1) {
    result.push(1)
    if (start === 3) result.push(2)
    else if (start > 3) result.push('…')
  }
  push(start, end)
  if (end < last) {
    if (end === last - 2) result.push(last - 1)
    else if (end < last - 2) result.push('…')
    result.push(last)
  }
  return result
}
