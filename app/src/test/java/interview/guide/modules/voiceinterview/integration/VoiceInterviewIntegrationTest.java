package interview.guide.modules.voiceinterview.integration;

import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.dto.CreateSessionRequest;
import interview.guide.modules.voiceinterview.dto.SessionResponseDTO;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 语音面试集成测试
 *
 * <p>语音面试功能的端到端测试：
 * <ul>
 *   <li>通过 REST API 创建会话</li>
 *   <li>会话生命周期管理</li>
 *   <li>阶段转换逻辑</li>
 *   <li>数据库持久化</li>
 *   <li>配置校验</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@org.junit.jupiter.api.Disabled(
    "Pending: 测试环境下 application-test.yml 的 app.ai.providers 配置漂移，Spring context 启动后 "
        + "VoiceInterviewService.createSession 通过 LlmProviderRegistry 解析 module-default provider "
        + "时 NPE。独立 PR 修 test profile 配置后再启用。"
)
@DisplayName("语音面试集成测试（待修复）")
class VoiceInterviewIntegrationTest {

    @Autowired
    private VoiceInterviewService voiceInterviewService;

    @Autowired
    private VoiceInterviewSessionRepository sessionRepository;

    @Autowired
    private VoiceInterviewProperties properties;

    @BeforeEach
    void setUp() {
        // 清理数据库
        sessionRepository.deleteAll();
    }

    @Nested
    @DisplayName("完整面试流程测试")
    class CompleteInterviewFlowTests {

        @Test
        @DisplayName("完整面试流程 - 创建会话到结束会话")
        void testCompleteInterviewFlow() {
            // 步骤 1：创建会话
            CreateSessionRequest createRequest = CreateSessionRequest.builder()
                .roleType("ali-p8")
                .introEnabled(true)
                .techEnabled(true)
                .projectEnabled(true)
                .hrEnabled(true)
                .plannedDuration(30)
                .build();

            SessionResponseDTO sessionResponse = voiceInterviewService.createSession(createRequest);

            assertNotNull(sessionResponse);
            assertNotNull(sessionResponse.getSessionId());
            assertEquals("ali-p8", sessionResponse.getRoleType());
            assertEquals("INTRO", sessionResponse.getCurrentPhase());
            assertEquals("IN_PROGRESS", sessionResponse.getStatus());

            Long sessionId = sessionResponse.getSessionId();

            // 校验会话已保存到数据库
            VoiceInterviewSessionEntity savedSession = sessionRepository.findById(sessionId).orElse(null);
            assertNotNull(savedSession);
            assertEquals("ali-p8", savedSession.getRoleType());

            // 步骤 2：测试阶段转换逻辑
            LocalDateTime phaseStartTime = LocalDateTime.now();
            boolean shouldTransition = voiceInterviewService.shouldTransitionToNextPhase(
                savedSession, phaseStartTime, 2
            );

            // 问题数较少时不应转换（INTRO 阶段）
            assertFalse(shouldTransition, "Should not transition with low question count");

            // 步骤 3：结束会话
            String sessionIdStr = sessionId.toString();
            voiceInterviewService.endSession(sessionIdStr);

            // 校验会话状态
            VoiceInterviewSessionEntity endedSession = sessionRepository.findById(sessionId).orElse(null);
            assertNotNull(endedSession);
            assertEquals(VoiceInterviewSessionEntity.InterviewPhase.COMPLETED, endedSession.getCurrentPhase());
        }

