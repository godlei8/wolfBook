import type { AdminAiConfig, AdminAiDocument, AdminAiLog, AdminAiVersion } from '../types'

// 后台「AI 助手」页的判断：路由、时间、日志筛选、资料状态、设置小节的合并。都是纯函数，单测在 tests/ai-admin.test.mjs

export type AiTab = 'qa' | 'knowledge' | 'settings'
export type SettingsSection = 'persona' | 'models' | 'advanced'

const TABS: AiTab[] = ['qa', 'knowledge', 'settings']
const SECTIONS: SettingsSection[] = ['persona', 'models', 'advanced']

/** #/ai/knowledge、#/ai/settings/models 这些地址 `ai` 后面的段 → 当前块和设置小节 */
export function parseAiPath(parts: string[]): { tab: AiTab; section: SettingsSection } {
  const [first = '', second = ''] = parts
  const tab = (TABS as string[]).includes(first) ? (first as AiTab) : 'qa'
  const section = tab === 'settings' && (SECTIONS as string[]).includes(second) ? (second as SettingsSection) : 'persona'
  return { tab, section }
}

export function aiPath(tab: AiTab, section?: SettingsSection): string {
  if (tab === 'qa') return 'ai'
  if (tab === 'settings' && section && section !== 'persona') return `ai/settings/${section}`
  return `ai/${tab}`
}

// ---------- 时间：后端给的是不带时区的 LocalDateTime，按本地时间读 ----------

export function parseLocalTime(value: string): Date {
  const match = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?/.exec(value || '')
  if (!match) return new Date(NaN)
  const [, y, mo, d, h, mi, s = '0'] = match
  return new Date(Number(y), Number(mo) - 1, Number(d), Number(h), Number(mi), Number(s))
}

const pad = (value: number) => String(value).padStart(2, '0')

function sameDay(a: Date, b: Date) {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()
}

/** 列表里的时间：今天 17:49 / 10-28 17:49 / 2025-10-28 */
export function formatLogTime(value: string, now: Date): string {
  const time = parseLocalTime(value)
  if (Number.isNaN(time.getTime())) return value || ''
  const hm = `${pad(time.getHours())}:${pad(time.getMinutes())}`
  if (sameDay(time, now)) return `今天 ${hm}`
  if (time.getFullYear() === now.getFullYear()) return `${pad(time.getMonth() + 1)}-${pad(time.getDate())} ${hm}`
  return `${time.getFullYear()}-${pad(time.getMonth() + 1)}-${pad(time.getDate())}`
}

/** 详情里的时间，到秒 */
export function formatFullTime(value: string): string {
  const time = parseLocalTime(value)
  if (Number.isNaN(time.getTime())) return value || ''
  return `${time.getFullYear()}-${pad(time.getMonth() + 1)}-${pad(time.getDate())} ${pad(time.getHours())}:${pad(time.getMinutes())}:${pad(time.getSeconds())}`
}

// ---------- 问答记录 ----------

export type LogFilter = 'all' | 'failed' | 'slow' | 'refused'

export const SLOW_MS = 10000

const LOG_TESTS: Record<LogFilter, (log: AdminAiLog) => boolean> = {
  all: () => true,
  failed: (log) => !log.success,
  slow: (log) => log.latencyMs >= SLOW_MS,
  refused: (log) => log.answerType === 'REFUSAL',
}

export function filterLogs(logs: AdminAiLog[], filter: LogFilter): AdminAiLog[] {
  return logs.filter(LOG_TESTS[filter])
}

export function logFilterCounts(logs: AdminAiLog[]): Record<LogFilter, number> {
  return {
    all: logs.length,
    failed: filterLogs(logs, 'failed').length,
    slow: filterLogs(logs, 'slow').length,
    refused: filterLogs(logs, 'refused').length,
  }
}

/** 状态条上「今天」的数：按浏览器本地日期 */
export function todayStats(logs: AdminAiLog[], now: Date) {
  const today = logs.filter((log) => sameDay(parseLocalTime(log.createTime), now))
  return {
    total: today.length,
    failed: filterLogs(today, 'failed').length,
    slow: filterLogs(today, 'slow').length,
  }
}

// ---------- 资料 ----------

export function currentVersion(versions: AdminAiVersion[]): AdminAiVersion | null {
  return versions.find((version) => version.current) || null
}

function retrievable(doc: AdminAiDocument, currentId: number | null) {
  return currentId != null && doc.publishVersionId === currentId && doc.reviewStatus === 'APPROVED' && doc.processingStatus === 'READY'
}

/** 线上能被检索到的资料数，和后端检索同口径：版本号是当前版本、已通过、解析完成 */
export function onlineCount(docs: AdminAiDocument[], currentId: number | null): number {
  return docs.filter((doc) => retrievable(doc, currentId)).length
}

export type UploadStatus = 'parsing' | 'failed' | 'pending' | 'waiting' | 'online' | 'rejected'

/** 上传文档的状态。驳回只改审核状态，所以「已驳回」要先于版本号判断 */
export function uploadStatus(doc: AdminAiDocument, currentId: number | null): UploadStatus {
  if (doc.processingStatus === 'FAILED') return 'failed'
  if (doc.processingStatus !== 'READY') return 'parsing'
  if (doc.reviewStatus === 'REJECTED') return 'rejected'
  if (doc.reviewStatus !== 'APPROVED') return 'pending'
  return retrievable(doc, currentId) ? 'online' : 'waiting'
}

