const BODY_STYLE = 'margin:0 0 8px 0;line-height:1.55;font-size:12px;color:#ece6d8;word-break:break-word;'
const H2_STYLE = 'margin:0 0 8px 0;font-size:18px;font-weight:800;color:#fff4d3;line-height:1.25;'
const H3_STYLE = 'margin:10px 0 6px 0;font-size:13px;font-weight:800;color:#ffd86b;line-height:1.35;'
const LIST_ROW_STYLE = 'margin:0 0 6px 0;line-height:1.5;font-size:12px;color:#e5dcc8;word-break:break-word;'
const LIST_MARK_STYLE = 'display:inline-block;width:13px;color:#ffd86b;font-weight:800;'
const BLOCKQUOTE_STYLE = 'margin:0 0 8px 0;padding:6px 8px;border-left:3px solid rgba(255,192,0,0.45);background:rgba(255,255,255,0.04);color:#d7cdb7;line-height:1.55;font-size:12px;'
const CODE_BLOCK_STYLE = 'margin:0 0 8px 0;padding:8px;border-radius:8px;background:#111111;color:#f7e8b4;font-size:11px;line-height:1.5;white-space:pre-wrap;word-break:break-word;border:1px solid rgba(255,192,0,0.14);'
const INLINE_CODE_STYLE = 'display:inline-block;padding:1px 4px;border-radius:4px;background:rgba(255,192,0,0.1);color:#ffd86b;font-size:11px;'
const LINK_STYLE = 'color:#ffd86b;text-decoration:underline;'
const STRONG_STYLE = 'font-weight:700;color:#fff7e6;'
const CURSOR_HTML = '<span style="display:inline-block;margin-left:4px;color:#ffd86b;font-weight:700;">▌</span>'

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
  html = html.replace(/(^|\s)#{1,6}\s+/g, '$1')
  html = html.replace(/\s+-\s+/g, ' ')
  html = html.replace(/\*\*([^*]+)\*\*/g, `<strong style="${STRONG_STYLE}">$1</strong>`)
  html = html.replace(/`([^`]+)`/g, `<code style="${INLINE_CODE_STYLE}">$1</code>`)
  return html
}

function fingerprintLine(line = '') {
  return String(line || '')
    .toLowerCase()
    .replace(/https?:\/\/\S+/g, '')
    .replace(/^#{1,6}\s*/, '')
    .replace(/^[-*]\s+/, '')
    .replace(/^\d+[.、．]\s*/, '')
    .replace(/\*\*|`|_/g, '')
    .replace(/[，。！？、；：：“”‘’（）《》【】「」『』,.!?;:()[\]{}"'`~@#$%^&+=|\\/<>*\-—_\s]+/g, '')
}

function hasSimilarFingerprint(fingerprint, recentFingerprints) {
  if (!fingerprint || fingerprint.length < 12) {
    return false
  }
  return recentFingerprints.some((existing) => {
    if (fingerprint === existing) {
      return true
    }
    const shorter = fingerprint.length <= existing.length ? fingerprint : existing
    const longer = fingerprint.length > existing.length ? fingerprint : existing
    return shorter.length >= 24 && longer.includes(shorter)
  })
}

function paragraphToHtml(lines) {
  return `<p style="${BODY_STYLE}">${lines.map(renderInline).join('<br/>')}</p>`
}

function listToHtml(items, ordered = false) {
  return items
    .map((item, index) => {
      const marker = ordered ? `${index + 1}.` : '•'
      return `<p style="${LIST_ROW_STYLE}"><span style="${LIST_MARK_STYLE}">${marker}</span>${renderInline(item)}</p>`
    })
    .join('')
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

function normalizeMarkdownSource(markdown = '') {
  const prepared = String(markdown || '')
    .replace(/\r\n/g, '\n')
    .replace(/\u00a0/g, ' ')
    .replace(/\s+-\s+(?=(?:#{1,6}\s*)?[\u4e00-\u9fa5A-Za-z0-9]{1,12}[：:])/g, '\n- ')
    .replace(/\s+-\s+(?=(?:若|如果|当|可|可以|不|在|被|否则|同时|然后|接刀|小贴士|注意))/g, '\n- ')

  const lines = prepared
    .split('\n')

  const normalized = []
  let previousMeaningful = ''
  const recentFingerprints = []
  let skippingAttribution = false

  for (const rawLine of lines) {
    let line = rawLine
      .replace(/^\s*[-*]\s+(#{1,6}\s+)/, '$1')
      .replace(/^\s*[-*]\s+[-*]\s+/, '- ')
      .replace(/^\s*[-*]\s+(\d+[.、．]\s*)/, '$1')
      .replace(/^(\s*#{1,6})\s*(\d+[.、．]\s*)/, '$1 ')

    let meaningful = line.trim()
    const sectionMatch = meaningful.match(/^[-*]\s*(结论|技能|规则拆解|你可以怎么做|小贴士|注意事项|常见问题)[：:]?\s*$/)
    if (sectionMatch) {
      line = `### ${sectionMatch[1]}`
      meaningful = line
    }
    if (/^#{1,6}\s*(依据|参考来源|来源|使用说明)\s*$/.test(meaningful)) {
      skippingAttribution = true
      continue
    }
    if (skippingAttribution) {
      if (/^#{1,6}\s+/.test(meaningful)) {
        skippingAttribution = false
      } else {
        continue
      }
    }
    if (meaningful && meaningful === previousMeaningful) {
      continue
    }
    const fingerprint = fingerprintLine(meaningful)
    if (hasSimilarFingerprint(fingerprint, recentFingerprints)) {
      continue
    }
    if (meaningful) {
      previousMeaningful = meaningful
      if (fingerprint.length >= 12) {
        recentFingerprints.push(fingerprint)
        if (recentFingerprints.length > 18) {
          recentFingerprints.shift()
        }
      }
    }
    normalized.push(line)
  }

  return normalized.join('\n')
}

export function markdownToRichText(markdown = '', options = {}) {
  const source = normalizeMarkdownSource(markdown)
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