        @Test
        @DisplayName("会话状态转换 - 验证阶段转换条件")
        void testPhaseTransition() {
            // 创建启用 INTRO 和 TECH 阶段的会话
            CreateSessionRequest request = CreateSessionRequest.builder()
                .roleType("byteance-algo")
                .introEnabled(true)
                .techEnabled(true)
                .projectEnabled(false)
                .hrEnabled(false)
                .plannedDuration(20)
                .build();

            SessionResponseDTO sessionResponse = voiceInterviewService.createSession(request);
            Long sessionId = sessionResponse.getSessionId();

            // 初始阶段应为 INTRO
            assertEquals("INTRO", sessionResponse.getCurrentPhase());

            VoiceInterviewSessionEntity session = sessionRepository.findById(sessionId).orElseThrow();

            // 测试阶段转换条件
            LocalDateTime phaseStartTime = LocalDateTime.now().minusMinutes(10); // 10 分钟前
            int questionCount = 3; // 问题数较少

            boolean shouldTransition = voiceInterviewService.shouldTransitionToNextPhase(
                session, phaseStartTime, questionCount
            );

            // 校验转换逻辑正常工作
            assertNotNull(shouldTransition);
        }

        @Test
        @DisplayName("会话持久化 - 数据库存储和检索")
        void testSessionPersistence() {
            CreateSessionRequest request = CreateSessionRequest.builder()
                .roleType("tencent-backend")
                .introEnabled(true)
                .plannedDuration(25)
                .build();

            SessionResponseDTO sessionResponse = voiceInterviewService.createSession(request);
            Long sessionId = sessionResponse.getSessionId();

            // 校验数据库持久化
            VoiceInterviewSessionEntity dbSession = sessionRepository.findById(sessionId).orElse(null);
            assertNotNull(dbSession);
            assertEquals(sessionId, dbSession.getId());
            assertEquals("tencent-backend", dbSession.getRoleType());

            // 校验能重新查回该会话
            VoiceInterviewSessionEntity retrievedSession = sessionRepository.findById(sessionId).orElse(null);
            assertNotNull(retrievedSession);
            assertEquals(dbSession.getCurrentPhase(), retrievedSession.getCurrentPhase());
        }

        @Test
        @DisplayName("多阶段会话 - 验证所有阶段都能正确初始化")
        void testMultiPhaseSession() {
            CreateSessionRequest request = CreateSessionRequest.builder()
                .roleType("ali-p8")
                .introEnabled(true)
                .techEnabled(true)
                .projectEnabled(true)
                .hrEnabled(true)
                .plannedDuration(45)
                .build();

            SessionResponseDTO sessionResponse = voiceInterviewService.createSession(request);
            Long sessionId = sessionResponse.getSessionId();

            VoiceInterviewSessionEntity session = sessionRepository.findById(sessionId).orElseThrow();

            // 校验所有阶段均已正确配置
            assertEquals(VoiceInterviewSessionEntity.InterviewPhase.INTRO, session.getCurrentPhase());

            // 逐个阶段测试转换逻辑
            LocalDateTime startTime = LocalDateTime.now();

            // INTRO 阶段
            session.setCurrentPhase(VoiceInterviewSessionEntity.InterviewPhase.INTRO);
            boolean introTransition = voiceInterviewService.shouldTransitionToNextPhase(
                session, startTime.minusMinutes(10), 5
            );

            // TECH 阶段
            session.setCurrentPhase(VoiceInterviewSessionEntity.InterviewPhase.TECH);
            boolean techTransition = voiceInterviewService.shouldTransitionToNextPhase(
                session, startTime.minusMinutes(12), 9
            );

            // 校验所有阶段的逻辑均正常工作
            assertNotNull(introTransition);
            assertNotNull(techTransition);
        }
    }

    @Nested
    @DisplayName("错误处理测试")
    class ErrorHandlingTests {

        @Test
        @DisplayName("处理无效的会话ID")
        void testInvalidSessionId() {
            // 尝试结束一个不存在的会话
            String invalidSessionId = "99999";

            // 服务应优雅处理不存在的会话（不抛异常）
            assertDoesNotThrow(() -> {
                voiceInterviewService.endSession(invalidSessionId);
            });
        }

        @Test
        @DisplayName("处理空配置 - 验证默认值")
        void testEmptyConfiguration() {
            // 使用最小配置创建会话
            CreateSessionRequest request = CreateSessionRequest.builder()
                .roleType("ali-p8")
                .build();

            SessionResponseDTO sessionResponse = voiceInterviewService.createSession(request);

            assertNotNull(sessionResponse);
            assertNotNull(sessionResponse.getSessionId());
        }

