package interview.guide;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 知面 - 应用测试
 */
class AppTest {
    
    @Test 
    void contextLoads() {
        // 验证应用主类存在
        assertNotNull(App.class);
    }
}
