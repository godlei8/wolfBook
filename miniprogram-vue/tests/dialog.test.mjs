import test from 'node:test'
import assert from 'node:assert/strict'
import { dialogState, pick, showActionSheet, showModal } from '../src/utils/dialog.js'

// 用户给定：小程序的弹窗、底部弹出菜单都用夜镇卡牌的统一样式。
// utils/dialog.js 和 uni.showModal / uni.showActionSheet 同一套参数，页面里的 NtDialogHost 负责画出来。

test('modalConfirmsAndCancelsLikeUni', () => {
  const calls = []
  showModal({ title: '删除记录', content: '确认删除这条记录吗？', success: (res) => calls.push(res) })
  assert.equal(dialogState.kind, 'modal')
  assert.equal(dialogState.title, '删除记录')
  assert.equal(dialogState.confirmText, '确定')
  assert.equal(dialogState.cancelText, '取消')
  assert.equal(dialogState.showCancel, true)
  pick('confirm')
  assert.deepEqual(calls, [{ confirm: true, cancel: false }])
  assert.equal(dialogState.kind, '', '选完就关')

  showModal({ title: 'x', success: (res) => calls.push(res) })
  pick('cancel')
  assert.deepEqual(calls[1], { confirm: false, cancel: true })
})

test('destructiveConfirmsAreRed', () => {
  showModal({ title: '自爆', confirmText: '自爆' })
  assert.equal(dialogState.danger, true)
  showModal({ title: '清理', confirmText: '清理', confirmColor: '#E0403A' })
  assert.equal(dialogState.danger, true, '原来标了红色的也算')
  showModal({ title: '申请换座', confirmText: '申请' })
  assert.equal(dialogState.danger, false)
  pick('cancel')
})

test('alertHasNoCancel', () => {
  let done = 0
  showModal({ title: '已退出房间', content: '房主已解散房间', showCancel: false, success: () => { done += 1 } })
  assert.equal(dialogState.showCancel, false)
  pick('confirm')
  assert.equal(done, 1)
})

test('actionSheetReturnsTheTappedIndexOrFails', () => {
  const picked = []
  const failed = []
  showActionSheet({ itemList: ['暂停计时', '提前结束'], success: (res) => picked.push(res.tapIndex), fail: () => failed.push(1) })
  assert.equal(dialogState.kind, 'sheet')
  assert.deepEqual(dialogState.items.map((item) => [item.label, item.danger]), [['暂停计时', false], ['提前结束', true]])
  pick(1)
  assert.deepEqual(picked, [1])
  showActionSheet({ itemList: ['A'], success: (res) => picked.push(res.tapIndex), fail: () => failed.push(1) })
  pick('cancel')
  assert.deepEqual(failed, [1], '点取消或遮罩走 fail，和 uni 一样')
})

test('aSheetCanCarryALineOfContent', () => {
  showActionSheet({ title: '9 号 P9 · 白痴 · 存活', content: '投 10 号', itemList: ['点杀 9 号'] })
  assert.equal(dialogState.title, '9 号 P9 · 白痴 · 存活')
  assert.equal(dialogState.content, '投 10 号')
  pick('cancel')
})

test('aNewDialogCancelsTheOneStillOpen', () => {
  const results = []
  showModal({ title: 'first', success: (res) => results.push(['first', res.confirm]) })
  showModal({ title: 'second', success: (res) => results.push(['second', res.confirm]) })
  assert.deepEqual(results, [['first', false]])
  assert.equal(dialogState.title, 'second')
  pick('confirm')
  assert.deepEqual(results[1], ['second', true])
})
