import { defineStore } from 'pinia';
import * as authApi from '../api/auth';
import type { UserInfo } from '../api/types';

const tokenKey = 'smart_worksite_token';
const userKey = 'smart_worksite_user';

function readStoredUser() {
  const raw = localStorage.getItem(userKey);
  if (!raw) return null;
  try { return JSON.parse(raw) as UserInfo; } catch { return null; }
}

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem(tokenKey) || '',
    user: readStoredUser() as UserInfo | null,
    permissions: readStoredUser()?.permissions || [] as string[],
    roles: readStoredUser()?.roles || [] as string[],
    loading: false,
    error: ''
  }),
  getters: {
    isLoggedIn: (state) => Boolean(state.token),
    displayName: (state) => state.user?.realName || state.user?.username || '未登录用户',
    isPlatformAdmin: (state) => state.roles.includes('PLATFORM_ADMIN')
  },
  actions: {
    persistUser(user: UserInfo | null) {
      this.user = user;
      this.permissions = user?.permissions || [];
      this.roles = user?.roles || [];
      if (user) localStorage.setItem(userKey, JSON.stringify(user));
      else localStorage.removeItem(userKey);
    },
    setToken(token: string) {
      this.token = token;
      localStorage.setItem(tokenKey, token);
    },
    async login(payload: { username: string; password: string }) {
      this.loading = true;
      this.error = '';
      try {
        const result = await authApi.login(payload);
        this.setToken(result.accessToken);
        this.persistUser(result.user);
      } catch (error) {
        this.error = error instanceof Error ? error.message : '登录失败';
        throw error;
      } finally {
        this.loading = false;
      }
    },
    async fetchCurrentUser() {
      if (!this.token) return null;
      this.loading = true;
      this.error = '';
      try {
        const user = await authApi.fetchCurrentUser();
        this.persistUser(user);
        return user;
      } catch (error) {
        this.error = error instanceof Error ? error.message : '获取当前用户失败';
        throw error;
      } finally {
        this.loading = false;
      }
    },
    async logout() {
      try { await authApi.logout(); } catch { /* ignore logout api failure */ }
      this.clearAuthState();
    },
    clearAuthState() {
      this.token = '';
      this.error = '';
      this.loading = false;
      this.persistUser(null);
      localStorage.removeItem(tokenKey);
    },
    hasPermission(permission?: string) {
      return !permission || this.permissions.includes(permission);
    }
  }
});
