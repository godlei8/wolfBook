// 后台「AI 助手」页的纯逻辑（Node 内置测试运行器，Node 23+ 直接加载 .ts）：node --test "tests/*.test.mjs"
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  ADVANCED_FIELDS,
  PERSONA_FIELDS,
  DRAFT_FIELDS,
  KEY_FIELDS,
  aiPath,
  applyVersionToDraft,
  avgLatency,
  clampAdvanced,
  configChanges,
  dailyCounts,
  neighborId,
  normalizeQuestion,
  searchLogs,
  sessionThread,
  slowest,
  storableDraft,
  topQuestions,
  typeShare,
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

test('parseAiPathPicksThePageAndKnowledgeTab', () => {
  assert.deepEqual(parseAiPath([]), { page: 'compose', knowledgeTab: 'auto' })
  assert.deepEqual(parseAiPath(['logs']), { page: 'logs', knowledgeTab: 'auto' })
  assert.deepEqual(parseAiPath(['knowledge', 'uploads']), { page: 'knowledge', knowledgeTab: 'uploads' })
  assert.deepEqual(parseAiPath(['knowledge', 'nope']), { page: 'knowledge', knowledgeTab: 'auto' })
  assert.deepEqual(parseAiPath(['data']), { page: 'data', knowledgeTab: 'auto' })
  // 上一版的地址落到编排
  assert.deepEqual(parseAiPath(['settings', 'models']), { page: 'compose', knowledgeTab: 'auto' })
  assert.deepEqual(parseAiPath(['nope']), { page: 'compose', knowledgeTab: 'auto' })
  assert.equal(aiPath('compose'), 'ai')
  assert.equal(aiPath('logs'), 'ai/logs')
  assert.equal(aiPath('knowledge'), 'ai/knowledge')
  assert.equal(aiPath('knowledge', 'auto'), 'ai/knowledge')
  assert.equal(aiPath('knowledge', 'versions'), 'ai/knowledge/versions')
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
  assert.deepEqual(logFilterCounts(logs, new Date(2026, 10, 3, 12, 0)), { all: 4, failed: 1, slow: 2, refused: 1, today: 4 })
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

test('draftFieldsLeaveOutTheSwitchAndTheKeys', () => {
  assert.equal(DRAFT_FIELDS.includes('base.enabled'), false)
  assert.equal(DRAFT_FIELDS.includes('volcengine.embeddingApiKey'), false)
  assert.equal(DRAFT_FIELDS.includes('prompt.systemPrompt'), true)
  assert.equal(DRAFT_FIELDS.includes('retrieval.topK'), true)
  assert.deepEqual(KEY_FIELDS, ['volcengine.embeddingApiKey', 'volcengine.searchApiKey'])
})

test('configChangesListEachChangedFieldAndTheModel', () => {
  const before = config()
  const after = config()
  after.prompt.systemPrompt = '新人设'
  after.safety.blockedKeywords = ['站边', '代打']
  after.retrieval.topK = 6
  after.base.enabled = false
  after.volcengine.embeddingApiKey = 'sk-real'
  const changes = configChanges(before, after, '官方', '学校')
  assert.deepEqual(changes.map((c) => c.field), ['prompt.systemPrompt', 'safety.blockedKeywords', 'retrieval.topK', 'model'])
  const kw = changes.find((c) => c.field === 'safety.blockedKeywords')
  assert.equal(kw.label, '拦截词')
  assert.equal(kw.before, '站边')
  assert.equal(kw.after, '站边、代打')
  assert.equal(changes.find((c) => c.field === 'prompt.systemPrompt').long, true)
  assert.deepEqual(changes.find((c) => c.field === 'model'), { field: 'model', label: '模型', before: '官方', after: '学校', long: false })
  assert.equal(configChanges(before, config(), '官方', '官方').length, 0)
})

test('applyVersionToDraftKeepsLiveKeysAndSwitch', () => {
  const live = config()
  live.volcengine.embeddingApiKey = '********ab12'
  live.base.enabled = false
  const old = config()
  old.prompt.systemPrompt = '旧人设'
  old.volcengine.embeddingApiKey = ''
  old.base.enabled = true
  const draft = applyVersionToDraft(old, live)
  assert.equal(draft.prompt.systemPrompt, '旧人设')
  assert.equal(draft.volcengine.embeddingApiKey, '********ab12')
  assert.equal(draft.base.enabled, false)
})

test('storableDraftNeverHoldsARealKey', () => {
  const live = config()
  live.volcengine.searchApiKey = '********cd34'
  const draft = config()
  draft.volcengine.searchApiKey = 'sk-typed-but-not-saved'
  draft.prompt.systemPrompt = '改过'
  const stored = storableDraft(draft, live)
  assert.equal(stored.volcengine.searchApiKey, '********cd34')
  assert.equal(stored.prompt.systemPrompt, '改过')
})

test('sessionThreadGroupsBySessionInTimeOrder', () => {
  const a = log('2026-10-28T17:49:41', { id: 1, sessionId: 's1' })
  const b = log('2026-10-28T17:49:46', { id: 2, sessionId: 's1' })
  const c = log('2026-10-28T17:49:50', { id: 3, sessionId: 's2' })
  const lone = log('2026-10-28T17:49:52', { id: 4, sessionId: null })
  assert.deepEqual(sessionThread([b, c, a, lone], b).map((x) => x.id), [1, 2])
  assert.deepEqual(sessionThread([b, c, a, lone], lone).map((x) => x.id), [4])
})

test('searchLogsMatchesQuestionText', () => {
  const logs = [log('2026-10-28T17:49:41', { userMessage: '女巫能不能自救' }), log('2026-10-28T17:49:42', { userMessage: '12人进阶推荐什么板子' })]
  assert.equal(searchLogs(logs, ' 女巫 ').length, 1)
  assert.equal(searchLogs(logs, '').length, 2)
})

test('neighborIdStepsWithoutWrapping', () => {
  const ids = [10, 11, 12]
  assert.equal(neighborId(ids, 11, 1), 12)
  assert.equal(neighborId(ids, 11, -1), 10)
  assert.equal(neighborId(ids, 12, 1), null)
  assert.equal(neighborId(ids, 10, -1), null)
  assert.equal(neighborId(ids, 99, 1), 10)
})

test('logFilterTodayUsesLocalDate', () => {
  const now = new Date(2026, 10, 4, 9, 0)
  const logs = [log('2026-11-04T08:00:00'), log('2026-11-03T23:00:00')]
  assert.equal(filterLogs(logs, 'today', now).length, 1)
  assert.equal(logFilterCounts(logs, now).today, 1)
})

test('dailyCountsFillEmptyDays', () => {
  const now = new Date(2026, 10, 4, 9, 0)
  const logs = [log('2026-11-04T08:00:00'), log('2026-11-04T08:10:00'), log('2026-11-01T08:00:00'), log('2026-10-01T08:00:00')]
  const days = dailyCounts(logs, '7', now)
  assert.equal(days.length, 7)
  assert.deepEqual(days.map((d) => d.count), [0, 0, 0, 1, 0, 0, 2])
  assert.equal(days[6].label, '11-04')
  const all = dailyCounts(logs, 'all', now)
  assert.deepEqual(all.map((d) => [d.label, d.count]), [['10-01', 1], ['11-01', 1], ['11-04', 2]])
})

test('typeShareCountsFailuresSeparately', () => {
  const logs = [
    log('2026-11-04T08:00:00', { answerType: 'RAG_ANSWER' }),
    log('2026-11-04T08:00:00', { answerType: 'RAG_ANSWER', success: false }),
    log('2026-11-04T08:00:00', { answerType: 'STRUCTURED_RECOMMENDATION' }),
    log('2026-11-04T08:00:00', { answerType: 'REFUSAL' }),
  ]
  const share = Object.fromEntries(typeShare(logs).map((s) => [s.key, s.count]))
  assert.deepEqual(share, { RAG_ANSWER: 1, STRUCTURED_RECOMMENDATION: 1, WEB_AUGMENTED_ANSWER: 0, REFUSAL: 1, FAILED: 1 })
})

test('topQuestionsMergeSameQuestion', () => {
  assert.equal(normalizeQuestion(' 女巫能不能自救？ '), '女巫能不能自救')
  const logs = [
    log('2026-11-04T08:00:00', { userMessage: '女巫能不能自救' }),
    log('2026-11-04T08:01:00', { userMessage: '女巫能不能自救？' }),
    log('2026-11-04T08:02:00', { userMessage: '12人' }),
  ]
  assert.deepEqual(topQuestions(logs, 5), [{ text: '女巫能不能自救', count: 2 }, { text: '12人', count: 1 }])
})

test('slowestAndAverageSkipRefusals', () => {
  const logs = [
    log('2026-11-04T08:00:00', { id: 1, latencyMs: 1000 }),
    log('2026-11-04T08:00:00', { id: 2, latencyMs: 3000 }),
    log('2026-11-04T08:00:00', { id: 3, answerType: 'REFUSAL', latencyMs: 9000 }),
  ]
  assert.deepEqual(slowest(logs, 5).map((x) => x.id), [2, 1])
  assert.equal(avgLatency(logs), 2000)
  assert.equal(avgLatency([]), null)
})

test('configChangesCompareModelsById', () => {
  const same = config()
  // 两个模型同名：按 id 比，换了就算改动
  assert.equal(configChanges(same, config(), '学校', '学校', 1, 2).length, 1)
  // 线上模型改了名，id 没变：不算改动
  assert.equal(configChanges(same, config(), '旧名字', '新名字', 3, 3).length, 0)
})

test('crashesAreFailuresNotRefusals', () => {
  // 流程里出意外时日志记的是 REFUSAL + 失败
  const crash = log('2026-11-04T08:00:00', { id: 9, answerType: 'REFUSAL', success: false, latencyMs: 15000 })
  const refusal = log('2026-11-04T08:00:00', { id: 8, answerType: 'REFUSAL', latencyMs: 0 })
  assert.deepEqual(filterLogs([crash, refusal], 'refused').map((x) => x.id), [8])
  assert.deepEqual(slowest([crash, refusal], 5).map((x) => x.id), [9])
  assert.equal(avgLatency([crash, refusal]), 15000)
})
