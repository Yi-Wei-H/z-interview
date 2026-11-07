import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Loader2, UserPlus, Eye, EyeOff, KeyRound, AlertTriangle } from 'lucide-react';
import { authApi } from '../api/auth';
import { ROUTES } from '../constants/routes';

const INPUT_CLASS = `w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600
  bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white
  placeholder:text-slate-400 focus:outline-none focus:ring-2
  focus:ring-primary-500/50 focus:border-primary-400 transition-shadow`;

export default function RegisterPage() {
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [username, setUsername] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [registeredUserid, setRegisteredUserid] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (password.length < 8) {
      setError('密码长度至少 8 位');
      return;
    }
    if (password !== confirmPassword) {
      setError('两次输入的密码不一致');
      return;
    }
    setError('');
    setLoading(true);
    try {
      const user = await authApi.register({ password, username: username.trim() || undefined });
      setRegisteredUserid(user.userid); // 切换到成功视图
    } catch (err) {
      setError(err instanceof Error ? err.message : '注册失败');
    } finally {
      setLoading(false);
    }
  };

  // 注册成功视图：醒目展示随机分配的 UserID
  if (registeredUserid) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-slate-50 dark:bg-slate-900 p-4">
        <div className="bg-white dark:bg-slate-800 rounded-2xl shadow-2xl max-w-md w-full p-8 text-center">
          <div className="p-3 rounded-xl bg-gradient-to-r from-primary-500 to-primary-600 shadow-lg shadow-primary-500/25 inline-flex mb-4">
            <KeyRound className="w-6 h-6 text-white" />
          </div>
          <h1 className="text-2xl font-bold text-slate-800 dark:text-white mb-2">注册成功！</h1>
          <p className="text-sm text-slate-500 dark:text-slate-400 mb-6">
            请牢记你的 UserID（登录时使用）：
          </p>
          <p className="text-4xl font-mono font-bold text-primary-600 dark:text-primary-400 tracking-widest mb-6 select-all">
            {registeredUserid}
          </p>
          <div className="flex items-start gap-2 text-sm text-amber-600 dark:text-amber-400 bg-amber-50 dark:bg-amber-900/20 rounded-xl px-4 py-3 mb-8 text-left">
            <AlertTriangle className="w-4 h-4 flex-shrink-0 mt-0.5" />
            <span>UserID 是系统随机分配的，遗失将无法找回账号，请妥善保存。</span>
          </div>
          <Link
            to={ROUTES.LOGIN}
            className="inline-flex items-center justify-center w-full py-2.5 text-white rounded-xl font-semibold text-sm
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

  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-50 dark:bg-slate-900 p-4">
      <div className="bg-white dark:bg-slate-800 rounded-2xl shadow-2xl max-w-md w-full p-8">
        {/* 标题 */}
        <div className="flex flex-col items-center mb-8">
          <div className="p-3 rounded-xl bg-gradient-to-r from-primary-500 to-primary-600 shadow-lg shadow-primary-500/25 mb-4">
            <UserPlus className="w-6 h-6 text-white" />
          </div>
          <h1 className="text-2xl font-bold text-slate-800 dark:text-white">AI 面试助手 · 注册</h1>
          <p className="text-slate-500 dark:text-slate-400 mt-1 text-sm">
            注册后系统会随机分配一个登录 UserID
          </p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          {/* 密码 */}
          <div>
            <label htmlFor="register-password" className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
              密码 <span className="text-slate-400 font-normal">(至少 8 位)</span>
            </label>
            <div className="relative">
              <input
                id="register-password"
                type={showPassword ? 'text' : 'password'}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="请输入密码"
                autoComplete="new-password"
                className={`${INPUT_CLASS} pr-10`}
              />
              <button
                type="button"
                onClick={() => setShowPassword(!showPassword)}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400
                  hover:text-slate-600 dark:hover:text-slate-300 transition-colors"
              >
                {showPassword ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
              </button>
            </div>
          </div>

          {/* 确认密码 */}
          <div>
            <label htmlFor="register-confirm" className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
              确认密码
            </label>
            <input
              id="register-confirm"
              type={showPassword ? 'text' : 'password'}
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
              placeholder="请再次输入密码"
              autoComplete="new-password"
              className={INPUT_CLASS}
            />
          </div>

          {/* 用户名（可选） */}
          <div>
            <label htmlFor="register-username" className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
              用户名 <span className="text-slate-400 font-normal">(可选，用于展示)</span>
            </label>
            <input
              id="register-username"
              type="text"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              placeholder="留空则不设置显示名"
              className={INPUT_CLASS}
            />
          </div>

          {/* 错误提示 */}
          {error && (
            <p className="text-sm text-red-500 bg-red-50 dark:bg-red-900/20 dark:text-red-300 rounded-lg px-3 py-2">
              {error}
            </p>
          )}

          {/* 提交按钮 */}
          <button
            type="submit"
            disabled={loading}
            className="w-full py-2.5 text-white rounded-xl font-semibold text-sm
              bg-gradient-to-r from-primary-500 to-primary-600
              shadow-lg shadow-primary-500/25
              hover:from-primary-600 hover:to-primary-700
              transition-all disabled:opacity-50 disabled:cursor-not-allowed
              flex items-center justify-center gap-2"
          >
            {loading ? (
              <>
                <Loader2 className="w-4 h-4 animate-spin" />
                注册中...
              </>
            ) : (
              '注册'
            )}
          </button>
        </form>

        {/* 底部链接 */}
        <p className="mt-6 text-center text-sm text-slate-500 dark:text-slate-400">
          已有账号？{' '}
          <Link to={ROUTES.LOGIN} className="text-primary-600 dark:text-primary-400 font-medium hover:underline">
            去登录
          </Link>
        </p>
      </div>
    </div>
  );
}
