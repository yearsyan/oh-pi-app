// Minimal, dependency-free Markdown renderer.
// All source text is HTML-escaped first; the output is safe for v-html.

const ESC: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
}

function esc(s: string): string {
  return s.replace(/[&<>"']/g, (c) => ESC[c])
}

// Escape inline code segments and apply inline formatting to the rest.
function inline(s: string): string {
  if (!s) return ''
  const parts = s.split(/(`[^`\n]+`)/g)
  return parts
    .map((p) => {
      if (p.startsWith('`') && p.endsWith('`') && p.length > 2) {
        return `<code>${esc(p.slice(1, -1))}</code>`
      }
      let out = esc(p)
      out = out.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
      out = out.replace(/(^|[^*])\*([^*\n]+)\*(?!\*)/g, '$1<em>$2</em>')
      out = out.replace(/~~([^~]+)~~/g, '<del>$1</del>')
      out = out.replace(
        /\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g,
        '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>',
      )
      return out
    })
    .join('')
}

interface Block {
  tag: string
  html: string
}

function renderTable(lines: string[]): string {
  const rows = lines
    .map((l) =>
      l
        .trim()
        .replace(/^\|/, '')
        .replace(/\|$/, '')
        .split('|')
        .map((c) => c.trim()),
    )
    .filter((r, i) => i !== 1 || !r.every((c) => /^:?-+:?$/.test(c.replace(/\s/g, ''))))
  if (rows.length < 2) return ''
  const [, ...body] = rows
  const thead = `<thead><tr>${rows[0].map((c) => `<th>${inline(c)}</th>`).join('')}</tr></thead>`
  const tbody = body
    .map((r) => `<tr>${r.map((c) => `<td>${inline(c)}</td>`).join('')}</tr>`)
    .join('')
  return `<table>${thead}<tbody>${tbody}</tbody></table>`
}

export function renderMarkdown(src: string): string {
  const text = String(src ?? '')
  if (!text.trim()) return ''

  const blocks: Block[] = []
  const lines = text.split(/\r?\n/)
  let i = 0

  const flushParagraph = (buf: string[]) => {
    if (buf.length) {
      blocks.push({ tag: 'p', html: `<p>${inline(buf.join(' '))}</p>` })
      buf.length = 0
    }
  }

  let para: string[] = []
  let listType = ''
  let listHtml = ''

  const flushList = () => {
    if (listHtml) {
      blocks.push({ tag: listType, html: listType === 'ul' ? `<ul>${listHtml}</ul>` : `<ol>${listHtml}</ol>` })
      listHtml = ''
      listType = ''
    }
  }

  while (i < lines.length) {
    const line = lines[i]

    // fenced code block
    const fence = line.match(/^\s*```(\S*)\s*$/)
    if (fence) {
      flushParagraph(para)
      flushList()
      const lang = fence[1]
      const code: string[] = []
      i++
      while (i < lines.length && !/^\s*```\s*$/.test(lines[i])) {
        code.push(lines[i])
        i++
      }
      i++
      const langCls = lang ? ` class="language-${esc(lang)}"` : ''
      blocks.push({ tag: 'pre', html: `<pre><code${langCls}>${esc(code.join('\n'))}</code></pre>` })
      continue
    }

    const heading = line.match(/^(#{1,6})\s+(.*)$/)
    if (heading) {
      flushParagraph(para)
      flushList()
      const level = heading[1].length
      blocks.push({ tag: 'h', html: `<h${level}>${inline(heading[2])}</h${level}>` })
      i++
      continue
    }

    if (/^\s*([-*_])\s*\1\s*\1\s*$/.test(line)) {
      flushParagraph(para)
      flushList()
      blocks.push({ tag: 'hr', html: '<hr>' })
      i++
      continue
    }

    const quote = line.match(/^\s*>\s?(.*)$/)
    if (quote) {
      flushParagraph(para)
      flushList()
      const inner: string[] = [quote[1]]
      i++
      while (i < lines.length && /^\s*>\s?/.test(lines[i])) {
        inner.push(lines[i].replace(/^\s*>\s?/, ''))
        i++
      }
      blocks.push({ tag: 'blockquote', html: `<blockquote>${inline(inner.join(' '))}</blockquote>` })
      continue
    }

    // tables: header row followed by |---| separator row
    if (
      line.includes('|') &&
      i + 1 < lines.length &&
      /^\s*\|?[\s:|-]+\|[\s:|-]*\|?\s*$/.test(lines[i + 1]) &&
      lines[i + 1].includes('-')
    ) {
      flushParagraph(para)
      flushList()
      const tableLines: string[] = [line]
      i++
      while (i < lines.length && lines[i].trim().includes('|')) {
        tableLines.push(lines[i])
        i++
      }
      const html = renderTable(tableLines)
      if (html) blocks.push({ tag: 'table', html })
      continue
    }

    const ul = line.match(/^\s*[-*+]\s+(.*)$/)
    if (ul) {
      flushParagraph(para)
      if (listType && listType !== 'ul') flushList()
      listType = 'ul'
      listHtml += `<li>${inline(ul[1])}</li>`
      i++
      continue
    }

    const ol = line.match(/^\s*\d+\.\s+(.*)$/)
    if (ol) {
      flushParagraph(para)
      if (listType && listType !== 'ol') flushList()
      listType = 'ol'
      listHtml += `<li>${inline(ol[1])}</li>`
      i++
      continue
    }

    if (line.trim() === '') {
      flushParagraph(para)
      flushList()
      i++
      continue
    }

    para.push(line.trim())
    i++
  }

  flushParagraph(para)
  flushList()

  return blocks.map((b) => b.html).join('\n')
}
