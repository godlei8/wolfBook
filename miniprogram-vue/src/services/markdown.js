const BODY_STYLE = 'margin:0 0 12rpx 0;line-height:1.7;font-size:24rpx;color:#ece6d8;word-break:break-word;'
const H2_STYLE = 'margin:0 0 14rpx 0;font-size:28rpx;font-weight:700;color:#fff4d3;line-height:1.4;'
const H3_STYLE = 'margin:6rpx 0 12rpx 0;font-size:24rpx;font-weight:700;color:#ffd86b;line-height:1.45;'
const LIST_STYLE = 'margin:0 0 14rpx 0;padding-left:28rpx;color:#e5dcc8;line-height:1.7;font-size:23rpx;'
const BLOCKQUOTE_STYLE = 'margin:0 0 14rpx 0;padding:12rpx 16rpx;border-left:5rpx solid rgba(255,192,0,0.45);background:rgba(255,255,255,0.04);color:#d7cdb7;line-height:1.7;font-size:23rpx;'
const CODE_BLOCK_STYLE = 'margin:0 0 14rpx 0;padding:14rpx;border-radius:16rpx;background:#111111;color:#f7e8b4;font-size:22rpx;line-height:1.65;white-space:pre-wrap;word-break:break-word;border:1rpx solid rgba(255,192,0,0.14);'
const INLINE_CODE_STYLE = 'display:inline-block;padding:2rpx 8rpx;border-radius:8rpx;background:rgba(255,192,0,0.1);color:#ffd86b;font-size:22rpx;'
const LINK_STYLE = 'color:#ffd86b;text-decoration:underline;'
const STRONG_STYLE = 'font-weight:700;color:#fff7e6;'
const CURSOR_HTML = '<span style="display:inline-block;margin-left:6rpx;color:#ffd86b;font-weight:700;">▌</span>'

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
  const body = items.map((item) => `<li style="margin-bottom:8rpx;">${renderInline(item)}</li>`).join('')
  return `<${tag} style="${LIST_STYLE}">${body}</${tag}>`
}

function blockquoteToHtml(lines) {
  return `<blockquote style="${BLOCKQUOTE_STYLE}">${lines.map(renderInline).join('<br/>')}</blockquote>`
}

function codeBlockToHtml(lines) {
  return `<pre style="${CODE_BLOCK_STYLE}"><code>${escapeHtml(lines.join('\n'))}</code></pre>`
}

export function plainTextToRichText(text = '') {
  const escaped = escapeHtml(text).replace(/\n/g, '<br/>')
  return `<p style="${BODY_STYLE}">${escaped || '&nbsp;'}</p>`
}

export function markdownToRichText(markdown = '', options = {}) {
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

    if (/^[-*]\s+/.test(trimmed)) {
      const items = []
      while (index < lines.length && /^[-*]\s+/.test(lines[index].trim())) {
        items.push(lines[index].trim().replace(/^[-*]\s+/, ''))
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
        /^[-*]\s+/.test(currentTrimmed) ||
        /^\d+\.\s+/.test(currentTrimmed)
      ) {
        break
      }
      paragraphLines.push(currentTrimmed)
      index += 1
    }
    if (paragraphLines.length) {
      blocks.push(paragraphToHtml(paragraphLines))
      continue
    }
  }

  if (!blocks.length) {
    blocks.push(plainTextToRichText(source))
  }

  if (options.streaming) {
    const lastIndex = blocks.length - 1
    blocks[lastIndex] = blocks[lastIndex].replace(/<\/(p|blockquote|pre)>$/, `${CURSOR_HTML}</$1>`)
  }

  return blocks.join('')
}
