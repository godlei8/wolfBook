// AI 回答预览用的极简 Markdown：小标题、加粗、列表、引用、段落。先整体转义再加标签，模型输出里的 HTML 不会被执行。

function escapeHtml(text: string) {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

function inline(text: string) {
  return escapeHtml(text)
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/`([^`]+)`/g, '<code>$1</code>')
}

export function renderMarkdown(markdown: string) {
  const lines = (markdown || '').replace(/\r\n/g, '\n').split('\n')
  const html: string[] = []
  let list: string[] = []
  let paragraph: string[] = []

  const flushList = () => {
    if (list.length) html.push(`<ul>${list.map((item) => `<li>${item}</li>`).join('')}</ul>`)
    list = []
  }
  const flushParagraph = () => {
    if (paragraph.length) html.push(`<p>${paragraph.join('<br/>')}</p>`)
    paragraph = []
  }

  for (const raw of lines) {
    const line = raw.trim()
    if (!line) {
      flushList()
      flushParagraph()
      continue
    }
    const heading = line.match(/^(#{1,4})\s+(.*)$/)
    if (heading) {
      flushList()
      flushParagraph()
      html.push(`<h4>${inline(heading[2])}</h4>`)
      continue
    }
    const bullet = line.match(/^(?:-|\*(?!\*)|\d+\.)\s*(.*)$/)
    if (bullet) {
      flushParagraph()
      list.push(inline(bullet[1]))
      continue
    }
    if (line.startsWith('>')) {
      flushList()
      flushParagraph()
      html.push(`<blockquote>${inline(line.replace(/^>\s?/, ''))}</blockquote>`)
      continue
    }
    flushList()
    paragraph.push(inline(line))
  }
  flushList()
  flushParagraph()
  return html.join('')
}
