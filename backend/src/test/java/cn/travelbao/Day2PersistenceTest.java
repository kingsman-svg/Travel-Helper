package cn.travelbao;

import cn.travelbao.trip.dto.*;
import cn.travelbao.trip.service.*;
import cn.travelbao.trip.repository.TravelRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:day2;DB_CLOSE_DELAY=-1", "travel.deepseek-api-key=test-only-key"})
class Day2PersistenceTest {
    @Autowired TripService trips;
    @Autowired TravelRepository repository;
    @Autowired ChatMemory memory;
    @Autowired ChatClient client;
    @Autowired FakeModel model;
    @Autowired ObjectMapper json;
    @TestConfiguration static class Models {
        @Bean FakeModel fakeModel() { return new FakeModel(); }
        @Bean @Primary ChatClient testChatClient(FakeModel model, ChatMemory memory) {
            return ChatClient.builder(model).defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build()).build();
        }
    }
    static class FakeModel implements ChatModel {
        Function<Prompt,String> answer = prompt -> "测试回复";
        List<org.springframework.ai.chat.messages.Message> seen;
        public ChatResponse call(Prompt prompt) {
            seen = List.copyOf(prompt.getInstructions());
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer.apply(prompt)))));
        }
    }
    @Test void invalidModelOutputDoesNotOverwriteValidDraftOrSavedVersion() throws Exception {
        String id = trips.create().id();
        model.answer = p -> { try { return json.writeValueAsString(Day2BusinessTest.plan()); } catch(Exception e) { throw new RuntimeException(e); } };
        var generated = trips.generate(id,0,Day2BusinessTest.request());
        var saved = trips.save(id,1);
        model.answer = p -> "{\"title\":\"不完整\"}";
        assertThatThrownBy(() -> trips.adjust(id,1,"第二天少走路")).isInstanceOf(RuntimeException.class);
        assertThat(trips.conversation(id).version()).isEqualTo(1);
        assertThat(trips.get(saved.id()).draft().itinerary()).isEqualTo(generated.draft().itinerary());
        assertThatThrownBy(() -> trips.save(id,0)).hasMessageContaining("已有更新");
    }
    @Test void advisorLoadsOnlyTheSpecifiedConversationAndRepositoryRetainsMemory() {
        String a = trips.create().id(), b = trips.create().id();
        model.answer = p -> "已记住";
        client.prompt().user("记住：旅行偏好代号为绿茶17").advisors(x -> x.param(ChatMemory.CONVERSATION_ID,a)).call().content();
        client.prompt().user("我有什么偏好？").advisors(x -> x.param(ChatMemory.CONVERSATION_ID,a)).call().content();
        assertThat(model.seen.toString()).contains("绿茶17");
        client.prompt().user("我有什么偏好？").advisors(x -> x.param(ChatMemory.CONVERSATION_ID,b)).call().content();
        assertThat(model.seen.toString()).doesNotContain("绿茶17");
        assertThat(memory.get(a).toString()).contains("绿茶17");
        assertThat(repository.get(a).content()).isNull();
    }
    @Test void editSaveAndMarkdownUseValidatedVersionAndDecimalTotals() throws Exception {
        String id = trips.create().id();
        model.answer = p -> { try { return json.writeValueAsString(Day2BusinessTest.plan()); } catch(Exception e) { throw new RuntimeException(e); } };
        trips.generate(id,0,Day2BusinessTest.request());
        var plan = Day2BusinessTest.plan();
        trips.edit(id,1,new Itinerary("手动编辑的标题",plan.summary(),plan.days(),plan.extraCosts(),plan.sources(),plan.pendingConfirmations()));
        var saved = trips.save(id,2);
        assertThat(repository.trip(saved.id()).content().itinerary().title()).isEqualTo("手动编辑的标题");
        assertThat(new MarkdownExporter().export(saved)).contains("手动编辑的标题","1700.90","2026-11-02");
        assertThat(trips.conversation(id).savedTripId()).isEqualTo(saved.id());
    }
    @Test void oneInvalidAnswerCanBeCorrectedBeforeAnyDraftIsWritten() throws Exception {
        String id = trips.create().id();
        String valid = json.writeValueAsString(Day2BusinessTest.plan());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        model.answer = p -> calls.incrementAndGet() == 1 ? "{\"title\":\"不完整\"}" : valid;
        assertThat(trips.generate(id,0,Day2BusinessTest.request()).version()).isEqualTo(1);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(repository.messages(id)).hasSize(2);
    }
}
