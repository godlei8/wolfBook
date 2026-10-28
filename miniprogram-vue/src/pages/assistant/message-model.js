function resolveContentFormat(message) {
  if (message?.contentFormat) {
    return message.contentFormat
  }
  return message?.role === 'ASSISTANT' ? 'MARKDOWN' : 'PLAIN_TEXT'
}

function filterVisibleCitations(citations = [], boards = []) {
  let visibleCitations = Array.isArray(citations) ? citations : []

  if (visibleCitations.length && Array.isArray(boards) && boards.length) {
    const boardIds = new Set(boards.map((board) => String(board.id)))
    visibleCitations = visibleCitations.filter((citation) => {
      if (citation?.sourceType !== 'STRUCTURED') {
        return true
      }
      return !boardIds.has(String(citation.sourceId))
    })
  }

  return visibleCitations.filter((citation) => citation?.sourceType === 'WEB')
}

// 流式回答边收边按 markdown 渲染（流缓冲已经限了刷新频率），不然用户一直看到「## 结论」「- 」这些原始符号
function resolveRenderMode(role, contentFormat) {
  if (role !== 'ASSISTANT') {
    return 'plain'
  }
  return contentFormat === 'MARKDOWN' ? 'markdown' : 'plain'
}

export function normalizeChatMessage(message, renderers) {
  const citations = Array.isArray(message?.citations) ? message.citations : []
  const recommendedBoards = Array.isArray(message?.recommendedBoards) ? message.recommendedBoards : []
  const contentFormat = resolveContentFormat(message)
  const renderMode = resolveRenderMode(message?.role, contentFormat)
  const content = message?.content || ''

  return {
    ...message,
    contentFormat,
    renderMode,
    visibleCitations: filterVisibleCitations(citations, recommendedBoards),
    renderedContent: renderMode === 'markdown' ? renderers.markdownRenderer(content) : content,
  }
}
