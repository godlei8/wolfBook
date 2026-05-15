export interface DocumentSummary {
  total: number
  approved: number
  pending: number
  published: number
}

export function sourceTypeLabel(sourceType: string) {
  if (sourceType === 'STRUCTURED') return '结构化'
  if (sourceType === 'DOCUMENT') return '上传文档'
  return sourceType || '未知'
}

export function statusTagType(status: string) {
  if (status === 'APPROVED' || status === 'READY') return 'success'
  if (status === 'PENDING') return 'warning'
  if (status === 'FAILED' || status === 'REJECTED') return 'danger'
  return 'info'
}

export function processingStatusLabel(status: string) {
  if (status === 'READY') return '已完成'
  if (status === 'PENDING') return '处理中'
  if (status === 'FAILED') return '失败'
  return status || '未知'
}

export function reviewStatusLabel(status: string) {
  if (status === 'APPROVED') return '已通过'
  if (status === 'PENDING') return '待审核'
  if (status === 'REJECTED') return '已驳回'
  return status || '未知'
}

export function formatHitSources(items: string[]) {
  if (!items?.length) {
    return ['未命中来源']
  }
  return items
}
