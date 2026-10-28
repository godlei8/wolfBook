// AI 助手评测：把 scripts/assistant-eval-questions.json 里的固定问题逐个问一遍，输出一份 Markdown 报告，
// 用来对比改提示词、换模型、改检索前后的效果。走的是小程序同一个接口（/api/assistant/ask），会真的调用模型。
//
// 用法（仓库根目录，需要本地后端在跑）：
//   node scripts/assistant-eval.mjs                       # 默认 http://127.0.0.1:8088，报告写到 stdout
//   node scripts/assistant-eval.mjs http://127.0.0.1:8088 eval-report.md
// 开发环境（test profile）的登录是 MockAuthProvider，任意 code 都能登录；生产环境不要跑。
import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const base = (process.argv[2] || 'http://127.0.0.1:8088').replace(/\/+$/, '')
const output = process.argv[3]
const questions = JSON.parse(readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'assistant-eval-questions.json'), 'utf8'))

async function post(path, body, token) {
  const response = await fetch(base + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify(body),
  })
  const json = await response.json()
  if (json.code !== 0) throw new Error(`${path} → ${json.code} ${json.msg}`)
  return json.data
}

async function ask(token, item, message, sessionId) {
  const started = Date.now()
  const data = await post('/api/assistant/ask', {
    message,
    sessionId,
    scene: item.scene || 'general',
    pageContext: item.pageContext || {},
  }, token)
  return { ...data, latencyMs: Date.now() - started }
}

const { token } = await post('/api/login', { code: `assistant-eval-${Date.now()}` })
const lines = [`# AI 助手评测报告`, '', `- 接口：${base}`, `- 时间：${new Date().toLocaleString('zh-CN')}`, '']
let total = 0

for (const item of questions) {
  const turns = []
  try {
    const first = await ask(token, item, item.question)
    turns.push({ question: item.question, ...first })
    if (item.followUp) {
      turns.push({ question: item.followUp, ...(await ask(token, item, item.followUp, first.sessionId)) })
    }
  } catch (error) {
    turns.push({ question: item.question, answer: `**请求失败**：${error.message}`, answerType: 'ERROR', latencyMs: 0 })
  }
  lines.push(`## [${item.group}] ${item.id}`, '', `期望：${item.expect}`, '')
  for (const turn of turns) {
    total += turn.latencyMs
    lines.push(`**问**：${turn.question}　·　类型 \`${turn.answerType}\`　·　${(turn.latencyMs / 1000).toFixed(1)} 秒`, '')
    lines.push(String(turn.answer || '').split('\n').map((line) => `> ${line}`).join('\n'), '')
  }
  process.stderr.write(`${item.id} ✓\n`)
}

lines.push(`---`, '', `共 ${questions.length} 组，总耗时 ${(total / 1000).toFixed(1)} 秒。`)
const report = lines.join('\n')
if (output) writeFileSync(output, report, 'utf8')
else process.stdout.write(report)
