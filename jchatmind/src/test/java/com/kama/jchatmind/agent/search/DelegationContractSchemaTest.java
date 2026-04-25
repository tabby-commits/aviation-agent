package com.kama.jchatmind.agent.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.search.model.DelegationResult;
import com.kama.jchatmind.agent.search.model.GlobalPolicy;
import com.kama.jchatmind.agent.search.model.SearchDelegationErrorCode;
import com.kama.jchatmind.agent.search.model.SubTaskSpec;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 delegateSearchTask 工具的 JSON Schema 契约。
 * Spring AI 通过 MethodToolCallbackProvider 从方法签名自动生成入参 schema，
 * 本测试确保生成的 schema 包含预期的顶层字段，且 DTO records 可被 Jackson 正确序列化/反序列化。
 */
class DelegationContractSchemaTest {

    /** 用于验证 schema 生成的最小 stub，不含实际业务逻辑 */
    static class DelegationToolStub {
        @Tool(
                name = "delegateSearchTask",
                description = "派发并行子任务进行 Agentic Search，支持 KB 宽召回与 Tavily 外部搜索。"
                        + "子任务并行执行，结果由主 Agent 综合成文。"
        )
        public DelegationResult delegate(List<SubTaskSpec> tasks, GlobalPolicy policy) {
            return null;
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void toolName_isCorrect() {
        ToolCallback[] callbacks = buildCallbacks();
        assertThat(callbacks).hasSize(1);
        assertThat(callbacks[0].getToolDefinition().name()).isEqualTo("delegateSearchTask");
    }

    @Test
    void inputSchema_containsTopLevelFields() throws Exception {
        String schema = buildCallbacks()[0].getToolDefinition().inputSchema();
        assertThat(schema).isNotBlank();

        JsonNode root = mapper.readTree(schema);
        assertThat(root.has("properties"))
                .as("schema must have a 'properties' node")
                .isTrue();

        JsonNode props = root.get("properties");
        assertThat(props.has("tasks")).as("schema must declare 'tasks' parameter").isTrue();
        assertThat(props.has("policy")).as("schema must declare 'policy' parameter").isTrue();
    }

    @Test
    void subTaskSpec_jacksonRoundTrip() throws Exception {
        String json = """
                {
                  "taskId": "t1",
                  "taskDescription": "检索 2025 年 SpaceX 发射记录",
                  "kbId": "kb_space_news",
                  "scope": {
                    "timeRange": "2025",
                    "region": "US",
                    "mustCover": ["每月发射次数", "关键任务"]
                  },
                  "searchPolicy": {
                    "allowKbSearch": true,
                    "allowWebSearch": true,
                    "maxSubSteps": 6,
                    "timeoutSeconds": 45
                  }
                }
                """;

        SubTaskSpec spec = mapper.readValue(json, SubTaskSpec.class);

        assertThat(spec.taskId()).isEqualTo("t1");
        assertThat(spec.taskDescription()).isEqualTo("检索 2025 年 SpaceX 发射记录");
        assertThat(spec.kbId()).isEqualTo("kb_space_news");
        assertThat(spec.scope().timeRange()).isEqualTo("2025");
        assertThat(spec.scope().mustCover()).containsExactly("每月发射次数", "关键任务");
        assertThat(spec.searchPolicy().allowKbSearch()).isTrue();
        assertThat(spec.searchPolicy().allowWebSearch()).isTrue();
        assertThat(spec.searchPolicy().maxSubSteps()).isEqualTo(6);
        assertThat(spec.searchPolicy().timeoutSeconds()).isEqualTo(45);

        // 序列化后再反序列化，确保幂等
        String reJson = mapper.writeValueAsString(spec);
        SubTaskSpec reSpec = mapper.readValue(reJson, SubTaskSpec.class);
        assertThat(reSpec).isEqualTo(spec);
    }

    @Test
    void globalPolicy_jacksonRoundTrip() throws Exception {
        GlobalPolicy policy = mapper.readValue(
                "{\"maxParallel\": 4, \"globalTimeoutSeconds\": 60}",
                GlobalPolicy.class
        );
        assertThat(policy.maxParallel()).isEqualTo(4);
        assertThat(policy.globalTimeoutSeconds()).isEqualTo(60);

        String reJson = mapper.writeValueAsString(policy);
        assertThat(mapper.readValue(reJson, GlobalPolicy.class)).isEqualTo(policy);
    }

    @Test
    void errorCodes_allFiveDefined() {
        assertThat(SearchDelegationErrorCode.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder(
                        "SEARCH_DELEGATION_TIMEOUT",
                        "SEARCH_DELEGATION_NO_EVIDENCE",
                        "SEARCH_DELEGATION_TOOL_LIMIT",
                        "SEARCH_DELEGATION_PROVIDER_ERROR",
                        "SEARCH_DELEGATION_INVALID_INPUT"
                );
    }

    private ToolCallback[] buildCallbacks() {
        return MethodToolCallbackProvider.builder()
                .toolObjects(new DelegationToolStub())
                .build()
                .getToolCallbacks();
    }
}
