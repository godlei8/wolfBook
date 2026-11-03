/** 问答日志、调试台里的回答类型 */
export function answerTypeLabel(answerType: string) {
  if (answerType === 'STRUCTURED_RECOMMENDATION') return '推荐板子'
  if (answerType === 'RAG_ANSWER') return '按资料回答'
  if (answerType === 'WEB_AUGMENTED_ANSWER') return '联网回答'
  if (answerType === 'REFUSAL') return '婉拒'
  return answerType || '未知'
}

/** 调试台里问题被判成了哪一类 */
export function intentLabel(intent: string | null) {
  if (intent === 'RECOMMEND') return '推荐板子'
  if (intent === 'KNOWLEDGE') return '按资料回答'
  if (intent === 'REFUSE') return '直接婉拒'
  return '流程出错'
}

/** 命中资料的类型 */
export function hitTypeLabel(sourceType: string) {
  if (sourceType === 'BOARD') return '板子'
  if (sourceType === 'ROLE') return '角色'
  if (sourceType === 'WORLD') return '世界观'
  if (sourceType === 'DOCUMENT') return '上传文档'
  return sourceType || '资料'
}

/** 失败类型的说明：NoModelKey 之类的英文名看不懂 */
export function failureLabel(failureType: string | null) {
  if (!failureType) return ''
  if (failureType === 'NoModelKey') return '当前模型没填 Key'
  if (failureType === 'EmptyAnswer') return '模型返回了空内容'
  if (failureType.includes('Timeout')) return '模型超时'
  if (failureType === 'IllegalStateException') return '模型接口报错（Key、余额或地址不对）'
  return failureType
}

