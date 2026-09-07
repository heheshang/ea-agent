import { del, get, post, put } from './http'
import type { CreateTemplateReviewRequest, Template, TemplateReview } from './types'

/** 模板管理 /api/templates：租户维度 CRUD、提交审核与人工批注/最终审核。 */
export async function listTemplates(channel?: string): Promise<Template[]> {
  return get<Template[]>('/templates', channel ? { channel } : undefined)
}

export async function getTemplate(id: number): Promise<Template> {
  return get<Template>(`/templates/${id}`)
}

export async function createTemplate(body: Pick<Template, 'title' | 'channel' | 'content'>): Promise<Template> {
  return post<Template>('/templates', body)
}

export async function updateTemplate(id: number, body: Pick<Template, 'title' | 'channel' | 'content'>): Promise<Template> {
  return put<Template>(`/templates/${id}`, body)
}

export async function submitTemplate(id: number): Promise<Template> {
  return post<Template>(`/templates/${id}/submit`)
}

export async function listTemplateReviews(id: number): Promise<TemplateReview[]> {
  return get<TemplateReview[]>(`/templates/${id}/reviews`)
}

export async function createTemplateReview(id: number, body: CreateTemplateReviewRequest): Promise<TemplateReview> {
  return post<TemplateReview>(`/templates/${id}/reviews`, body)
}

export async function deleteTemplate(id: number): Promise<void> {
  await del(`/templates/${id}`)
}