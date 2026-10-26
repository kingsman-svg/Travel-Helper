package cn.travelbao.config;
import cn.travelbao.ai.agent.AgentDecisionClient;
import cn.travelbao.ai.advisor.TravelKnowledgeAdvisor;
import java.time.Duration;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
@Configuration
@EnableConfigurationProperties(AgentSettings.class)
public class AgentConfiguration {
    @Bean AgentDecisionClient agentDecisionClient(TravelSettings settings,AiConfiguration ai,TravelKnowledgeAdvisor advisor){
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(Duration.ofSeconds(5));factory.setReadTimeout(Duration.ofSeconds(30));
        // The agent owns one tool loop; no default memory/tool loop is attached to this client.
        var client=ai.configuredClient(settings,RestClient.builder().requestFactory(factory));
        return (messages,tools,retrieval)->client.prompt().messages(messages)
                .options(OpenAiChatOptions.builder().temperature(.2).maxTokens(9000).toolCallbacks(tools).internalToolExecutionEnabled(false).parallelToolCalls(true).build())
                .advisors(advisor).advisors(a->a.param(TravelKnowledgeAdvisor.CONTEXT,retrieval)).call().chatResponse().getResult().getOutput();
    }
}
