# 知面

## 项目介绍

「知面」是一个面向求职者的 AI 模拟面试平台：上传简历后即可开启文字面试与语音面试，由 AI 面试官动态出题、追问并逐题评估总结；内置 pgvector 知识库与 RAG 问答、面试日程管理；用户可自带任意 OpenAI 兼容模型的 API Key（加密存储、按用户动态路由），并内置 10 个岗位方向的面试官人设（Java / 阿里 / 字节 / 前端 / Python / 算法 / 系统设计 / 测开 / AI Agent 开发等）。

重点解决模拟面试场景的三个核心问题：**对话真实感**（语音面试单条 WebSocket 串起实时 ASR → LLM → 实时 TTS，服务端 VAD + 语句防抖合并 + 分句流式合成，边说边答低延迟）；**多用户数据安全**（opaque token 鉴权、WebSocket 握手鉴权、所有业务数据按 userId 隔离、API Key AES 加密存储）；**AI 输出稳定性**（结构化输出严格 JSON 指令 + 错误反馈重试 + schema 校验 + Micrometer 指标，Spring AI 自动重试关闭、重试收拢在业务层）。

技术栈：JDK 21 · Spring Boot 4.1 · Spring AI 2.0 · React 18 + Vite + Tailwind 4 · PostgreSQL 16 + pgvector · Redis 7 + Redisson · RustFS（S3 兼容）· DashScope（Qwen）。

## 主要功能

**简历**

- 上传 PDF / Word / 文本简历，Tika 解析，AI 结构化分析（Redis Stream 异步执行）
- 简历详情与历史记录，支持导出 PDF（iText 8，内嵌中文字体）

**文字面试**

- AI 按岗位方向动态出题（Redis 缓存，24h TTL），支持追问（轮数可配）
- 逐题批量评估 + 全场二次总结（UnifiedEvaluationService），答案与评分落库、可回看历史并导出 PDF 报告

**语音面试**

- WebSocket 全双工：用户音频 → Qwen 实时 ASR（服务端 VAD）→ LLM 面试官 → Qwen 实时 TTS（分句边合成边推流，opus 分片）
- 用户语句防抖合并：定稿静音判定、短内容等待续说、超时强制提交；说话 / 暂停时长统计
- 四阶段流程（自我介绍 / 技术 / 项目 / HR），各阶段时长与题量可配；会话级 / IP 级 / 全局并发三级限流（Redis Lua）
- 前端本地 VAD（ONNX Runtime Web），面试结束生成评估报告页

**知识库与 RAG**

- 文档上传 → 分块向量化入 pgvector（HNSW + 余弦，1024 维；单任务失败自动回滚已写入向量）
- RAG 会话：查询改写 + 按查询长度分档 topK / 相似度阈值；知识库按用户隔离，支持上传、管理与对话检索

**日程与账号**

- 面试日程管理（日历视图）；注册 / 登录 / 个人中心（Redis opaque token，7 天有效，前端 12001 去重登出）
- 数据全量按 userId 隔离：REST 与 WebSocket 双通道鉴权解析身份

**模型管理（自带 Key）**

- 设置页配置自己的 LLM Provider（DashScope / DeepSeek 等任意 OpenAI 兼容协议），API Key AES 加密入库、回显脱敏
- 默认对话 / 向量模型自选，页面修改即时生效（客户端缓存按 userId:providerId 精细失效）

**工程设计亮点**

- **不用 Spring AI starter 自动装配**：主启动类排除全部 OpenAI 自动配置；ChatClient 由 LlmProviderRegistry 按用户动态构造并缓存，配置变更即时失效；向量模型经 delegate EmbeddingModel 路由到当前用户默认模型
- **异步不靠 @EnableAsync**：后台任务统一走 Redis Streams（Redisson 消费组）——简历分析 / 知识库向量化 / 文字与语音面试评估；消息携带 userId，消费端手动设置与清理 UserContext；生产者经 TransactionalExecutor 在事务提交后投递
- **结构化输出收拢在 StructuredOutputInvoker**：严格 JSON 指令 + 防注入说明 + 解析失败带错误反馈重试（修复型提示词可关）+ entity schema 校验重试 + 尝试次数 / 延迟 Micrometer 指标
- **文字面试无题库**：题目由 AI 生成并缓存 Redis（`interview:session:<sid>`，24h TTL），答案与评分持久化 `interview_answers`；旧缓存 null 条目按未命中处理、回源重建（fail-closed）
- **语音链路防卡死**：单句 TTS 超时（8s）与会话内并发上限（3）兜底，某一句失败不阻塞整轮；STT 定稿防抖确保用户连续说话时 partial 可取消
- **统一响应与错误码**：`Result<T>` + `ErrorCode` 按模块分段（2xxx 简历 … 12xxx Provider）；未登录返回 HTTP 200 + 12001，前端去重后统一登出；通用限流注解 + Redis Lua

## 技术栈

