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

// 只有进页面时一次性铺开的历史消息才分段延后渲染；刚回答完的消息直接渲染，避免答完又闪回原始文本
function shouldDeferMarkdownRender(renderMode, content, options = {}) {
  return renderMode === 'markdown' && !!options.deferMarkdown && !!content
}

export function normalizeChatMessage(message, renderers) {
  const options = arguments[2] || {}
  const citations = Array.isArray(message?.citations) ? message.citations : []
  const recommendedBoards = Array.isArray(message?.recommendedBoards) ? message.recommendedBoards : []
  const contentFormat = resolveContentFormat(message)
  const renderMode = resolveRenderMode(message?.role, contentFormat)
  const content = message?.content || ''

  if (options.preserveRenderedContent) {
    return {
      ...message,
      contentFormat,
      renderMode: message?.renderMode || renderMode,
      isRenderPending: !!message?.isRenderPending,
      visibleCitations: filterVisibleCitations(citations, recommendedBoards),
      renderedContent: message?.renderedContent || content,
    }
  }

  if (shouldDeferMarkdownRender(renderMode, content, options)) {
    return {
      ...message,
      contentFormat,
      renderMode: 'plain',
      isRenderPending: true,
      visibleCitations: filterVisibleCitations(citations, recommendedBoards),
      renderedContent: content,
    }
  }

  const renderedContent = renderMode === 'markdown'
    ? renderers.markdownRenderer(content)
    : content

  return {
    ...message,
    contentFormat,
    renderMode,
    isRenderPending: false,
    visibleCitations: filterVisibleCitations(citations, recommendedBoards),
    renderedContent,
  }
}
