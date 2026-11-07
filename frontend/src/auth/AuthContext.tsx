import React, { createContext, useCallback, useContext, useEffect, useState } from 'react';
import { authApi } from '../api/auth';
import { clearAuth, getCachedUser, getToken, setCachedUser, setToken } from './token';
import type { UserDTO } from '../types/auth';

interface AuthState {
  user: UserDTO | null;
  /** 启动时是否正在用 /me 校准登录态（有 token 时为 true） */
  initializing: boolean;
  login: (userid: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  setUser: (user: UserDTO | null) => void;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  // 有 token 时先用本地缓存，避免刷新闪"未登录"
  const [user, setUserState] = useState<UserDTO | null>(() => (getToken() ? getCachedUser() : null));
  const [initializing, setInitializing] = useState(() => Boolean(getToken()));

  // 启动恢复登录态：缓存先显示，/me 校准（失败说明 token 已失效，清本地）
  useEffect(() => {
    if (!getToken()) return;
    authApi
      .me()
      .then((u) => setUserState(u))
      .catch(() => {
        clearAuth();
        setUserState(null);
      })
      .finally(() => setInitializing(false));
  }, []);

  const login = useCallback(async (userid: string, password: string) => {
    const resp = await authApi.login({ userid, password });
    setToken(resp.token);
    setCachedUser(resp.user);
    setUserState(resp.user);
  }, []);

  const logout = useCallback(async () => {
    try {
      await authApi.logout();
    } catch {
      // 后端登出失败（token 已失效等）也照样清理本地
    }
    clearAuth();
    setUserState(null);
  }, []);

  const setUser = useCallback((u: UserDTO | null) => {
    setUserState(u);
    setCachedUser(u);
  }, []);

  return (
    <AuthContext.Provider value={{ user, initializing, login, logout, setUser }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth 必须在 AuthProvider 内使用');
  return ctx;
}
