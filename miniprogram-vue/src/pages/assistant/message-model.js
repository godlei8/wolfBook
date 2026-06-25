const AUTO_DEFER_MARKDOWN_THRESHOLD = 360

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

function resolveRenderMode(role, contentFormat, isStreaming) {
  if (role !== 'ASSISTANT') {
    return 'plain'
  }
  if (isStreaming) {
    return 'plain'
  }
  return contentFormat === 'MARKDOWN' ? 'markdown' : 'plain'
}

function shouldDeferMarkdownRender(renderMode, content, options = {}) {
  if (renderMode !== 'markdown') {
    return false
  }
  if (options.deferMarkdown) {
    return true
  }
  return String(content || '').length >= AUTO_DEFER_MARKDOWN_THRESHOLD
}

export function normalizeChatMessage(message, renderers) {
  const options = arguments[2] || {}
  const citations = Array.isArray(message?.citations) ? message.citations : []
  const recommendedBoards = Array.isArray(message?.recommendedBoards) ? message.recommendedBoards : []
  const contentFormat = resolveContentFormat(message)
  const renderMode = resolveRenderMode(message?.role, contentFormat, !!message?.isStreaming)
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
