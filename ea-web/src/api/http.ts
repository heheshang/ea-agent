import axios, { type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import type { Result } from './types'

/**
 * axios 封装（7.3）：拦截器注入 X-Tenant-Id / X-Request-Id（每次请求唯一）/ Bearer token；
 * 响应统一解包 Result；非 0 错误码 → ElMessage + reject；401 → 回登录页。
 */
const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE ?? '/api',
  timeout: 15000,
})

http.interceptors.request.use((config) => {
  config.headers.set('X-Tenant-Id', localStorage.getItem('ea:tenantId') ?? '')
  config.headers.set('X-Request-Id', crypto.randomUUID())
  const token = localStorage.getItem('ea:token')
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  return config
})

http.interceptors.response.use(
  (resp) => {
    const body = resp.data as Result
    if (body && typeof body.code === 'number' && body.code !== 0) {
      ElMessage.error(`${body.code}: ${body.message}`)
      if (body.code === 10002 || body.code === 10003 || body.code === 11003) {
        localStorage.removeItem('ea:token')
        if (!location.pathname.startsWith('/login')) {
          location.href = '/login'
        }
      }
      return Promise.reject(new Error(body.message))
    }
    return resp
  },
  (error) => {
    ElMessage.error(error?.message ?? '网络错误')
    return Promise.reject(error)
  },
)

export async function get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  const resp = await http.get<Result<T>>(url, { params })
  return resp.data.data
}

export async function post<T>(url: string, body?: unknown, config?: AxiosRequestConfig): Promise<T> {
  const resp = await http.post<Result<T>>(url, body, config)
  return resp.data.data
}

export async function put<T>(url: string, body?: unknown, config?: AxiosRequestConfig): Promise<T> {
  const resp = await http.put<Result<T>>(url, body, config)
  return resp.data.data
}