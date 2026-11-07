package interview.guide.common.ai;

import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonSchemaModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 回归：victools jsonschema-generator 与 jsonschema-module-jackson 必须同大版本。
 * 曾经 dashscope-sdk 传递的 generator 4.31.1 与 Spring AI 2.0 的 module-jackson 5.0.0
 * 混搭，应用启动时在 {@code JacksonSchemaModule.applyToConfigBuilder} 抛 NoSuchMethodError；
 * 版本已在 app/pom.xml dependencyManagement 统一钉 5.0.0，本测试防止依赖漂移再次引入混搭。
 */
class VictoolsAlignmentTest {

    @Test
    @DisplayName("generator 与 module-jackson 版本对齐，可正常装配 SchemaGenerator 配置")
    void jacksonModuleAppliesToConfigBuilder() {
        assertThatCode(() -> {
            SchemaGeneratorConfigBuilder builder = new SchemaGeneratorConfigBuilder(
                new ObjectMapper(), SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON);
            new JacksonSchemaModule().applyToConfigBuilder(builder);
        }).doesNotThrowAnyException();
    }
}
