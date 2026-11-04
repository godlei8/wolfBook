import type { AdminAiConfig, AdminAiDocument, AdminAiLog, AdminAiVersion } from '../types'

// 后台「AI 助手」页的判断：路由、时间、日志筛选、资料状态、设置小节的合并。都是纯函数，单测在 tests/ai-admin.test.mjs

export type AiPage = 'compose' | 'logs' | 'knowledge' | 'data'
export type KnowledgeTab = 'auto' | 'uploads' | 'versions'

const PAGES: AiPage[] = ['logs', 'knowledge', 'data']
const KNOWLEDGE_TABS: KnowledgeTab[] = ['auto', 'uploads', 'versions']

/** #/ai/logs、#/ai/knowledge/uploads 这些地址 `ai` 后面的段 → 子菜单里的哪一页、资料库的哪个标签；认不出的（含上一版的 settings）都落到编排 */
export function parseAiPath(parts: string[]): { page: AiPage; knowledgeTab: KnowledgeTab } {
  const [first = '', second = ''] = parts
  const page = (PAGES as string[]).includes(first) ? (first as AiPage) : 'compose'
  const knowledgeTab = page === 'knowledge' && (KNOWLEDGE_TABS as string[]).includes(second) ? (second as KnowledgeTab) : 'auto'
  return { page, knowledgeTab }
}

