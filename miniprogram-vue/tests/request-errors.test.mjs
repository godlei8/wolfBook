import test from 'node:test'
import assert from 'node:assert/strict'

import {
  createRequestError,
  describeRequestError,
  isAuthErrorCode,
} from '../src/services/request-errors.js'

test('backend business messages are passed through untouched', () => {
  assert.equal(
    describeRequestError({ statusCode: 200, payload: { code: 4002, msg: '仍有玩家未准备' } }),
    '仍有玩家未准备',
  )
})

test('http status codes map to chinese copy', () => {
  assert.equal(describeRequestError({ statusCode: 500 }), '服务器开小差了，请稍后重试')
  assert.equal(describeRequestError({ statusCode: 502 }), '服务器开小差了，请稍后重试')
  assert.equal(describeRequestError({ statusCode: 413 }), '请求失败（413）')
  assert.equal(describeRequestError({ statusCode: 200, payload: '<html>' }), '服务器开小差了，请稍后重试')
})

test('transport failures map to chinese copy', () => {
  assert.equal(describeRequestError({ errMsg: 'request:fail timeout' }), '网络超时，请重试')
  assert.equal(describeRequestError({ errMsg: 'request:fail' }), '网络不可用，请检查网络')
  assert.equal(describeRequestError({ errMsg: 'request:fail abort' }), '请求已取消')
  assert.equal(describeRequestError({}), '请求失败，请稍后重试')
})

test('createRequestError keeps the backend code for callers', () => {
  const error = createRequestError({ statusCode: 200, payload: { code: 4001, msg: '请先登录' } })
  assert.equal(error.message, '请先登录')
  assert.equal(error.code, 4001)
  assert.equal(createRequestError({ statusCode: 503 }).code, undefined)
})

test('only backend code 4001 counts as an auth error', () => {
  assert.equal(isAuthErrorCode(4001), true)
  assert.equal(isAuthErrorCode('4001'), true)
  assert.equal(isAuthErrorCode(4003), false)
  assert.equal(isAuthErrorCode(undefined), false)
  assert.equal(isAuthErrorCode('max tokens exceeded, please login again'), false)
})