| 层             | 选型 |
|----------------|---|
| 语言 / 框架    | JDK 21（虚拟线程）· Spring Boot 4.1 · Maven（单应用模块 app） |
| AI             | Spring AI 2.0（排除 starter 自动装配，动态构造客户端）· DashScope（Qwen 对话 / 实时 ASR / 实时 TTS）· 任意 OpenAI 兼容 Provider（用户自配，API Key AES 加密） |
| 数据           | PostgreSQL 16 + pgvector（业务 + 向量，HNSW / 余弦 / 1024 维）· Spring Data JPA |
| 缓存 / 消息    | Redis 7 · Redisson（token / 业务缓存 / 分布式锁 / 限流 / Redis Streams 消费组） |
| 文件           | RustFS（S3 兼容对象存储）· Apache Tika（简历解析）· iText 8（PDF 导出） |
| 前端           | React 18 · TypeScript · Vite 5 · Tailwind CSS 4 · React Router 7 · axios · recharts · react-big-calendar · ONNX Runtime Web（本地 VAD）· pnpm |
| 文档 / 其他    | springdoc Swagger · Lombok · MapStruct · Micrometer（Prometheus 端点）· spring-ai-agent-utils（技能人设加载） |

## 系统架构

```
浏览器（React SPA，vite dev :5173 → 代理 /api 与 /ws；生产由 nginx 托管静态资源并反代）
        │  axios 自动附带 Bearer token；未登录（code 12001）统一登出
        ▼
Spring Boot 后端 :8080（app）
        ├── REST /api/**：AuthInterceptor 校验 Redis opaque token → UserContext（ThreadLocal）
        │      user · resume · interview · knowledgebase · llmprovider · interviewschedule
        ├── WebSocket /ws/voice-interview/{sessionId}：握手 ?token= 鉴权
        │      └── QwenAsrService（实时 ASR + 服务端 VAD）→ LLM → QwenTtsService（分句 TTS）
        ├── 异步：Redis Streams 消费组（事务提交后投递，消息带 userId）
        │      resume:analyze:stream · knowledgebase:vectorize:stream
        │      interview:evaluate:stream · voice:evaluate:stream
        └── AI 层：LlmProviderRegistry 按 userId:providerId 动态构造 ChatClient（API Key AES 解密）
               └── delegate EmbeddingModel 路由到当前用户默认向量模型
                     ▼
中间件（compose 本地或远程）：PostgreSQL 16 + pgvector（业务 + 向量）· Redis 7 · RustFS :9000（文件）
```

架构要点：

1. **自带模型（BYOK）**：模型配置存数据库不入 yml——`llm_provider_config` 每用户多 Provider，API Key AES 加密；ChatClient 按 userId:providerId 缓存，页面修改即 reload；语音 ASR/TTS 参数存 `voice_ai_setting`，页面可改、重启生效。
2. **全量用户隔离**：所有业务实体带 userId；REST 走拦截器、WS 走握手拦截器，异步消费端手动恢复 UserContext，不做 ThreadLocal 传播。
3. **一致性**：出队投递以事务提交为准（TransactionalExecutor），避免消费者读到未提交数据；向量化任务失败按 job 回滚部分向量，不产生脏检索。
4. **RAG 检索策略**：查询改写开启；短 / 中 / 长查询分档 topK（20 / 12 / 8）与相似度阈值（0.18 / 0.28），HNSW 余弦索引。
5. **语音时序控制**：定稿防抖 + 最小静音时长 + 短内容等待续说 + 最长等待强制提交，防止抢话 / 漏句；分句合成让首段语音不等整段回复生成。

## 模块结构

```
interview
├── app                                后端（Spring Boot 唯一应用模块，包根 interview.guide）
│   └── src/main/java/interview/guide
│       ├── common                     横切：ai（Provider 注册 / StructuredOutputInvoker / Embedding 路由）、
│       │                              evaluation（文字+语音统一评估）、async（Redis Stream 基类）、
│       │                              result / exception（Result<T> + ErrorCode）、config、
│       │                              annotation（@RateLimit + 切面）、transaction（提交后投递）
│       ├── infrastructure             redis（RedisService、InterviewSessionCache）、file（Tika 上传解析）、
│       │                              export（iText 8 PDF，内嵌中文字体）
│       └── modules                    领域模块（model / repository / service + controller / listener）：
│                                      user · resume · interview · voiceinterview ·
│                                      knowledgebase · llmprovider · interviewschedule
│   └── src/main/resources             application.yml、skills/<岗位方向>（SKILL.md + skill.meta.yml）、
│                                      prompts/*.st（StringTemplate 提示词）、voice-interview-opening.yml、
│                                      scripts/（限流 Lua）、fonts/（CJK）
├── frontend                           React 18 + TS：pages（路由页）/ components / api / auth
│                                      （AuthContext + Bearer 注入 + 12001 去重登出）
├── sql                                01-schema.sql → 02-vector-store.sql → 03-seed.sql（部署 schema 手工管理）
├── docker-compose.yml                 postgres(pgvector) :5432 · redis :6379 · rustfs :9000/:9001
└── interview.env.example              环境变量模板（真实 interview.env 不入库）
```

依赖与约定：JPA `ddl-auto: update` 仅用于本地开发；部署 schema 以 `sql/*.sql` 为准，实体变更需同步 `01-schema.sql`。Maven 依赖走阿里云镜像 + Spring 里程碑仓库（Spring Boot 4 / Spring AI 2.0 构件所需）。代码注释、提交信息、提示词与规格文档均使用中文。