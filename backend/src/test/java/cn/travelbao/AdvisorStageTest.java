package cn.travelbao;
import cn.travelbao.chat.dto.ChatRequest;
import cn.travelbao.chat.service.ChatService;
import cn.travelbao.config.TravelSettings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import static org.assertj.core.api.Assertions.*;
import reactor.test.StepVerifier;
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:advisor-stage;DB_CLOSE_DELAY=-1")
class AdvisorStageTest {
 @Autowired ChatMemory memory;
 @Test void jdbcMemoryKeepsConversationIdsSeparate(){memory.add("stage-a",new UserMessage("偏好代码：青竹17"));assertThat(memory.get("stage-a").toString()).contains("青竹17");assertThat(memory.get("stage-b")).isEmpty();}
 @Test void missingModelKeyEndsStreamWithoutCallingProvider(){var settings=new TravelSettings("https://api.deepseek.com","","test","http://127.0.0.1:11434","bge-m3","http://127.0.0.1:8081","/mcp",2);StepVerifier.create(new ChatService(null,settings).stream(new ChatRequest("杭州旅行",null))).assertNext(e->assertThat(e.code()).isEqualTo("MODEL_NOT_CONFIGURED")).verifyComplete();}
}
