package cn.travelbao;

import cn.travelbao.ai.agent.*;
import cn.travelbao.ai.agent.AgentModels.*;
import cn.travelbao.ai.rag.KnowledgeEmbedder;
import cn.travelbao.chat.service.ConversationGate;
import cn.travelbao.common.exception.ServiceProblem;
import cn.travelbao.knowledge.service.KnowledgeService;
import cn.travelbao.trip.dto.*;
import cn.travelbao.trip.service.TripService;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:day4;DB_CLOSE_DELAY=-1","travel.deepseek-api-key=test-only-key","travel.agent.timeout-seconds=2"})
class Day4AgentTest {
    @Autowired AgentService agents;@Autowired TripService trips;@Autowired KnowledgeService knowledge;
    @Autowired FakeDecisions model;@Autowired FakeGateway gateway;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;@Autowired ConversationGate gate;
    String documentId;
    @TestConfiguration static class Models {
        @Bean @Primary FakeDecisions fakeDecisions(){return new FakeDecisions();}
        @Bean @Primary FakeGateway fakeGateway(ObjectMapper json){return new FakeGateway(json);}
        @Bean @Primary KnowledgeEmbedder fakeEmbedder(){return new Day3KnowledgeTest.FakeEmbedder();}
    }
    static class FakeDecisions implements AgentDecisionClient {
        Function<List<Message>,AssistantMessage> answer;AtomicInteger calls=new AtomicInteger();
        public AssistantMessage decide(List<Message> messages,List<ToolCallback> tools,cn.travelbao.knowledge.dto.KnowledgeModels.SearchResult retrieval){calls.incrementAndGet();return answer.apply(messages);}
    }
    static class FakeGateway implements TourismGateway {
        final ObjectMapper json;boolean fail;AtomicInteger calls=new AtomicInteger();
        FakeGateway(ObjectMapper json){this.json=json;}
        public List<Definition> definitions(){return List.of("weather_forecast","search_places","plan_route").stream().map(n->new Definition(n,"测试用工具","{\"type\":\"object\",\"properties\":{}}")).toList();}
        public JsonNode call(String name,Map<String,Object> arguments){calls.incrementAndGet();if(fail)throw new ServiceProblem("MCP_UNAVAILABLE","测试提供方失败");
            return json.valueToTree(Map.of("status","AVAILABLE","provider","offline-test-fixture","queriedAt","2026-10-23T10:00:00Z","sourceUrl","https://example.org/", "message","离线夹具，非实时数据","data",name.equals("search_places")?Map.of("places",List.of(Map.of("id","place-a"),Map.of("id","place-b"))):Map.of()));}
    }
    @BeforeEach void reset(){
        jdbc.update("DELETE FROM knowledge_chunks");jdbc.update("DELETE FROM knowledge_documents");jdbc.update("DELETE FROM knowledge_index_state");
        documentId=knowledge.upload("杭州","测试来源","https://example.org/visit","2026-10-22","visit.txt","测试资料：场馆在测试路88号，开放情况需核对当日公告。".getBytes(StandardCharsets.UTF_8)).document().id();
        model.calls.set(0);gateway.calls.set(0);gateway.fail=false;model.answer=messages->normal(messages);
    }
    AssistantMessage call(String name,Object args){return calls(List.of(new AssistantMessage.ToolCall(UUID.randomUUID().toString(),"function",name,encode(args))));}
    AssistantMessage calls(List<AssistantMessage.ToolCall> calls){return AssistantMessage.builder().content("").toolCalls(calls).build();}
    String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new RuntimeException(e);}}
    Request request(){return new Request(UUID.randomUUID().toString(),trips.create().id(),0,Day2BusinessTest.request(),"按实际查询规划");}
    List<Event> run(Request request){return agents.stream(request).collectList().block(Duration.ofSeconds(5));}
    AssistantMessage normal(List<Message> history){
        int round=model.calls.get();
        if(round==1){var list=new ArrayList<AssistantMessage.ToolCall>();
            for(var entry:Map.of("search_knowledge",Map.of("query","场馆资料"),"weather_forecast",Map.of("city","杭州","date","2026-11-01"),"search_places",Map.of("city","杭州","keyword","场馆")).entrySet())list.add(new AssistantMessage.ToolCall(UUID.randomUUID().toString(),"function",entry.getKey(),encode(entry.getValue())));
            return calls(list);
        }
        if(round==2)return call("plan_route",Map.of("city","杭州","originPlaceId","place-a","destinationPlaceId","place-b","mode","walking"));
        if(round==3){var p=Day2BusinessTest.plan();var source=new Itinerary.Source("模型标题","https://wrong.example/","模型说明",documentId,documentId+"-0","1900-01-01","wrong");return call("calculate_budget",new Itinerary(p.title(),p.summary(),p.days(),p.extraCosts(),List.of(source),p.pendingConfirmations()));}
        for(int i=history.size()-1;i>=0;i--)if(history.get(i) instanceof ToolResponseMessage message)for(var response:message.getResponses())try{var result=json.readTree(response.responseData());if(result.has("preparedPlanId"))return new AssistantMessage(encode(Map.of("preparedPlanId",result.path("preparedPlanId").asText(),"summary","完成")));}catch(Exception ignored){}
        return new AssistantMessage("{}");
    }
    @Test void controlledLoopUsesRealDispatchAndCommitsOnlyCheckedPlan(){
        var request=request();var events=run(request);assertThat(events.get(events.size()-1).type()).isEqualTo("done");
        assertThat(agents.get(request.executionId()).status()).isEqualTo("COMPLETED");assertThat(model.calls).hasValue(3);assertThat(gateway.calls).hasValue(3);
        var conversation=trips.conversation(request.conversationId());assertThat(conversation.version()).isEqualTo(1);assertThat(conversation.draft().promptVersion()).isEqualTo("v4");
        assertThat(conversation.draft().agentExecutionId()).isEqualTo(request.executionId());assertThat(conversation.draft().itinerary().sources().get(0).url()).isEqualTo("https://example.org/visit");
        assertThat(agents.get(request.executionId()).events()).hasSize(events.size());
    }
    @Test void malformedFinalOrInventedPreparedIdNeverWritesDraft(){
        model.answer=messages->new AssistantMessage("{\"preparedPlanId\":\"invented\",\"summary\":\"完成\"}");var r=request();run(r);
        assertThat(agents.get(r.executionId()).code()).isEqualTo("STEP_LIMIT");assertThat(trips.conversation(r.conversationId()).draft()).isNull();
    }
    @Test void validBudgetOnLastPermittedRoundCompletesWithoutExtraModelCall(){
        model.answer=history->{
            int round=model.calls.get();
            if(round<=2)return normal(history);
            if(round<6)return call("search_knowledge",Map.of("query","场馆资料"));
            var p=Day2BusinessTest.plan();
            return call("calculate_budget",new Itinerary(p.title(),p.summary(),p.days(),p.extraCosts(),List.of(),p.pendingConfirmations()));
        };
        var r=request();run(r);
        assertThat(agents.get(r.executionId()).status()).isEqualTo("COMPLETED");
        assertThat(model.calls).hasValue(6);assertThat(trips.conversation(r.conversationId()).version()).isEqualTo(1);
    }
    @Test void tooManyToolsAreStoppedBeforeAnyDispatch(){
        model.answer=messages->{var calls=new ArrayList<AssistantMessage.ToolCall>();for(int i=0;i<11;i++)calls.add(new AssistantMessage.ToolCall("id-"+i,"function","search_places","{}"));return calls(calls);};
        var r=request();run(r);assertThat(agents.get(r.executionId()).code()).isEqualTo("TOOL_LIMIT");assertThat(gateway.calls).hasValue(0);assertThat(trips.conversation(r.conversationId()).version()).isZero();
    }
    @Test void unknownPlaceIdsCannotReachRouteProvider(){
        model.answer=messages->call("plan_route",Map.of("city","杭州","originPlaceId","fabricated-a","destinationPlaceId","fabricated-b","mode","walking"));
        var r=request();run(r);assertThat(gateway.calls).hasValue(0);assertThat(agents.get(r.executionId()).status()).isEqualTo("LIMIT_REACHED");
    }
    @Test void providerFailureTerminatesWithRecordedWarningsAndNoInventedQueryValues(){
        gateway.fail=true;var r=request();run(r);assertThat(agents.get(r.executionId()).status()).isEqualTo("COMPLETED");
        assertThat(trips.conversation(r.conversationId()).draft().itinerary().pendingConfirmations()).anyMatch(s->s.contains("测试提供方失败"));
        assertThat(agents.get(r.executionId()).events().toString()).contains("MCP_UNAVAILABLE");
    }
    @Test void cancellingInflightModelReleasesGateAndLateAnswerCannotCommit() throws Exception {
        var entered=new CountDownLatch(1);var finished=new CountDownLatch(1);
        model.answer=messages->{entered.countDown();try{Thread.sleep(10000);}catch(InterruptedException ignored){}finally{finished.countDown();}return new AssistantMessage("{}");};
        var r=request();var subscription=agents.stream(r).subscribe();assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
        assertThat(agents.cancel(r.executionId()).status()).isEqualTo("CANCELLED");
        try(var newLease=gate.acquire(r.conversationId())){assertThat(finished.await(1,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->gate.acquire(r.conversationId())).hasMessageContaining("正在处理");}
        assertThat(trips.conversation(r.conversationId()).version()).isZero();subscription.dispose();
    }
    @Test void globalDeadlineTerminatesWithoutWaitingForModelAndPreservesDraft(){
        model.answer=messages->{try{Thread.sleep(10000);}catch(InterruptedException ignored){}return new AssistantMessage("{}");};
        var r=request();long start=System.nanoTime();run(r);assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(4));
        assertThat(agents.get(r.executionId()).status()).isEqualTo("TIMED_OUT");assertThat(trips.conversation(r.conversationId()).draft()).isNull();
    }
    @Test void staleVersionAndDuplicateExecutionAreRejected(){
        var r=request();run(r);var duplicate=run(r);assertThat(duplicate.get(0).type()).isEqualTo("error");
        var stale=new Request(UUID.randomUUID().toString(),r.conversationId(),0,null,"再调整");run(stale);assertThat(trips.conversation(r.conversationId()).version()).isEqualTo(1);
    }
}
