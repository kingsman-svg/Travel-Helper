package cn.travelbao.config;

import cn.travelbao.ai.tools.BudgetProbeTool;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AiConfigurationTest {
    @Test
    void structuredPlanningUsesProviderJsonModeWithoutLosingThinkingSetting() {
        var rest = RestClient.builder();
        var server = MockRestServiceServer.bindTo(rest).build();
        var settings = new TravelSettings("https://model.test", "test-only-key", "test-model",
                "http://127.0.0.1:11434", "test-embedding", "http://127.0.0.1:8081", "/mcp", 10);
        var client = new AiConfiguration().configuredClient(settings,rest);
        server.expect(requestTo("https://model.test/chat/completions"))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andExpect(jsonPath("$.thinking.type").value("disabled"))
                .andExpect(jsonPath("$.max_tokens").value(10000))
                .andRespond(withSuccess("""
                    {"id":"json-1","object":"chat.completion","created":1,"model":"test-model",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"{}"},"finish_reason":"stop"}]}
                    """, MediaType.APPLICATION_JSON));
        assertThat(client.prompt().user("返回JSON").options(cn.travelbao.ai.prompt.StructuredPlanGenerator.options()).call().content()).isEqualTo("{}");
        server.verify();
    }
    @Test
    void compatibleRequestDisablesThinkingAndReturnsActualToolResultToModel() {
        var rest = RestClient.builder();
        var server = MockRestServiceServer.bindTo(rest).build();
        var settings = new TravelSettings("https://model.test", "test-only-key", "test-model",
                "http://127.0.0.1:11434", "test-embedding", "http://127.0.0.1:8081", "/mcp", 10);
        var client = new AiConfiguration().configuredClient(settings, rest);
        server.expect(requestTo("https://model.test/chat/completions"))
                .andExpect(header("Authorization", "Bearer test-only-key"))
                .andExpect(jsonPath("$.thinking.type").value("disabled"))
                .andExpect(jsonPath("$.model").value("test-model"))
                .andRespond(withSuccess("""
                    {"id":"call-1","object":"chat.completion","created":1,"model":"test-model",
                     "choices":[{"index":0,"message":{"role":"assistant","content":null,
                     "tool_calls":[{"id":"budget-1","type":"function","function":{"name":"calculate_budget",
                     "arguments":"{\\"accommodation\\":1500,\\"food\\":800,\\"transport\\":500}"}}]},
                     "finish_reason":"tool_calls"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                    """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://model.test/chat/completions"))
                .andExpect(content().string(containsString("2800")))
                .andExpect(content().string(containsString("budget-1")))
                .andRespond(withSuccess("""
                    {"id":"call-2","object":"chat.completion","created":1,"model":"test-model",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"总费用为2800元。"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                    """, MediaType.APPLICATION_JSON));
        var tool = new BudgetProbeTool();
        String answer = client.prompt().user("计算预算").tools(tool).call().content();
        assertThat(tool.calls()).isEqualTo(1);
        assertThat(tool.lastResult().total()).isEqualTo(2800);
        assertThat(answer).contains("2800");
        server.verify();
    }
}
