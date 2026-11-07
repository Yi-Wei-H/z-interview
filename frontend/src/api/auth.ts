import request from './request';
import type { LoginResponse, RegisterRequest, UserDTO } from '../types/auth';

export const authApi = {
  login: (data: { userid: string; password: string }) =>
    request.post<LoginResponse>('/api/auth/login', data),

  register: (data: RegisterRequest) =>
    request.post<UserDTO>('/api/auth/register', data),

  logout: () => request.post<void>('/api/auth/logout'),

  me: () => request.get<UserDTO>('/api/auth/me'),

  updateUsername: (username?: string) =>
    request.put<UserDTO>('/api/user/username', { username }),

  changePassword: (oldPassword: string, newPassword: string) =>
    request.put<void>('/api/user/password', { oldPassword, newPassword }),
};