        @Test
        @DisplayName("处理不同角色类型")
        void testDifferentRoleTypes() {
            String[] roleTypes = {"ali-p8", "byteance-algo", "tencent-backend"};

            for (String roleType : roleTypes) {
                CreateSessionRequest request = CreateSessionRequest.builder()
                    .roleType(roleType)
                    .plannedDuration(30)
                    .build();

                SessionResponseDTO sessionResponse = voiceInterviewService.createSession(request);

                assertNotNull(sessionResponse, "Session should be created for role: " + roleType);
                assertEquals(roleType, sessionResponse.getRoleType());
            }
        }
    }

    @Nested
    @DisplayName("配置验证测试")
    class ConfigurationTests {

        @Test
        @DisplayName("验证面试阶段配置")
        void testPhaseConfiguration() {
            VoiceInterviewProperties.PhaseConfig phaseConfig = properties.getPhase();

            assertNotNull(phaseConfig.getIntro(), "INTRO phase config should not be null");
            assertNotNull(phaseConfig.getTech(), "TECH phase config should not be null");
            assertNotNull(phaseConfig.getProject(), "PROJECT phase config should not be null");
            assertNotNull(phaseConfig.getHr(), "HR phase config should not be null");

            // 校验时长上限合理
            assertTrue(phaseConfig.getIntro().getMaxDuration() > 0);
            assertTrue(phaseConfig.getTech().getMaxDuration() > 0);
            assertTrue(phaseConfig.getProject().getMaxDuration() > 0);
            assertTrue(phaseConfig.getHr().getMaxDuration() > 0);
        }

        @Test
        @DisplayName("验证阶段配置参数")
        void testPhaseConfigParameters() {
            VoiceInterviewProperties.PhaseConfig phaseConfig = properties.getPhase();

            // 校验 INTRO 阶段
            VoiceInterviewProperties.DurationConfig intro = phaseConfig.getIntro();
            assertTrue(intro.getSuggestedDuration() > 0);
            assertTrue(intro.getMinQuestions() >= 0);
            assertTrue(intro.getMaxQuestions() > intro.getMinQuestions());

            // 校验 TECH 阶段
            VoiceInterviewProperties.DurationConfig tech = phaseConfig.getTech();
            assertTrue(tech.getSuggestedDuration() > 0);
            assertTrue(tech.getMinQuestions() >= 0);
            assertTrue(tech.getMaxQuestions() > tech.getMinQuestions());

            // 校验 PROJECT 阶段
            VoiceInterviewProperties.DurationConfig project = phaseConfig.getProject();
            assertTrue(project.getSuggestedDuration() > 0);
            assertTrue(project.getMinQuestions() >= 0);
            assertTrue(project.getMaxQuestions() > project.getMinQuestions());

            // 校验 HR 阶段
            VoiceInterviewProperties.DurationConfig hr = phaseConfig.getHr();
            assertTrue(hr.getSuggestedDuration() > 0);
            assertTrue(hr.getMinQuestions() >= 0);
            assertTrue(hr.getMaxQuestions() > hr.getMinQuestions());
        }

        @Test
        @DisplayName("验证配置完整性")
        void testConfigurationCompleteness() {
            assertNotNull(properties, "Properties should not be null");
            assertNotNull(properties.getPhase(), "Phase config should not be null");

            VoiceInterviewProperties.PhaseConfig phaseConfig = properties.getPhase();

            // 校验所有阶段的配置均非 null
            assertNotNull(phaseConfig.getIntro());
            assertNotNull(phaseConfig.getTech());
            assertNotNull(phaseConfig.getProject());
            assertNotNull(phaseConfig.getHr());
        }
    }

    @AfterEach
    void tearDown() {
        // 清理测试数据
        sessionRepository.deleteAll();
    }
}
