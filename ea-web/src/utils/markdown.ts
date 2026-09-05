import { marked } from 'marked'
import DOMPurify from 'dompurify'

// 对话场景：GFM（表格/删除线/任务列表）+ 单换行折行（LLM 输出常以单换行分段）。
marked.setOptions({ gfm: true, breaks: true })

/**
 * Markdown → 净化后的 HTML（供 v-html 使用）。
 * 净化不可或缺：文本可能来自 LLM 输出/工具结果，未净化直接 v-html 存在 XSS 面。
 * 流式增量期间由调用方以纯文本展示，本函数只处理已完成的整段文本（无未闭合围栏闪烁）。
 */
export function renderMarkdown(text: string): string {
  if (!text) return ''
  const html = marked.parse(text) as string
  return DOMPurify.sanitize(html, { USE_PROFILES: { html: true } })
}