// 后台「AI 助手」页的纯逻辑（Node 内置测试运行器，Node 23+ 直接加载 .ts）：node --test "tests/*.test.mjs"
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  ADVANCED_FIELDS,
  PERSONA_FIELDS,
  aiPath,
  clampAdvanced,
  countChanges,
  currentVersion,
  filterLogs,
  formatFullTime,
  formatLogTime,
  groupStructured,
  hitKind,
  logFilterCounts,
  mergeSection,
  onlineCount,
  parseAiPath,
  pendingPublish,
  rebaseDraft,
  todayStats,
  uploadStatus,
} from '../src/utils/aiAdmin.ts'

const log = (createTime, extra = {}) => ({
  id: Math.random(),
  userMessage: 'q',
  answerType: 'RAG_ANSWER',
  hitSources: [],
  usedWebSearch: false,
  latencyMs: 1000,
  success: true,
  failureType: null,
  traceId: 't',
  createTime,
  answer: null,
  openid: null,
  sessionId: null,
  ...extra,
})

const doc = (id, extra = {}) => ({
  id,
  name: `doc${id}`,
  fileName: null,
  sourceType: 'DOCUMENT',
  sourceKey: `DOC:${id}`,
  sourceId: null,
  summary: null,
  chunkCount: 1,
  processingStatus: 'READY',
  reviewStatus: 'APPROVED',
  publishVersionId: 5,
  lastError: null,
  createTime: '2026-11-01T10:00:00',
  updateTime: '2026-11-01T10:00:00',
  ...extra,
})

const config = () => ({
  base: { enabled: true, welcomeMessage: '你好', quickQuestions: ['a', 'b'], temperature: 0.35, maxSuggestions: 3 },
  provider: { platform: 'DEEPSEEK', model: 'm', baseUrl: 'u', apiKey: '********aaaa' },
  volcengine: { baseUrl: 'v', embeddingModel: 'e', embeddingApiKey: '', searchModel: 's', searchApiKey: '' },
  prompt: { systemPrompt: '人设', recommendationPrompt: '推荐', refusalPrompt: '婉拒' },
  retrieval: { topK: 4, similarityThreshold: 0.45, historyWindow: 12 },
  search: { webSearchEnabled: true },
  safety: { unsupportedMessage: '不行', blockedKeywords: ['站边'] },
  ui: { mascot: 'wolf', dockLabel: 'd', accentColor: '#FFC000' },
})

test('parseAiPathFallsBackToQa', () => {
  assert.deepEqual(parseAiPath([]), { tab: 'qa', section: 'persona' })
  assert.deepEqual(parseAiPath(['knowledge']), { tab: 'knowledge', section: 'persona' })
  assert.deepEqual(parseAiPath(['settings', 'models']), { tab: 'settings', section: 'models' })
  assert.deepEqual(parseAiPath(['settings', 'nope']), { tab: 'settings', section: 'persona' })
  assert.deepEqual(parseAiPath(['nope']), { tab: 'qa', section: 'persona' })
  assert.equal(aiPath('qa'), 'ai')
  assert.equal(aiPath('knowledge'), 'ai/knowledge')
  assert.equal(aiPath('settings'), 'ai/settings')
  assert.equal(aiPath('settings', 'persona'), 'ai/settings')
  assert.equal(aiPath('settings', 'advanced'), 'ai/settings/advanced')
})

test('formatLogTimeByAge', () => {
  const now = new Date(2026, 10, 3, 20, 0)
  assert.equal(formatLogTime('2026-11-03T17:49:47', now), '今天 17:49')
  assert.equal(formatLogTime('2026-10-28T09:05:00', now), '10-28 09:05')
  assert.equal(formatLogTime('2025-10-28T09:05:00', now), '2025-10-28')
  assert.equal(formatFullTime('2026-10-28T17:49:47'), '2026-10-28 17:49:47')
  assert.equal(formatFullTime('2026-10-28T17:49:47.123'), '2026-10-28 17:49:47')
})

