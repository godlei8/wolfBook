import { ref } from 'vue'
import type { AdminAiConfig } from '../../types'
import { DRAFT_FIELDS, cloneConfig, rebaseDraft, storableDraft } from '../../utils/aiAdmin'

// 编排页的草稿：改了先在右边试，点「发布」才对玩家生效。存在这台电脑的浏览器里，刷新不丢；
// 存之前把 Key 换回线上的打码值，浏览器里不留明文 Key。localStorage 用不了时只放在内存里。

const STORAGE_KEY = 'wolfbook_ai_draft'

export interface ComposeDraft {
  config: AdminAiConfig
  modelId: number | null
  /** 草稿是从哪份线上设置改起的：线上变了时，没动过的字段跟上新值 */
  baseConfig: AdminAiConfig
  baseModelId: number | null
  baseVersionId: number | null
}

/** 草稿里改了几处（编排页算好写进来），刷新 / 关页面前据此提醒 */
export const draftChangeCount = ref(0)

/** 问答日志「拿到编排里调试」：放进预览输入框的问题，编排页取走后清空 */
export const pendingQuestion = ref<string | null>(null)

/** 数据页点「问得最多」「最慢的几题」跳到问答日志：填进搜索框的问题 / 要打开的那条，日志页取走后清空 */
export const logsRequest = ref<{ search?: string; logId?: number } | null>(null)

export function readDraft(): ComposeDraft | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? (JSON.parse(raw) as ComposeDraft) : null
  } catch {
    return null
  }
}

export function writeDraft(draft: ComposeDraft, live: AdminAiConfig) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ ...draft, config: storableDraft(draft.config, live) }))
  } catch {
    // 无痕模式、存储满了：草稿只留在内存里
  }
}

export function clearDraft() {
  try {
    localStorage.removeItem(STORAGE_KEY)
  } catch {
    // 同上
  }
}

/**
 * 打开编排页时拿到的草稿：没有存过就从线上设置开一份；存过的话，线上在此期间变了，
 * 没动过的字段跟上新值、动过的保留，模型没换过的也跟线上走
 */
export function restoreDraft(live: AdminAiConfig, liveModelId: number | null, liveVersionId: number | null): ComposeDraft {
  const saved = readDraft()
  if (!saved) {
    return { config: cloneConfig(live), modelId: liveModelId, baseConfig: cloneConfig(live), baseModelId: liveModelId, baseVersionId: liveVersionId }
  }
  return {
    config: rebaseDraft(saved.baseConfig, live, saved.config, DRAFT_FIELDS),
    modelId: saved.modelId === saved.baseModelId ? liveModelId : saved.modelId,
    baseConfig: cloneConfig(live),
    baseModelId: liveModelId,
    baseVersionId: saved.baseVersionId,
  }
}
