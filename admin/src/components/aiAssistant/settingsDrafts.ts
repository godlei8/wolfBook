import { computed, ref } from 'vue'
import type { AdminAiConfig } from '../../types'
import { ADVANCED_FIELDS, PERSONA_FIELDS, countChanges } from '../../utils/aiAdmin'

// 设置里「话术与人设」「高级」两份草稿记在模块里：在后台切块、切页面再回来都还在，只有保存或放弃才清掉
export const serverConfig = ref<AdminAiConfig | null>(null)
export const personaDraft = ref<AdminAiConfig | null>(null)
export const advancedDraft = ref<AdminAiConfig | null>(null)

export const personaChanges = computed(() =>
  serverConfig.value && personaDraft.value ? countChanges(serverConfig.value, personaDraft.value, PERSONA_FIELDS) : 0,
)
export const advancedChanges = computed(() =>
  serverConfig.value && advancedDraft.value ? countChanges(serverConfig.value, advancedDraft.value, ADVANCED_FIELDS) : 0,
)
export const settingsDirtyCount = computed(() => personaChanges.value + advancedChanges.value)
