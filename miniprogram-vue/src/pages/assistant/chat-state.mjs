export function buildChatViewportState(mode, latestMessageId, currentScrollTop = 0) {
  if (mode === 'reset') {
    return {
      scrollTop: 0,
      scrollIntoView: '',
    }
  }

  return {
    scrollTop: currentScrollTop,
    scrollIntoView: latestMessageId ? `msg-${latestMessageId}` : '',
  }
}
