package cn.travelbao.ai.agent;
import cn.travelbao.knowledge.dto.KnowledgeModels.SearchResult;
import java.util.List;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.ToolCallback;
public interface AgentDecisionClient {
    AssistantMessage decide(List<Message> messages,List<ToolCallback> tools,SearchResult retrieval);
}