test('todayStatsUseLocalDate', () => {
  const now = new Date(2026, 10, 3, 0, 30)
  const logs = [
    log('2026-11-02T23:59:00', { success: false }),
    log('2026-11-03T00:01:00', { latencyMs: 12000 }),
    log('2026-11-03T00:20:00'),
  ]
  assert.deepEqual(todayStats(logs, now), { total: 2, failed: 0, slow: 1 })
})

test('filterLogsByKind', () => {
  const logs = [
    log('2026-11-03T10:00:00', { success: false, latencyMs: 30000 }),
    log('2026-11-03T10:01:00', { latencyMs: 10000 }),
    log('2026-11-03T10:02:00', { answerType: 'REFUSAL', latencyMs: 0 }),
    log('2026-11-03T10:03:00'),
  ]
  assert.equal(filterLogs(logs, 'all').length, 4)
  assert.deepEqual(filterLogs(logs, 'failed'), [logs[0]])
  assert.deepEqual(filterLogs(logs, 'slow'), [logs[0], logs[1]])
  assert.deepEqual(filterLogs(logs, 'refused'), [logs[2]])
  assert.deepEqual(logFilterCounts(logs), { all: 4, failed: 1, slow: 2, refused: 1 })
})

test('noCurrentVersionMeansNothingOnline', () => {
  assert.equal(currentVersion([]), null)
  const docs = [doc(1, { publishVersionId: null }), doc(2, { sourceType: 'STRUCTURED', sourceKey: 'BOARD:2', publishVersionId: null })]
  assert.equal(onlineCount(docs, null), 0)
  assert.deepEqual(pendingPublish(docs, null).map((item) => item.id), [1])
  assert.equal(uploadStatus(docs[0], null), 'waiting')
})

test('currentVersionPicksTheCurrentFlag', () => {
  const versions = [
    { id: 7, versionName: 'v7', notes: null, documentIds: [], current: false, publishedBy: null, createTime: '' },
    { id: 5, versionName: 'v5', notes: null, documentIds: [], current: true, publishedBy: null, createTime: '' },
  ]
  assert.equal(currentVersion(versions)?.id, 5)
})

test('onlineCountMatchesRetrieval', () => {
  const docs = [
    doc(1),
    doc(2, { reviewStatus: 'REJECTED' }),
    doc(3, { publishVersionId: 4 }),
    doc(4, { processingStatus: 'FAILED' }),
  ]
  assert.equal(onlineCount(docs, 5), 1)
})

test('uploadStatusFollowsReviewAndVersion', () => {
  assert.equal(uploadStatus(doc(1, { processingStatus: 'PENDING' }), 5), 'parsing')
  assert.equal(uploadStatus(doc(1, { processingStatus: 'FAILED' }), 5), 'failed')
  assert.equal(uploadStatus(doc(1, { reviewStatus: 'PENDING' }), 5), 'pending')
  assert.equal(uploadStatus(doc(1, { publishVersionId: 4 }), 5), 'waiting')
  assert.equal(uploadStatus(doc(1), 5), 'online')
  assert.equal(uploadStatus(doc(1, { reviewStatus: 'REJECTED' }), 5), 'rejected')
  const docs = [
    doc(1, { publishVersionId: 4 }),
    doc(2),
    doc(3, { reviewStatus: 'PENDING', publishVersionId: null }),
    doc(4, { sourceType: 'STRUCTURED', sourceKey: 'ROLE:4', publishVersionId: null }),
  ]
  assert.deepEqual(pendingPublish(docs, 5).map((item) => item.id), [1])
})

test('groupStructuredAndHitKind', () => {
  const docs = [
    doc(1, { sourceType: 'STRUCTURED', sourceKey: 'BOARD:2', name: '预女猎白' }),
    doc(2, { sourceType: 'STRUCTURED', sourceKey: 'ROLE:3', name: '女巫' }),
    doc(3, { sourceType: 'STRUCTURED', sourceKey: 'WORLD:1', name: '夜镇编年史' }),
    doc(4, { name: 'terms_optimized' }),
  ]
  const groups = groupStructured(docs)
  assert.deepEqual(groups.boards.map((item) => item.id), [1])
  assert.deepEqual(groups.roles.map((item) => item.id), [2])
  assert.deepEqual(groups.world.map((item) => item.id), [3])
  assert.equal(hitKind('预女猎白', docs), '板子')
  assert.equal(hitKind('女巫', docs), '角色')
  assert.equal(hitKind('夜镇编年史', docs), '世界观')
  assert.equal(hitKind('terms_optimized', docs), '文档')
  assert.equal(hitKind('不存在', docs), null)
})

