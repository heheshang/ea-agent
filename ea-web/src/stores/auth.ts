import { defineStore } from 'pinia'
import { post } from '../api/http'
import type { LoginResponse } from '../api/types'

const K = {
  token: 'ea:token',
  tenantId: 'ea:tenantId',
  userId: 'ea:userId',
  role: 'ea:role',
  name: 'ea:name',
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem(K.token) ?? '',
    tenantId: localStorage.getItem(K.tenantId) ?? '',
    role: localStorage.getItem(K.role) ?? '',
    name: localStorage.getItem(K.name) ?? '',
  }),
  getters: {
    loggedIn: (s) => s.token !== '',
  },
  actions: {
    async login(loginName: string, password: string) {
      const r: LoginResponse = await post('/auth/login', { loginName, password })
      this.token = r.token
      this.tenantId = String(r.tenantId)
      this.role = r.role
      this.name = r.name
      localStorage.setItem(K.token, r.token)
      localStorage.setItem(K.tenantId, String(r.tenantId))
      localStorage.setItem(K.userId, String(r.userId))
      localStorage.setItem(K.role, r.role)
      localStorage.setItem(K.name, r.name)
    },
    logout() {
      this.token = ''
      this.tenantId = ''
      this.role = ''
      this.name = ''
      for (const v of Object.values(K)) localStorage.removeItem(v)
    },
  },
})