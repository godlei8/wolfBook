import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

function readAppConfig(path) {
  return JSON.parse(readFileSync(new URL(path, import.meta.url), 'utf8'))
}

test('dev mp-weixin app config does not inject WechatSI plugin', () => {
  const appConfig = readAppConfig('../dist/dev/mp-weixin/app.json')
  assert.equal(appConfig.plugins?.WechatSI, undefined)
})