test('mergeSectionKeepsOtherFieldsFromServer', () => {
  const server = config()
  const draft = config()
  draft.prompt.systemPrompt = '新人设'
  draft.retrieval.topK = 8
  draft.base.quickQuestions = ['a', 'b']
  const merged = mergeSection(server, draft, PERSONA_FIELDS)
  assert.equal(merged.prompt.systemPrompt, '新人设')
  assert.equal(merged.retrieval.topK, 4)
  assert.equal(server.prompt.systemPrompt, '人设')
  assert.equal(draft.retrieval.topK, 8)
  merged.base.quickQuestions.push('c')
  assert.deepEqual(draft.base.quickQuestions, ['a', 'b'])
  assert.equal(countChanges(server, draft, PERSONA_FIELDS), 1)
  assert.equal(countChanges(server, draft, ADVANCED_FIELDS), 1)
})

test('mergeSectionPassesMaskedKeysThrough', () => {
  const server = config()
  const draft = config()
  draft.volcengine.embeddingApiKey = '********ab12'
  draft.provider.apiKey = 'should-not-be-sent'
  const merged = mergeSection(server, draft, ADVANCED_FIELDS)
  assert.equal(merged.volcengine.embeddingApiKey, '********ab12')
  assert.equal(merged.provider.apiKey, '********aaaa')
})

test('rebaseDraftTakesNewServerValuesForUntouchedFields', () => {
  // 别的标签页改了 Key 和人设；这边只动过 TopK。刷新后没动过的字段跟新的服务器值走，动过的保留
  const before = config()
  before.volcengine.embeddingApiKey = '********ab12'
  const after = config()
  after.volcengine.embeddingApiKey = '********cd34'
  after.prompt.systemPrompt = '别处改的人设'
  const draft = config()
  draft.volcengine.embeddingApiKey = '********ab12'
  draft.retrieval.topK = 8
  const rebased = rebaseDraft(before, after, draft, [...PERSONA_FIELDS, ...ADVANCED_FIELDS])
  assert.equal(rebased.volcengine.embeddingApiKey, '********cd34')
  assert.equal(rebased.prompt.systemPrompt, '别处改的人设')
  assert.equal(rebased.retrieval.topK, 8)
  assert.equal(countChanges(after, rebased, ADVANCED_FIELDS), 1)
})

test('clampAdvancedKeepsNumbersInRange', () => {
  const server = config()
  const draft = config()
  draft.base.temperature = 3
  draft.retrieval.topK = 0
  draft.retrieval.similarityThreshold = ''
  draft.retrieval.historyWindow = 7.6
  draft.base.maxSuggestions = 99
  const clamped = clampAdvanced(server, draft)
  assert.equal(clamped.base.temperature, 1.5)
  assert.equal(clamped.retrieval.topK, 1)
  assert.equal(clamped.retrieval.similarityThreshold, 0.45)
  assert.equal(clamped.retrieval.historyWindow, 8)
  assert.equal(clamped.base.maxSuggestions, 6)
  assert.equal(draft.base.temperature, 3)
})

test('pageNumbersCollapseLongRuns', async () => {
  const { pageNumbers } = await import('../src/utils/pagination.ts')
  assert.deepEqual(pageNumbers(1, 1), [1])
  assert.deepEqual(pageNumbers(2, 5), [1, 2, 3, 4, 5])
  assert.deepEqual(pageNumbers(1, 50), [1, 2, 3, '…', 50])
  assert.deepEqual(pageNumbers(25, 50), [1, '…', 24, 25, 26, '…', 50])
  assert.deepEqual(pageNumbers(50, 50), [1, '…', 48, 49, 50])
  assert.deepEqual(pageNumbers(4, 8), [1, 2, 3, 4, 5, '…', 8])
})
