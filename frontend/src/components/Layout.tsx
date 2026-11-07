import {Link, Outlet, useLocation, useNavigate} from 'react-router-dom';
import {motion, AnimatePresence} from 'framer-motion';
import {Calendar, CheckCircle, ChevronLeft, ChevronRight, Cpu, Database, FileStack, LogOut, MessageSquare, Moon, Settings, Sparkles, User, Users, XCircle,} from 'lucide-react';
import {useTheme} from '../hooks/useTheme';
import {useCallback, useEffect, useState} from 'react';
import {useAuth} from '../auth/AuthContext';
import {ROUTES} from '../constants/routes';
import UnifiedInterviewModal, {UnifiedInterviewConfig} from './UnifiedInterviewModal';

interface NavItem {
  id: string;
  path: string;
  label: string;
  icon: React.ComponentType<{ className?: string }>;
  description?: string;
}

interface NavGroup {
  id: string;
  title: string;
  items: NavItem[];
}

export default function Layout() {
  const location = useLocation();
  const currentPath = location.pathname;
  const {theme, toggleTheme} = useTheme();
  const navigate = useNavigate();
  const {user, logout} = useAuth();
  const [userMenuOpen, setUserMenuOpen] = useState(false);
  const [settingsMenuOpen, setSettingsMenuOpen] = useState(false);
  // 侧边栏收起状态（记忆到本地，收起后仅保留细条，形态与问答助手知识库栏一致）
  const [sidebarOpen, setSidebarOpen] = useState(() => localStorage.getItem('sidebar_collapsed') === '1' ? false : true);

  useEffect(() => {
    localStorage.setItem('sidebar_collapsed', sidebarOpen ? '0' : '1');
  }, [sidebarOpen]);
  const [interviewModalPreset, setInterviewModalPreset] = useState<{
    defaultMode: 'text' | 'voice';
    defaultResumeId?: number;
    title: string;
    subtitle: string;
    startButtonText: string;
  } | null>(null);

  const openInterviewModalWithResume = (resumeId: number) => {
    setInterviewModalPreset({
      defaultMode: 'text',
      defaultResumeId: resumeId,
      title: '开始模拟面试',
      subtitle: '配置面试参数，开始练习',
      startButtonText: '开始面试',
    });
  };

  // Toast 通知提示
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null);
  const showToast = useCallback((message: string, type: 'success' | 'error' = 'success') => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 3000);
  }, []);

  // 全局未登录事件: token 失效/未登录时提示
  useEffect(() => {
    const onUnauthorized = () => showToast('请先登录', 'error');
    window.addEventListener('auth:unauthorized', onUnauthorized);
    return () => window.removeEventListener('auth:unauthorized', onUnauthorized);
  }, [showToast]);

  const handleInterviewStart = (config: UnifiedInterviewConfig) => {
    setInterviewModalPreset(null);
    if (config.mode === 'text') {
      navigate('/interview', {
        state: {
          resumeId: config.resumeId,
          interviewConfig: {
            skillId: config.skillId,
            difficulty: config.difficulty,
            questionCount: config.questionCount,
            llmProvider: config.llmProvider,
          },
        },
      });
      return;
    }

    const params = new URLSearchParams({
      skillId: config.skillId,
      difficulty: config.difficulty,
    });
    navigate(`/voice-interview?${params.toString()}`, {
      state: {
        voiceConfig: {
          skillId: config.skillId,
          difficulty: config.difficulty,
          techEnabled: true,
          projectEnabled: true,
          hrEnabled: true,
          plannedDuration: config.plannedDuration,
          resumeId: config.resumeId,
          llmProvider: config.llmProvider,
        },
      },
    });
  };

  // 按业务模块组织的导航项
  const navGroups: NavGroup[] = [
    {
      id: 'interview',
      title: '面试准备',
      items: [
        { id: 'resumes', path: '/history', label: '简历管理', icon: FileStack, description: '管理简历，AI 分析' },
        { id: 'interview-hub', path: '/interview-hub', label: '模拟面试', icon: Sparkles, description: '文字/语音面试练习' },
        { id: 'interviews', path: '/interviews', label: '面试记录', icon: Users, description: '查看面试历史' },
        { id: 'interview-schedule', path: '/interview-schedule', label: '面试日程', icon: Calendar, description: '管理面试安排' },
      ],
    },
    {
      id: 'knowledge',
      title: '知识库',
      items: [
        { id: 'kb-manage', path: '/knowledgebase', label: '知识库管理', icon: Database, description: '管理知识文档' },
        { id: 'chat', path: '/knowledgebase/chat', label: '问答助手', icon: MessageSquare, description: '基于知识库问答' },
      ],
    }
  ];

  // 判断当前页面是否匹配导航项
  const isActive = (path: string) => {
    if (path.startsWith('#')) return false;
    if (path === '/history') {
      return currentPath === '/history'
        || currentPath === '/'
        || currentPath.startsWith('/history/')
        || currentPath === '/upload';
    }
    if (path === '/interview-hub') {
      return currentPath === '/interview-hub'
        || currentPath === '/interview'
        || currentPath.startsWith('/interview/')
        || currentPath.startsWith('/voice-interview');
    }
    if (path === '/knowledgebase') {
      return currentPath === '/knowledgebase' || currentPath === '/knowledgebase/upload';
    }
    return currentPath.startsWith(path);
  };

  return (
    <div className="flex min-h-screen bg-gradient-to-br from-slate-50 to-indigo-50 dark:from-slate-900 dark:to-slate-800">
      {/* 左侧边栏（可收起，收起后仅剩细条、中间显示">"展开按钮） */}
      <motion.aside
        initial={false}
        animate={{ width: sidebarOpen ? 256 : 40 }}
        transition={{ duration: 0.3, ease: 'easeInOut' }}
        className="bg-white dark:bg-slate-900 border-r border-slate-100 dark:border-slate-700 fixed h-screen left-0 top-0 z-50 overflow-hidden"
      >
        {/* 展开态：完整侧边栏内容（固定宽度，收起动画时仅被裁剪不重排） */}
        <motion.div
          initial={false}
          animate={{ opacity: sidebarOpen ? 1 : 0 }}
          transition={{ duration: 0.2, delay: sidebarOpen ? 0.15 : 0 }}
          style={{ pointerEvents: sidebarOpen ? 'auto' : 'none' }}
          className="w-64 h-full flex flex-col"
        >
        {/* 品牌图标（点击收起侧边栏，悬浮时图标顺滑转为"<"）；标题文字纯展示不可点击 */}
        <div className="p-6 border-b border-slate-100 dark:border-slate-700 flex items-center gap-3">
          <button
            onClick={() => setSidebarOpen(false)}
            title="收起侧边栏"
            aria-label="收起侧边栏"
            className="group w-10 h-10 flex-shrink-0 bg-gradient-to-br from-primary-500 to-primary-600 rounded-xl flex items-center justify-center text-white shadow-lg shadow-primary-500/30 transition-transform duration-200 hover:scale-105 active:scale-95"
          >
            <span className="relative block w-5 h-5">
              <Sparkles
                className="absolute inset-0 w-5 h-5 transition-all duration-300 ease-out group-hover:opacity-0 group-hover:-rotate-90 group-hover:scale-75"
              />
              <ChevronLeft
                className="absolute inset-0 w-5 h-5 transition-all duration-300 ease-out opacity-0 rotate-90 scale-75 group-hover:opacity-100 group-hover:rotate-0 group-hover:scale-100"
              />
            </span>
          </button>
          <div className="min-w-0">
            <span className="text-lg font-bold text-slate-800 dark:text-white tracking-tight block">知面</span>
            <span className="text-xs text-slate-400 dark:text-slate-500">智能面试助手</span>
          </div>
        </div>


        {/* 导航菜单 */}
        <nav className="flex-1 p-4 overflow-y-auto">
          <div className="space-y-6">
            {navGroups.map((group) => (
              <div key={group.id}>
                <div className="px-3 mb-2">
                  <span className="text-xs font-semibold text-slate-400 dark:text-slate-500 uppercase tracking-wider">
                    {group.title}
                  </span>
                </div>
                <div className="space-y-1">
                  {group.items.map((item) => {
                    const active = isActive(item.path);

                    return (
                      <Link
                        key={item.id}
                        to={item.path}
                        className={`group relative flex items-center gap-3 px-3 py-2.5 rounded-xl transition-all duration-200
                          ${active
                            ? 'bg-primary-50 dark:bg-primary-900/30 text-primary-600 dark:text-primary-400'
                            : 'text-slate-600 dark:text-slate-400 hover:bg-slate-50 dark:hover:bg-slate-800 hover:text-slate-900 dark:hover:text-white'
                          }`}
                      >
                        <div className={`w-9 h-9 rounded-lg flex items-center justify-center transition-colors
                          ${active
                            ? 'bg-primary-100 dark:bg-primary-900/50 text-primary-600 dark:text-primary-400'
                            : 'bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-400 group-hover:bg-slate-200 dark:group-hover:bg-slate-700 group-hover:text-slate-700 dark:group-hover:text-white'
                          }`}
                        >
                          <item.icon className="w-5 h-5" />
                        </div>
                        <div className="flex-1 min-w-0">
                          <span className={`text-sm block ${active ? 'font-semibold' : 'font-medium'}`}>
                            {item.label}
                          </span>
                          {item.description && (
                            <span className="text-xs text-slate-400 dark:text-slate-500 truncate block">
                              {item.description}
                            </span>
                          )}
                        </div>
                        {active && <ChevronRight className="w-4 h-4 text-primary-400" />}
                      </Link>
                    );
                  })}
                </div>
              </div>
            ))}
          </div>
        </nav>

        {/* 底部: 用户入口 + 设置 */}
        <div className="relative p-4 border-t border-slate-100 dark:border-slate-700">
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => (user ? setUserMenuOpen((v) => {
                const next = !v;
                if (next) setSettingsMenuOpen(false);
                return next;
              }) : navigate(ROUTES.LOGIN))}
              className="flex-1 min-w-0 px-3 py-2 bg-gradient-to-r from-primary-50 to-indigo-50
                dark:from-primary-900/30 dark:to-slate-800 rounded-xl text-left hover:opacity-80 transition-opacity"
            >
              <p className="text-xs text-primary-600 dark:text-primary-400 font-medium truncate">
                {user ? (user.username || '无名用户') : '未登录'}
              </p>
              <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5 truncate">
                {user ? user.userid : '点击登录'}
              </p>
            </button>
            <button
              type="button"
              onClick={() => setSettingsMenuOpen((v) => {
                const next = !v;
                if (next) setUserMenuOpen(false);
                return next;
              })}
              title="设置"
              aria-label="设置"
              className="p-2.5 rounded-xl text-slate-500 dark:text-slate-400
                hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
            >
              <Settings className="w-5 h-5" />
            </button>
          </div>

          {/* 用户下拉: 弹层定位在按钮上方, 点击遮罩/外部区域关闭 */}
          <AnimatePresence>
            {userMenuOpen && (
              <motion.div
                key="user-menu-overlay"
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                exit={{ opacity: 0 }}
                transition={{ duration: 0.15 }}
                className="fixed inset-0 z-40"
                onClick={() => setUserMenuOpen(false)}
              />
            )}
            {userMenuOpen && (
              <motion.div
                key="user-menu"
                initial={{ opacity: 0, y: 8, scale: 0.98 }}
                animate={{ opacity: 1, y: 0, scale: 1 }}
                exit={{ opacity: 0, y: 8, scale: 0.98 }}
                transition={{ duration: 0.15 }}
                className="absolute bottom-full left-4 right-4 mb-2 z-50 rounded-xl bg-white dark:bg-slate-800
                  shadow-lg border border-slate-100 dark:border-slate-700 overflow-hidden"
              >
                <button
                  type="button"
                  onClick={() => {
                    setUserMenuOpen(false);
                    navigate(ROUTES.PROFILE);
                  }}
                  className="w-full flex items-center gap-2 px-4 py-3 text-sm text-slate-600 dark:text-slate-300
                    hover:bg-slate-50 dark:hover:bg-slate-700 transition-colors"
                >
                  <User className="w-4 h-4" />
                  个人中心
                </button>
                <button
                  type="button"
                  onClick={async () => {
                    setUserMenuOpen(false);
                    await logout();
                    navigate(ROUTES.LOGIN);
                  }}
                  className="w-full flex items-center gap-2 px-4 py-3 text-sm text-red-500 dark:text-red-400
                    hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors border-t border-slate-100 dark:border-slate-700"
                >
                  <LogOut className="w-4 h-4" />
                  退出登录
                </button>
              </motion.div>
            )}
          </AnimatePresence>

          {/* 设置菜单: 弹层定位在设置图标上方（模型配置 / 深色模式开关，状态只显示当前一种） */}
          <AnimatePresence>
            {settingsMenuOpen && (
              <motion.div
                key="settings-menu-overlay"
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                exit={{ opacity: 0 }}
                transition={{ duration: 0.15 }}
                className="fixed inset-0 z-40"
                onClick={() => setSettingsMenuOpen(false)}
              />
            )}
            {settingsMenuOpen && (
              <motion.div
                key="settings-menu"
                initial={{ opacity: 0, y: 8, scale: 0.98 }}
                animate={{ opacity: 1, y: 0, scale: 1 }}
                exit={{ opacity: 0, y: 8, scale: 0.98 }}
                transition={{ duration: 0.15 }}
                className="absolute bottom-full right-4 mb-2 z-50 w-48 rounded-xl bg-white dark:bg-slate-800
                  shadow-lg border border-slate-100 dark:border-slate-700 overflow-hidden"
              >
                <button
                  type="button"
                  onClick={() => {
                    setSettingsMenuOpen(false);
                    navigate('/settings');
                  }}
                  className="w-full flex items-center gap-2 px-4 py-3 text-sm text-slate-600 dark:text-slate-300
                    hover:bg-slate-50 dark:hover:bg-slate-700 transition-colors"
                >
                  <Cpu className="w-4 h-4" />
                  模型配置
                </button>
                <button
                  type="button"
                  onClick={toggleTheme}
                  className="w-full flex items-center gap-2 px-4 py-3 text-sm text-slate-600 dark:text-slate-300
                    hover:bg-slate-50 dark:hover:bg-slate-700 transition-colors border-t border-slate-100 dark:border-slate-700"
                >
                  <Moon className="w-4 h-4" />
                  深色模式
                  <span
                    className={`ml-auto text-xs font-medium px-2 py-0.5 rounded-full ${
                      theme === 'dark'
                        ? 'bg-primary-50 dark:bg-primary-900/30 text-primary-600 dark:text-primary-400'
                        : 'bg-slate-100 dark:bg-slate-700 text-slate-400'
                    }`}
                  >
                    {theme === 'dark' ? '开启' : '关闭'}
                  </span>
                </button>
              </motion.div>
            )}
          </AnimatePresence>
        </div>
        </motion.div>

        {/* 收起态：细条（与问答助手知识库栏收起形态一致），中间显示">"展开按钮 */}
        <motion.div
          initial={false}
          animate={{ opacity: sidebarOpen ? 0 : 1 }}
          transition={{ duration: 0.2, delay: sidebarOpen ? 0 : 0.15 }}
          style={{ pointerEvents: sidebarOpen ? 'none' : 'auto' }}
          className="absolute inset-0 flex items-center justify-center"
        >
          <button
            onClick={() => setSidebarOpen(true)}
            title="展开侧边栏"
            aria-label="展开侧边栏"
            className="w-full h-full flex items-center justify-center text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-800 transition-colors"
          >
            <ChevronRight className="w-5 h-5" />
          </button>
        </motion.div>
      </motion.aside>

      {/* 主内容区（边距跟随侧边栏宽度过渡） */}
      <main
        className={`flex-1 p-10 min-h-screen overflow-y-auto transition-[margin-left] duration-300 ease-in-out ${sidebarOpen ? 'ml-64' : 'ml-10'}`}
      >
        <motion.div
          key={currentPath}
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: -20 }}
          transition={{ duration: 0.3 }}
        >
          <Outlet context={{ openInterviewModalWithResume }} />
        </motion.div>
      </main>

      {/* 统一面试弹窗 */}
      <UnifiedInterviewModal
        isOpen={interviewModalPreset !== null}
        onClose={() => setInterviewModalPreset(null)}
        onStart={handleInterviewStart}
        defaultMode={interviewModalPreset?.defaultMode || 'text'}
        defaultResumeId={interviewModalPreset?.defaultResumeId}
        hideModeSwitch={interviewModalPreset?.defaultResumeId == null}
        title={interviewModalPreset?.title || '开始模拟面试'}
        subtitle={interviewModalPreset?.subtitle || '选择面试模式和主题，快速开始'}
        startButtonText={interviewModalPreset?.startButtonText || '开始面试'}
      />

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
