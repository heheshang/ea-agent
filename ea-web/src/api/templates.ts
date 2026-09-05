import { del, get, post, put } from './http'
import type { Template } from './types'

/** 模板管理 /api/templates：租户维度 CRUD + 审核流（submit / approve / reject）。 */
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

export async function approveTemplate(id: number): Promise<Template> {
  return post<Template>(`/templates/${id}/approve`)
}

export async function rejectTemplate(id: number): Promise<Template> {
  return post<Template>(`/templates/${id}/reject`)
}

export async function deleteTemplate(id: number): Promise<void> {
  await del(`/templates/${id}`)
}