export function aiPath(page: AiPage, knowledgeTab?: KnowledgeTab): string {
  if (page === 'compose') return 'ai'
  if (page === 'knowledge' && knowledgeTab && knowledgeTab !== 'auto') return `ai/knowledge/${knowledgeTab}`
  return `ai/${page}`
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

export type LogFilter = 'all' | 'failed' | 'slow' | 'refused' | 'today'

export const SLOW_MS = 10000

function logTest(filter: LogFilter, now: Date): (log: AdminAiLog) => boolean {
  if (filter === 'failed') return (log) => !log.success
  if (filter === 'slow') return (log) => log.latencyMs >= SLOW_MS
  // 流程里出意外时日志也记成 REFUSAL，但 success = false，那算失败不算婉拒
  if (filter === 'refused') return (log) => log.answerType === 'REFUSAL' && log.success
  if (filter === 'today') return (log) => sameDay(parseLocalTime(log.createTime), now)
  return () => true
}

export function filterLogs(logs: AdminAiLog[], filter: LogFilter, now: Date = new Date()): AdminAiLog[] {
  return logs.filter(logTest(filter, now))
}

export function logFilterCounts(logs: AdminAiLog[], now: Date = new Date()): Record<LogFilter, number> {
  return {
    all: logs.length,
    failed: filterLogs(logs, 'failed', now).length,
    slow: filterLogs(logs, 'slow', now).length,
    refused: filterLogs(logs, 'refused', now).length,
    today: filterLogs(logs, 'today', now).length,
  }
}

/** 按问题文字筛，忽略首尾空白和大小写 */
export function searchLogs(logs: AdminAiLog[], text: string): AdminAiLog[] {
  const needle = text.trim().toLowerCase()
  if (!needle) return logs
  return logs.filter((log) => log.userMessage.toLowerCase().includes(needle))
}

function byTime(a: AdminAiLog, b: AdminAiLog) {
  return parseLocalTime(a.createTime).getTime() - parseLocalTime(b.createTime).getTime() || a.id - b.id
}

/** 同一会话的前后几问，按时间正序；没有会话号的只有它自己 */
export function sessionThread(logs: AdminAiLog[], log: AdminAiLog): AdminAiLog[] {
  if (!log.sessionId) return [log]
  return logs.filter((item) => item.sessionId === log.sessionId).sort(byTime)
}

/** J / K 翻条：在当前列表里的上一条 / 下一条，到头了返回 null；当前那条不在列表里时从第一条开始 */
export function neighborId(ids: number[], currentId: number | null, delta: 1 | -1): number | null {
  const index = currentId == null ? -1 : ids.indexOf(currentId)
  if (index < 0) return ids[0] ?? null
  return ids[index + delta] ?? null
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

// ---------- 编排页的草稿：开关立即生效、火山 Key 单独保存，这两样不进草稿也不进版本 ----------

export const KEY_FIELDS = ['volcengine.embeddingApiKey', 'volcengine.searchApiKey']

export const DRAFT_FIELDS = [...PERSONA_FIELDS, ...ADVANCED_FIELDS].filter(
  (field) => field !== 'base.enabled' && !KEY_FIELDS.includes(field),
)

const FIELD_LABELS: Record<string, string> = {
  'prompt.systemPrompt': '人设',
  'base.welcomeMessage': '开场白',
  'base.quickQuestions': '推荐问题',
  'prompt.recommendationPrompt': '推荐板子时的要求',
  'safety.blockedKeywords': '拦截词',
  'safety.unsupportedMessage': '婉拒话术',
  'retrieval.topK': 'TopK',
  'retrieval.similarityThreshold': '相似度门槛',
  'retrieval.historyWindow': '历史窗口',
  'base.temperature': '温度',
  'base.maxSuggestions': '推荐问题数',
  'search.webSearchEnabled': '联网搜索兜底',
}

// 改动清单按页面上从上到下的顺序排
const CHANGE_ORDER = [
  'prompt.systemPrompt',
  'base.welcomeMessage',
  'base.quickQuestions',
  'safety.blockedKeywords',
  'safety.unsupportedMessage',
  'prompt.recommendationPrompt',
  'retrieval.topK',
  'retrieval.similarityThreshold',
  'retrieval.historyWindow',
  'base.temperature',
  'base.maxSuggestions',
  'search.webSearchEnabled',
]

const LONG_FIELDS = ['prompt.systemPrompt', 'prompt.recommendationPrompt', 'base.welcomeMessage', 'safety.unsupportedMessage']

export interface ConfigChange {
  field: string
  label: string
  before: string
  after: string
  /** 长文本：清单里只说「改了」，点开看前后全文 */
  long: boolean
}

function showValue(value: unknown): string {
  if (Array.isArray(value)) return value.join('、')
  if (typeof value === 'boolean') return value ? '开' : '关'
  if (value == null || value === '') return '（空）'
  return String(value)
}

/**
 * 两份配置之间改了什么（发布确认、历史版本对比都用），最后加上模型。
 * 传了模型 id 就按 id 比（同名的两个模型也分得开，线上模型改名不算改动），名字只用来显示
 */
export function configChanges(
  before: AdminAiConfig,
  after: AdminAiConfig,
  beforeModel: string | null,
  afterModel: string | null,
  beforeModelId?: number | null,
  afterModelId?: number | null,
): ConfigChange[] {
  const changes: ConfigChange[] = CHANGE_ORDER.filter(
    (field) => JSON.stringify(readField(before, field)) !== JSON.stringify(readField(after, field)),
  ).map((field) => ({
    field,
    label: FIELD_LABELS[field] || field,
    before: showValue(readField(before, field)),
    after: showValue(readField(after, field)),
    long: LONG_FIELDS.includes(field),
  }))
  const modelChanged =
    beforeModelId !== undefined && afterModelId !== undefined
      ? (beforeModelId ?? null) !== (afterModelId ?? null)
      : (beforeModel || null) !== (afterModel || null)
  if (modelChanged) {
    changes.push({ field: 'model', label: '模型', before: beforeModel || '默认配置', after: afterModel || '默认配置', long: false })
  }
  return changes
}

/** 历史版本「覆盖草稿」：只换草稿字段，开关和 Key 保持线上的（版本里本来就不存 Key） */
export function applyVersionToDraft(versionConfig: AdminAiConfig, live: AdminAiConfig): AdminAiConfig {
  return mergeSection(live, versionConfig, DRAFT_FIELDS)
}

/** 存进浏览器前把 Key 换回线上的打码值，浏览器里不留明文 Key */
export function storableDraft(draft: AdminAiConfig, live: AdminAiConfig): AdminAiConfig {
  return mergeSection(draft, live, KEY_FIELDS)
}

// ---------- 数据页：都用已拉到的日志在页面上算 ----------

export type DataRange = '7' | '30' | 'all'

function dayKey(date: Date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

function startOfDay(date: Date) {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate())
}

export function logsInRange(logs: AdminAiLog[], range: DataRange, now: Date): AdminAiLog[] {
  if (range === 'all') return logs
  const from = startOfDay(now)
  from.setDate(from.getDate() - (Number(range) - 1))
  return logs.filter((log) => parseLocalTime(log.createTime) >= from)
}

/** 每天提问数：7 / 30 天连空白日也给一根柱；「全部」只给有提问的日子，最多 30 天 */
export function dailyCounts(logs: AdminAiLog[], range: DataRange, now: Date): { key: string; label: string; count: number }[] {
  const counts = new Map<string, number>()
  for (const log of logs) {
    const time = parseLocalTime(log.createTime)
    if (Number.isNaN(time.getTime())) continue
    counts.set(dayKey(time), (counts.get(dayKey(time)) || 0) + 1)
  }
  const label = (key: string) => key.slice(5)
  if (range === 'all') {
    return [...counts.keys()].sort().slice(-30).map((key) => ({ key, label: label(key), count: counts.get(key) || 0 }))
  }
  const days: { key: string; label: string; count: number }[] = []
  for (let back = Number(range) - 1; back >= 0; back--) {
    const day = startOfDay(now)
    day.setDate(day.getDate() - back)
    const key = dayKey(day)
    days.push({ key, label: label(key), count: counts.get(key) || 0 })
  }
  return days
}

export const ANSWER_TYPES: { key: string; label: string }[] = [
  { key: 'RAG_ANSWER', label: '按资料回答' },
  { key: 'STRUCTURED_RECOMMENDATION', label: '推荐板子' },
  { key: 'WEB_AUGMENTED_ANSWER', label: '联网回答' },
  { key: 'REFUSAL', label: '婉拒' },
  { key: 'FAILED', label: '失败' },
]

/** 回答类型占比：失败单独算，不再算进它的回答类型 */
export function typeShare(logs: AdminAiLog[]): { key: string; label: string; count: number; ratio: number }[] {
  const total = logs.length || 1
  return ANSWER_TYPES.map(({ key, label }) => {
    const count = key === 'FAILED' ? logs.filter((log) => !log.success).length : logs.filter((log) => log.success && log.answerType === key).length
    return { key, label, count, ratio: count / total }
  })
}

/** 同一句话的不同写法算一个：去掉首尾空白和末尾标点 */
export function normalizeQuestion(text: string): string {
  return text.trim().replace(/[\s?？。.!！~～，,]+$/u, '')
}

export function topQuestions(logs: AdminAiLog[], limit: number): { text: string; count: number }[] {
  const counts = new Map<string, number>()
  for (const log of logs) {
    const text = normalizeQuestion(log.userMessage)
    if (text) counts.set(text, (counts.get(text) || 0) + 1)
  }
  return [...counts.entries()]
    .map(([text, count]) => ({ text, count }))
    .sort((a, b) => b.count - a.count)
    .slice(0, limit)
}

/** 婉拒不走模型，耗时不算；流程出意外的（REFUSAL + 失败）要算 */
function timed(log: AdminAiLog) {
  return log.answerType !== 'REFUSAL' || !log.success
}

/** 最慢的几题 */
export function slowest(logs: AdminAiLog[], limit: number): AdminAiLog[] {
  return logs.filter(timed).sort((a, b) => b.latencyMs - a.latencyMs).slice(0, limit)
}

/** 平均耗时（毫秒）；没有数据返回 null */
export function avgLatency(logs: AdminAiLog[]): number | null {
  const timedLogs = logs.filter(timed)
  if (!timedLogs.length) return null
  return Math.round(timedLogs.reduce((sum, log) => sum + log.latencyMs, 0) / timedLogs.length)
}
