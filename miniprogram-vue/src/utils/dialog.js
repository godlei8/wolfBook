// 统一样式的弹窗和底部弹出菜单（用户给定：小程序里的弹窗都用夜镇卡牌风格，不用微信原生的白框）。
// showModal / showActionSheet 和 uni.showModal / uni.showActionSheet 同一套参数和回调，换个函数名就能用；
// 画出来靠页面里的 components/NtDialogHost.vue（每个要弹窗的页面放一个）。纯状态，有单测（tests/dialog.test.mjs）。
import { reactive } from 'vue'

// 确定键是这些字的，按危险操作画成红色
const DANGER_WORDS = ['删除', '清空', '点杀', '自爆', '解散', '结束', '请离', '举报', '离开', '仍要删除', '清理本地']

function isDangerColor(color) {
  return /^#(e0|ff|f0|d6|e5)[0-9a-f]{4}$/i.test(String(color || '').trim()) && !/^#f2b33d$/i.test(String(color).trim())
}

export const dialogState = reactive({
  kind: '', // '' | 'modal' | 'sheet'
  title: '',
  content: '',
  confirmText: '确定',
  cancelText: '取消',
  showCancel: true,
  danger: false,
  items: [],
})

let pending = null

function settleOpen() {
  if (!pending) return
  const current = pending
  pending = null
  dialogState.kind = ''
  if (current.kind === 'modal') {
    current.success?.({ confirm: false, cancel: true })
  } else {
    current.fail?.({ errMsg: 'showActionSheet:fail cancel' })
  }
  current.complete?.()
}

/** 和 uni.showModal 一样：success({ confirm, cancel })。 */
export function showModal(options = {}) {
  settleOpen()
  const confirmText = options.confirmText || '确定'
  Object.assign(dialogState, {
    kind: 'modal',
    title: options.title || '',
    content: options.content || '',
    confirmText,
    cancelText: options.cancelText || '取消',
    showCancel: options.showCancel !== false,
    danger: Boolean(options.danger) || isDangerColor(options.confirmColor) || DANGER_WORDS.some((word) => confirmText.includes(word)),
    items: [],
  })
  pending = { kind: 'modal', success: options.success, complete: options.complete }
}

/** 和 uni.showActionSheet 一样：success({ tapIndex })，点取消或遮罩走 fail。 */
export function showActionSheet(options = {}) {
  settleOpen()
  Object.assign(dialogState, {
    kind: 'sheet',
    title: options.title || '',
    // uni 原生没有：底部菜单上方的一段说明（法官点座位时放这个人的经历）
    content: options.content || '',
    confirmText: '确定',
    cancelText: '取消',
    showCancel: true,
    danger: false,
    items: (options.itemList || []).map((label) => ({ label, danger: DANGER_WORDS.some((word) => String(label).includes(word)) })),
  })
  pending = { kind: 'sheet', success: options.success, fail: options.fail, complete: options.complete }
}

/** 弹窗里点了什么：'confirm' / 'cancel'，菜单里点了第几项（数字）。NtDialogHost 调。 */
export function pick(choice) {
  if (!pending) return
  const current = pending
  pending = null
  dialogState.kind = ''
  if (current.kind === 'modal') {
    const confirm = choice === 'confirm'
    current.success?.({ confirm, cancel: !confirm })
  } else if (typeof choice === 'number') {
    current.success?.({ tapIndex: choice })
  } else {
    current.fail?.({ errMsg: 'showActionSheet:fail cancel' })
  }
  current.complete?.()
}
