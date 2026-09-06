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
      // 仅「未认证」类业务码清 token 回登录页：10002（UNAUTHENTICATED）/11003 需重登；
      // 10003（FORBIDDEN）是业务级无权限（如门控非发起者），只提示，绝不登出。
      if (body.code === 10002 || body.code === 11003) {
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
    // HTTP 401（token 过期/无效、租户失效/不匹配）：清 token 回登录页，与业务码分支一致
    if (error?.response?.status === 401) {
      localStorage.removeItem('ea:token')
      if (!location.pathname.startsWith('/login')) {
        location.href = '/login'
      }
      return Promise.reject(error)
    }
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

export async function del<T>(url: string): Promise<T> {
  const resp = await http.delete<Result<T>>(url)
  return resp.data.data
}