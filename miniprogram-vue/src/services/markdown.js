const BODY_STYLE = 'margin:0 0 16rpx 0;line-height:1.75;font-size:30rpx;color:#f4ead5;word-break:break-word;'
// style 属性用双引号包着，里面的字体名只能用单引号，否则属性提前结束、整段样式失效
const H2_STYLE = "margin:0 0 18rpx 0;font-size:34rpx;font-weight:900;color:#f4ead5;line-height:1.45;font-family:'Noto Serif SC','Songti SC',serif;"
const H3_STYLE = 'margin:8rpx 0 14rpx 0;font-size:30rpx;font-weight:700;color:#f2b33d;line-height:1.5;'
const LIST_STYLE = 'margin:0 0 18rpx 0;padding-left:32rpx;color:#e8dff0;line-height:1.75;font-size:28rpx;'
const BLOCKQUOTE_STYLE = 'margin:0 0 18rpx 0;padding:16rpx 20rpx;border-left:6rpx solid #f2b33d;border-radius:12rpx;background:#2c2539;color:#d9cfe6;line-height:1.75;font-size:28rpx;'
const CODE_BLOCK_STYLE = 'margin:0 0 18rpx 0;padding:18rpx;border-radius:18rpx;background:#07060a;color:#f4ead5;font-size:24rpx;line-height:1.7;white-space:pre-wrap;word-break:break-word;border:2rpx solid #352d44;'
const INLINE_CODE_STYLE = 'display:inline-block;padding:2rpx 10rpx;border-radius:10rpx;background:#2c2539;color:#f2b33d;font-size:26rpx;'
const LINK_STYLE = 'color:#5fe3e0;text-decoration:underline;'
const STRONG_STYLE = 'font-weight:700;color:#f2b33d;'
const CURSOR_HTML = '<span style="display:inline-block;margin-left:8rpx;color:#5fe3e0;font-weight:700;">|</span>'
const DEFAULT_SEGMENT_CHAR_LIMIT = 520
// 无序列表项：「- 」「* 」，或「-」后面直接跟中文（有的模型输出「-经典高配」不带空格）
const BULLET = /^[-*](?:\s+|(?=[一-龥]))/

function escapeHtml(value = '') {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function renderInline(text = '') {
  let html = escapeHtml(text)
  html = html.replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, `<span style="${LINK_STYLE}">$1</span>`)
  html = html.replace(/\*\*([^*]+)\*\*/g, `<strong style="${STRONG_STYLE}">$1</strong>`)
  html = html.replace(/`([^`]+)`/g, `<code style="${INLINE_CODE_STYLE}">$1</code>`)
  return html
}

function paragraphToHtml(lines) {
  return `<p style="${BODY_STYLE}">${lines.map(renderInline).join('<br/>')}</p>`
}

function listToHtml(items, ordered = false) {
  const tag = ordered ? 'ol' : 'ul'
  const body = items.map((item) => `<li style="margin-bottom:10rpx;">${renderInline(item)}</li>`).join('')
  return `<${tag} style="${LIST_STYLE}">${body}</${tag}>`
}

function blockquoteToHtml(lines) {
  return `<blockquote style="${BLOCKQUOTE_STYLE}">${lines.map(renderInline).join('<br/>')}</blockquote>`
}

function codeBlockToHtml(lines) {
  return `<pre style="${CODE_BLOCK_STYLE}"><code>${escapeHtml(lines.join('\n'))}</code></pre>`
}

function markdownToHtmlBlocks(markdown = '', options = {}) {
  const source = String(markdown || '').replace(/\r\n/g, '\n')
  const lines = source.split('\n')
  const blocks = []
  let index = 0

  while (index < lines.length) {
    const line = lines[index]
    const trimmed = line.trim()

    if (!trimmed) {
      index += 1
      continue
    }

    if (trimmed.startsWith('```')) {
      const fenceLines = []
      index += 1
      while (index < lines.length && !lines[index].trim().startsWith('```')) {
        fenceLines.push(lines[index])
        index += 1
      }
      if (index < lines.length) {
        index += 1
      }
      blocks.push(codeBlockToHtml(fenceLines))
      continue
    }

    if (trimmed.startsWith('### ')) {
      blocks.push(`<h3 style="${H3_STYLE}">${renderInline(trimmed.slice(4))}</h3>`)
      index += 1
      continue
    }

    if (trimmed.startsWith('## ')) {
      blocks.push(`<h2 style="${H2_STYLE}">${renderInline(trimmed.slice(3))}</h2>`)
      index += 1
      continue
    }

    if (trimmed.startsWith('# ')) {
      blocks.push(`<h2 style="${H2_STYLE}">${renderInline(trimmed.slice(2))}</h2>`)
      index += 1
      continue
    }

    if (trimmed.startsWith('>')) {
      const quoteLines = []
      while (index < lines.length && lines[index].trim().startsWith('>')) {
        quoteLines.push(lines[index].trim().replace(/^>\s?/, ''))
        index += 1
      }
      blocks.push(blockquoteToHtml(quoteLines))
      continue
    }

    if (BULLET.test(trimmed)) {
      const items = []
      while (index < lines.length && BULLET.test(lines[index].trim())) {
        items.push(lines[index].trim().replace(BULLET, ''))
        index += 1
      }
      blocks.push(listToHtml(items, false))
      continue
    }

    if (/^\d+\.\s+/.test(trimmed)) {
      const items = []
      while (index < lines.length && /^\d+\.\s+/.test(lines[index].trim())) {
        items.push(lines[index].trim().replace(/^\d+\.\s+/, ''))
        index += 1
      }
      blocks.push(listToHtml(items, true))
      continue
    }

    const paragraphLines = []
    while (index < lines.length) {
      const current = lines[index]
      const currentTrimmed = current.trim()
      if (!currentTrimmed) {
        index += 1
        break
      }
      if (
        currentTrimmed.startsWith('#') ||
        currentTrimmed.startsWith('>') ||
        currentTrimmed.startsWith('```') ||
        BULLET.test(currentTrimmed) ||
        /^\d+\.\s+/.test(currentTrimmed)
      ) {
        break
      }
      paragraphLines.push(currentTrimmed)
      index += 1
    }

    if (paragraphLines.length) {
      blocks.push(paragraphToHtml(paragraphLines))
    }
  }

  if (!blocks.length) {
    blocks.push(plainTextToRichText(source))
  }

  if (options.streaming) {
    const lastIndex = blocks.length - 1
    blocks[lastIndex] = blocks[lastIndex].replace(/<\/(p|blockquote|pre)>$/, `${CURSOR_HTML}</$1>`)
  }

  return blocks
}

export function plainTextToRichText(text = '') {
  const escaped = escapeHtml(text).replace(/\n/g, '<br/>')
  return `<p style="${BODY_STYLE}">${escaped || '&nbsp;'}</p>`
}

export function markdownToRichTextSegments(markdown = '', options = {}) {
  const blocks = markdownToHtmlBlocks(markdown, options)
  if (!blocks.length) {
    return []
  }

  const segmentCharLimit = Number(options.segmentCharLimit || DEFAULT_SEGMENT_CHAR_LIMIT)
  const segments = []
  let currentSegment = ''

  blocks.forEach((block) => {
    if (currentSegment && currentSegment.length + block.length > segmentCharLimit) {
      segments.push(currentSegment)
      currentSegment = ''
    }
    currentSegment += block
  })

  if (currentSegment) {
    segments.push(currentSegment)
  }

  return segments
}

export function markdownToRichText(markdown = '', options = {}) {
  return markdownToHtmlBlocks(markdown, options).join('')
}
