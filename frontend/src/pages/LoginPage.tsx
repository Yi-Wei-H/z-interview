import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Loader2, LogIn, Eye, EyeOff } from 'lucide-react';
import { useAuth } from '../auth/AuthContext';
import { ROUTES } from '../constants/routes';

const INPUT_CLASS = `w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600
  bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white
  placeholder:text-slate-400 focus:outline-none focus:ring-2
  focus:ring-primary-500/50 focus:border-primary-400 transition-shadow`;

export default function LoginPage() {
  const [userid, setUserid] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const { login } = useAuth();
  const navigate = useNavigate();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!userid.trim() || !password) {
      setError('请输入 UserID 和密码');
      return;
    }
    setError('');
    setLoading(true);
    try {
      await login(userid.trim(), password);
      navigate('/');
    } catch (err) {
      setError(err instanceof Error ? err.message : '登录失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-50 dark:bg-slate-900 p-4">
      <div className="bg-white dark:bg-slate-800 rounded-2xl shadow-2xl max-w-md w-full p-8">
        {/* 标题 */}
        <div className="flex flex-col items-center mb-8">
          <div className="p-3 rounded-xl bg-gradient-to-r from-primary-500 to-primary-600 shadow-lg shadow-primary-500/25 mb-4">
            <LogIn className="w-6 h-6 text-white" />
          </div>
          <h1 className="text-2xl font-bold text-slate-800 dark:text-white">AI 面试助手 · 登录</h1>
          <p className="text-slate-500 dark:text-slate-400 mt-1 text-sm">使用 UserID 和密码登录</p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          {/* UserID */}
          <div>
            <label htmlFor="login-userid" className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
              UserID
            </label>
            <input
              id="login-userid"
              type="text"
              value={userid}
              onChange={(e) => setUserid(e.target.value)}
              placeholder="请输入 UserID"
              autoComplete="username"
              className={INPUT_CLASS}
            />
          </div>

          {/* 密码 */}
          <div>
            <label htmlFor="login-password" className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
              密码
            </label>
            <div className="relative">
              <input
                id="login-password"
                type={showPassword ? 'text' : 'password'}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="请输入密码"
                autoComplete="current-password"
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
                登录中...
              </>
            ) : (
              '登录'
            )}
          </button>
        </form>

        {/* 底部链接 */}
        <p className="mt-6 text-center text-sm text-slate-500 dark:text-slate-400">
          还没有账号？{' '}
          <Link to={ROUTES.REGISTER} className="text-primary-600 dark:text-primary-400 font-medium hover:underline">
            去注册
          </Link>
        </p>
      </div>
    </div>
  );
}