/** 已通过、还没发布的上传文档（自动资料改了就生效，不算） */
export function pendingPublish(docs: AdminAiDocument[], currentId: number | null): AdminAiDocument[] {
  return docs.filter((doc) => doc.sourceType === 'DOCUMENT' && uploadStatus(doc, currentId) === 'waiting')
}

export function groupStructured(docs: AdminAiDocument[]) {
  const structured = docs.filter((doc) => doc.sourceType === 'STRUCTURED')
  const byPrefix = (prefix: string) => structured.filter((doc) => doc.sourceKey.startsWith(prefix))
  return { boards: byPrefix('BOARD:'), roles: byPrefix('ROLE:'), world: byPrefix('WORLD:') }
}

/** 问答记录里「交给模型的资料」只有名字，按名字去资料列表里认出是什么 */
export function hitKind(name: string, docs: AdminAiDocument[]): '板子' | '角色' | '世界观' | '文档' | null {
  const doc = docs.find((item) => item.name === name)
  if (!doc) return null
  if (doc.sourceType === 'DOCUMENT') return '文档'
  if (doc.sourceKey.startsWith('BOARD:')) return '板子'
  if (doc.sourceKey.startsWith('ROLE:')) return '角色'
  if (doc.sourceKey.startsWith('WORLD:')) return '世界观'
  return null
}

// ---------- 设置：每个小节只提交自己的字段 ----------

export const PERSONA_FIELDS = [
  'base.enabled',
  'base.welcomeMessage',
  'base.quickQuestions',
  'prompt.systemPrompt',
  'prompt.recommendationPrompt',
  'safety.blockedKeywords',
  'safety.unsupportedMessage',
]

export const ADVANCED_FIELDS = [
  'retrieval.topK',
  'retrieval.similarityThreshold',
  'retrieval.historyWindow',
  'base.temperature',
  'base.maxSuggestions',
  'search.webSearchEnabled',
  'volcengine.embeddingApiKey',
  'volcengine.searchApiKey',
]

type Bag = Record<string, Record<string, unknown>>

function readField(config: AdminAiConfig, field: string): unknown {
  const [group, key] = field.split('.')
  return (config as unknown as Bag)[group]?.[key]
}

export function cloneConfig(config: AdminAiConfig): AdminAiConfig {
  return JSON.parse(JSON.stringify(config))
}

/** 以服务器上的配置为底，只换上这一小节的字段（Key 的打码值原样带上，表示不改） */
export function mergeSection(server: AdminAiConfig, draft: AdminAiConfig, fields: string[]): AdminAiConfig {
  const merged = cloneConfig(server) as unknown as Bag
  for (const field of fields) {
    const [group, key] = field.split('.')
    merged[group][key] = JSON.parse(JSON.stringify(readField(draft, field) ?? null))
  }
  return merged as unknown as AdminAiConfig
}

export function countChanges(server: AdminAiConfig, draft: AdminAiConfig, fields: string[]): number {
  return fields.filter((field) => JSON.stringify(readField(server, field)) !== JSON.stringify(readField(draft, field))).length
}

/**
 * 服务器配置刷新了（比如别的标签页保存过）：草稿里没动过的字段换成新的服务器值，动过的留着。
 * 不然没动过的旧值（尤其是旧的打码 Key）会被当成改动提交上去
 */
export function rebaseDraft(previous: AdminAiConfig, next: AdminAiConfig, draft: AdminAiConfig, fields: string[]): AdminAiConfig {
  const touched = fields.filter((field) => JSON.stringify(readField(previous, field)) !== JSON.stringify(readField(draft, field)))
  return mergeSection(next, draft, touched)
}

const NUMBER_RULES: { field: string; min: number; max: number; integer: boolean }[] = [
  { field: 'retrieval.topK', min: 1, max: 10, integer: true },
  { field: 'retrieval.similarityThreshold', min: 0, max: 1, integer: false },
  { field: 'retrieval.historyWindow', min: 2, max: 20, integer: true },
  { field: 'base.temperature', min: 0, max: 1.5, integer: false },
  { field: 'base.maxSuggestions', min: 1, max: 6, integer: true },
]

/** 高级小节的数字收进范围；清空或填了不是数字的，用服务器上的值（后端不校验范围，温度过大模型会直接报错） */
export function clampAdvanced(server: AdminAiConfig, draft: AdminAiConfig): AdminAiConfig {
  const result = cloneConfig(draft) as unknown as Bag
  for (const rule of NUMBER_RULES) {
    const [group, key] = rule.field.split('.')
    const raw = readField(draft, rule.field)
    let value = typeof raw === 'number' ? raw : raw === '' || raw == null ? NaN : Number(raw)
    if (!Number.isFinite(value)) value = Number(readField(server, rule.field))
    if (rule.integer) value = Math.round(value)
    result[group][key] = Math.min(rule.max, Math.max(rule.min, value))
  }
  return result as unknown as AdminAiConfig
}
