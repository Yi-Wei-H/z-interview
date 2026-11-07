import { useState } from 'react';
import { Link } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import {
  User, Loader2, CheckCircle, XCircle, KeyRound, LogIn, IdCard,
} from 'lucide-react';
import { authApi } from '../api/auth';
import { useAuth } from '../auth/AuthContext';
import { ROUTES } from '../constants/routes';

const INPUT_CLASS = `w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600
  bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white
  placeholder:text-slate-400 focus:outline-none focus:ring-2
  focus:ring-primary-500/50 focus:border-primary-400 transition-shadow`;

const CARD_CLASS = `bg-white dark:bg-slate-800 rounded-2xl shadow-sm
  border border-slate-200 dark:border-slate-700 p-6`;

export default function ProfilePage() {
  const { user, setUser } = useAuth();

  // 改用户名表单
  const [username, setUsername] = useState(user?.username ?? '');
  const [savingUsername, setSavingUsername] = useState(false);
  // 改密码表单
  const [oldPassword, setOldPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [savingPassword, setSavingPassword] = useState(false);

  // Toast 通知提示（抄 SettingsPage 的 showToast 模式）
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null);
  const showToast = (message: string, type: 'success' | 'error' = 'success') => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 3000);
  };

  // 未登录：显示"请先登录"占位 + 去登录链接（不强制跳转）
  if (!user) {
    return (
      <div className="max-w-4xl mx-auto">
        <div className="text-center py-20 bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700">
          <LogIn className="w-12 h-12 text-slate-300 dark:text-slate-600 mx-auto mb-3" />
          <p className="text-slate-500 dark:text-slate-400 mb-6">请先登录后查看个人中心</p>
          <Link
            to={ROUTES.LOGIN}
            className="inline-flex items-center justify-center px-5 py-2.5 text-white rounded-xl font-semibold text-sm
              bg-gradient-to-r from-primary-500 to-primary-600
              shadow-lg shadow-primary-500/25
              hover:from-primary-600 hover:to-primary-700
              transition-all"
          >
            去登录
          </Link>
        </div>
      </div>
    );
  }

  const handleSaveUsername = async (e: React.FormEvent) => {
    e.preventDefault();
    setSavingUsername(true);
    try {
      const updated = await authApi.updateUsername(username.trim() || undefined);
      setUser(updated);
      setUsername(updated.username ?? '');
      showToast('用户名已更新');
    } catch (err) {
      showToast(err instanceof Error ? err.message : '更新用户名失败', 'error');
    } finally {
      setSavingUsername(false);
    }
  };

  const handleChangePassword = async (e: React.FormEvent) => {
    e.preventDefault();
    if (newPassword.length < 8) {
      showToast('新密码长度至少 8 位', 'error');
      return;
    }
    if (newPassword !== confirmPassword) {
      showToast('两次输入的新密码不一致', 'error');
      return;
    }
    setSavingPassword(true);
    try {
      await authApi.changePassword(oldPassword, newPassword);
      showToast('密码已修改');
      setOldPassword('');
      setNewPassword('');
      setConfirmPassword('');
    } catch (err) {
      showToast(err instanceof Error ? err.message : '修改密码失败', 'error');
    } finally {
      setSavingPassword(false);
    }
  };

  return (
    <div className="max-w-2xl mx-auto">
      {/* 页面头部 */}
      <div className="mb-8">
        <div className="flex items-center gap-4 mb-2">
          <div className="p-3 rounded-xl bg-gradient-to-r from-primary-500 to-primary-600 shadow-lg shadow-primary-500/25">
            <User className="w-6 h-6 text-white" />
          </div>
          <div>
            <h1 className="text-2xl font-bold text-slate-800 dark:text-white">个人中心</h1>
            <p className="text-slate-500 dark:text-slate-400 mt-0.5 text-sm">查看账号信息、修改用户名和密码</p>
          </div>
        </div>
      </div>

      <div className="space-y-6">
        {/* ① 账号信息 */}
        <div className={CARD_CLASS}>
          <h2 className="text-base font-bold text-slate-800 dark:text-white mb-4 flex items-center gap-2">
            <IdCard className="w-4 h-4 text-primary-500" />
            账号信息
          </h2>
          <div className="space-y-4">
            <div>
              <p className="text-xs text-slate-500 dark:text-slate-400 mb-1">
                登录 UserID（系统随机分配，遗失将无法找回账号）
              </p>
              <p className="text-2xl font-mono font-bold text-primary-600 dark:text-primary-400 tracking-widest select-all">
                {user.userid}
              </p>
            </div>
            <div>
              <p className="text-xs text-slate-500 dark:text-slate-400 mb-1">用户名</p>
              <p className="text-sm font-medium text-slate-700 dark:text-slate-200">
                {user.username ?? '无名用户'}
              </p>
            </div>
          </div>
        </div>

        {/* ② 修改用户名 */}
        <div className={CARD_CLASS}>
          <h2 className="text-base font-bold text-slate-800 dark:text-white mb-4 flex items-center gap-2">
            <User className="w-4 h-4 text-primary-500" />
            修改用户名
          </h2>
          <form onSubmit={handleSaveUsername} className="space-y-3">
            <div>
              <label htmlFor="profile-username" className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                用户名 <span className="text-slate-400 font-normal">(留空保存 = 清空显示名)</span>
              </label>
              <input
                id="profile-username"
                type="text"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                placeholder="留空则不设置显示名"
                className={INPUT_CLASS}
              />
            </div>
            <div className="flex justify-end">
              <button
                type="submit"
                disabled={savingUsername}
                className="px-5 py-2.5 text-white rounded-xl font-semibold text-sm
                  bg-gradient-to-r from-primary-500 to-primary-600
                  shadow-lg shadow-primary-500/25
                  hover:from-primary-600 hover:to-primary-700
                  transition-all disabled:opacity-50 disabled:cursor-not-allowed
                  flex items-center gap-2"
              >
                {savingUsername && <Loader2 className="w-4 h-4 animate-spin" />}
                保存
              </button>
            </div>
          </form>
        </div>

        {/* ③ 修改密码 */}
        <div className={CARD_CLASS}>
          <h2 className="text-base font-bold text-slate-800 dark:text-white mb-4 flex items-center gap-2">
            <KeyRound className="w-4 h-4 text-primary-500" />
            修改密码
          </h2>
          <form onSubmit={handleChangePassword} className="space-y-3">
            <div>
              <label htmlFor="profile-old-password" className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                旧密码
              </label>
              <input
                id="profile-old-password"
                type="password"
                value={oldPassword}
                onChange={(e) => setOldPassword(e.target.value)}
                placeholder="请输入旧密码"
                autoComplete="current-password"
                className={INPUT_CLASS}
              />
            </div>
            <div>
              <label htmlFor="profile-new-password" className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                新密码 <span className="text-slate-400 font-normal">(至少 8 位)</span>
              </label>
              <input
                id="profile-new-password"
                type="password"
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
                placeholder="请输入新密码"
                autoComplete="new-password"
                className={INPUT_CLASS}
              />
            </div>
            <div>
              <label htmlFor="profile-confirm-password" className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                确认新密码
              </label>
              <input
                id="profile-confirm-password"
                type="password"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                placeholder="请再次输入新密码"
                autoComplete="new-password"
                className={INPUT_CLASS}
              />
            </div>
            <div className="flex justify-end">
              <button
                type="submit"
                disabled={savingPassword}
                className="px-5 py-2.5 text-white rounded-xl font-semibold text-sm
                  bg-gradient-to-r from-primary-500 to-primary-600
                  shadow-lg shadow-primary-500/25
                  hover:from-primary-600 hover:to-primary-700
                  transition-all disabled:opacity-50 disabled:cursor-not-allowed
                  flex items-center gap-2"
              >
                {savingPassword && <Loader2 className="w-4 h-4 animate-spin" />}
                修改密码
              </button>
            </div>
          </form>
        </div>
      </div>

      {/* Toast 通知提示 */}
      <AnimatePresence>
        {toast && (
          <motion.div
            initial={{ opacity: 0, y: 50, x: '-50%' }}
            animate={{ opacity: 1, y: 0, x: '-50%' }}
            exit={{ opacity: 0, y: 50, x: '-50%' }}
            className={`fixed bottom-6 left-1/2 px-5 py-3 rounded-xl shadow-lg text-sm font-medium
              flex items-center gap-2 z-[60] ${
                toast.type === 'success'
                  ? 'bg-emerald-600 text-white'
                  : 'bg-red-600 text-white'
              }`}
          >
            {toast.type === 'success'
              ? <CheckCircle className="w-4 h-4" />
              : <XCircle className="w-4 h-4" />
            }
            {toast.message}
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
