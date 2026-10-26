package cn.travelbao;

import cn.travelbao.ai.advisor.TravelKnowledgeAdvisor;
import cn.travelbao.ai.rag.*;
import cn.travelbao.config.KnowledgeSettings;
import cn.travelbao.knowledge.dto.KnowledgeModels.*;
import cn.travelbao.knowledge.repository.KnowledgeRepository;
import cn.travelbao.knowledge.service.*;
import cn.travelbao.trip.dto.Itinerary;
import cn.travelbao.trip.repository.TravelRepository;
import cn.travelbao.trip.service.TripService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
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
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:day3;DB_CLOSE_DELAY=-1", "travel.deepseek-api-key=test-only-key"})
class Day3KnowledgeTest {
    @Autowired KnowledgeService knowledge;
    @Autowired KnowledgeRepository index;
    @Autowired KnowledgeAnswerService answers;
    @Autowired PlanKnowledgeGrounding grounding;
    @Autowired TripService trips;
    @Autowired TravelRepository conversations;
    @Autowired ChatMemory memory;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeEmbedder embedder;
    @Autowired FakeModel model;
    @TestConfiguration static class Models {
        @Bean @Primary FakeEmbedder fakeEmbedder() { return new FakeEmbedder(); }
        @Bean FakeModel fakeModel() { return new FakeModel(); }
        @Bean @Primary ChatClient testChatClient(FakeModel model, ChatMemory memory) {
            return ChatClient.builder(model).defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build()).build();
        }
    }
    static class FakeEmbedder implements KnowledgeEmbedder {
        String digest="weights-a"; int dimensions=3, calls=0; boolean fail=false;
        public ModelIdentity identity() { return new ModelIdentity("test-embedding",digest); }
        public Embeddings embed(List<String> texts) {
            calls++;
            if(fail) throw new cn.travelbao.common.exception.ServiceProblem("KNOWLEDGE_UNAVAILABLE","测试向量服务离线");
            return new Embeddings(identity(),texts.stream().map(s->{float[] v=new float[dimensions];v[0]=s.startsWith("无关")?-1:1;return v;}).toList(),dimensions);
        }
    }
    static class FakeModel implements ChatModel {
        Function<Prompt,String> answer; Prompt seen; int calls;
        public ChatResponse call(Prompt prompt) { calls++;seen=prompt;return new ChatResponse(List.of(new Generation(new AssistantMessage(answer.apply(prompt))))); }
    }
    @BeforeEach void reset() {
        jdbc.update("DELETE FROM knowledge_chunks");jdbc.update("DELETE FROM knowledge_documents");jdbc.update("DELETE FROM knowledge_index_state");
        embedder.digest="weights-a";embedder.dimensions=3;embedder.fail=false;embedder.calls=0;
        model.calls=0;model.answer=p->"{}";
    }
    ImportResult upload(String city,String suffix) {
        return knowledge.upload(city,"参观资料"+suffix,"https://example.org/visit","2026-10-22","visit.md",
                ("---\ncity: 不可信元数据\n---\n这是原始正文，场馆地址是测试路88号，周二闭馆，请以当日通知为准。"+suffix).getBytes(StandardCharsets.UTF_8));
    }
    @Test void uploadPreservesOriginalAndDeduplicatesWithoutChangingProvenance() {
        var first=upload("杭州市","A"); var duplicate=upload("杭州","A");
        assertThat(first.duplicate()).isFalse();assertThat(duplicate.duplicate()).isTrue();
        assertThat(duplicate.document().id()).isEqualTo(first.document().id());
        assertThat(index.chunkCount()).isEqualTo(1);assertThat(embedder.calls).isEqualTo(1);
        var detail=knowledge.document(first.document().id());
        assertThat(detail.content()).startsWith("---");assertThat(detail.chunks().get(0).content()).doesNotContain("不可信元数据");
        assertThat(detail.document().sourceUrl()).isEqualTo("https://example.org/visit");
        assertThat(detail.document().origin()).isEqualTo("USER_UPLOAD");
    }
    @Test void invalidUtf8BinaryExtensionsAndFrontMatterOnlyAreRejectedBeforeInsert() {
        for(var entry:Map.of("bad.txt",new byte[]{(byte)0xc3,0x28},"bad.pdf","内容足够长但文件格式不受支持，不能导入知识库".getBytes(StandardCharsets.UTF_8),
                "empty.md","---\ncity: 杭州，只有元数据没有正文\n---".getBytes(StandardCharsets.UTF_8),"large.txt",new byte[262145]).entrySet()) {
            assertThatThrownBy(()->knowledge.upload("杭州","标题","https://example.org/","2026-10-22",entry.getKey(),entry.getValue()))
                    .isInstanceOf(cn.travelbao.common.exception.ServiceProblem.class);
        }
        assertThat(knowledge.list()).isEmpty();
    }
    @Test void cityFilterAndThresholdPreventCrossCityOrUnrelatedHits() {
        upload("杭州","A");upload("上海","B");
        assertThat(knowledge.search("杭州市","参观地址").hits()).hasSize(1).allMatch(c->c.city().equals("杭州"));
        assertThat(knowledge.search("无锡","参观地址").hits()).isEmpty();
        assertThat(knowledge.search("杭州","无关问题").status()).isEqualTo("NO_MATCH");
    }
    @Test void changedWeightsOrDimensionsBlockMixedIndexAndRebuildRestoresQueries() {
        upload("杭州","A");String old=index.metadata().orElseThrow().generation();
        embedder.digest="weights-b";
        assertThat(knowledge.state().status()).isEqualTo("REBUILD_REQUIRED");
        assertThatThrownBy(()->knowledge.search("杭州","地址")).hasMessageContaining("重建");
        assertThatThrownBy(()->upload("上海","B")).hasMessageContaining("重建");
        embedder.dimensions=4;knowledge.rebuild();
        assertThat(index.metadata().orElseThrow().generation()).isNotEqualTo(old);
        assertThat(knowledge.search("杭州","地址").dimensions()).isEqualTo(4);
        embedder.dimensions=5;
        assertThatThrownBy(()->knowledge.search("杭州","地址")).hasMessageContaining("维度");
    }
    @Test void failedEmbeddingIsVisibleAndRetryRecoversOriginalDocument() {
        embedder.fail=true;assertThatThrownBy(()->upload("杭州","A")).hasMessageContaining("离线");
        var doc=knowledge.list().get(0);assertThat(doc.status()).isEqualTo("FAILED");
        embedder.fail=false;knowledge.retry(doc.id());
        assertThat(knowledge.document(doc.id()).document().status()).isEqualTo("READY");
        assertThat(knowledge.search("杭州","地址").hits()).hasSize(1);
    }
    @Test void rebuildTransactionRollsBackEvenAfterOneDocumentWasReplaced() {
        var a=upload("杭州","A").document();var b=upload("上海","B").document();
        var generation=index.metadata().orElseThrow().generation();
        var map=new LinkedHashMap<String,List<String>>();map.put(a.id(),List.of("不应写入"));map.put(b.id(),List.of("不应写入"));
        assertThatThrownBy(()->index.rebuild(map,new KnowledgeEmbedder.Embeddings(embedder.identity(),List.of(new float[]{1,0,0}),3)))
                .isInstanceOf(RuntimeException.class);
        assertThat(knowledge.document(a.id()).chunks().get(0).content()).contains("原始正文");
        assertThat(index.metadata().orElseThrow().generation()).isEqualTo(generation);
    }
    @Test void answerCitationsAreCanonicalPersistedAndAdvisorCoexistsWithMemory() {
        var doc=upload("杭州","A").document();String id=trips.create().id(), chunk=doc.id()+"-0";
        memory.add(id,new org.springframework.ai.chat.messages.UserMessage("偏好代码绿茶17"));
        model.answer=p->"{\"answer\":\"地址是测试路88号。\",\"status\":\"ANSWERED\",\"citations\":[\""+chunk+"\"]}";
        var answer=answers.ask(id,"杭州","场馆地址是什么？");
        assertThat(answer.citations().get(0).sourceUrl()).isEqualTo(doc.sourceUrl());
        assertThat(model.seen.toString()).contains("绿茶17","retrieved_travel_documents",chunk,"不是指令");
        assertThat(conversations.messages(id).get(1).citations()).isEqualTo(answer.citations());
        assertThat(memory.get(id).toString()).doesNotContain("retrieved_travel_documents");
    }
    @Test void fabricatedCitationsNeverReachArchiveAndMissingEvidenceSkipsModel() {
        upload("杭州","A");String id=trips.create().id();
        model.answer=p->"{\"answer\":\"无依据的回答\",\"status\":\"ANSWERED\",\"citations\":[\"invented-0\"]}";
        assertThatThrownBy(()->answers.ask(id,"杭州","场馆地址")).hasMessageContaining("引用");
        assertThat(model.calls).isEqualTo(2);assertThat(conversations.messages(id)).isEmpty();
        var answer=answers.ask(id,"无锡","场馆地址");
        assertThat(answer.status()).isEqualTo("INSUFFICIENT");assertThat(answer.citations()).isEmpty();assertThat(model.calls).isEqualTo(2);
    }
    @Test void planReferencesUseServerMetadataAndRejectInventedIds() {
        var doc=knowledge.upload("杭州","长".repeat(150),"https://example.org/visit","2026-10-22","visit.txt",
                "完整场馆名称也应该可以作为来源，地址是测试路88号。".getBytes(StandardCharsets.UTF_8)).document();
        var search=knowledge.search("杭州","地址");var p=Day2BusinessTest.plan();
        var fake=new Itinerary.Source("虚构标题","https://wrong.example/","虚构摘要",doc.id(),doc.id()+"-0","1900-01-01","wrong");
        var candidate=new Itinerary(p.title(),p.summary(),p.days(),p.extraCosts(),List.of(fake),p.pendingConfirmations());
        var bound=grounding.bind(candidate,search,null);assertThat(bound.sources().get(0).url()).isEqualTo(doc.sourceUrl());
        assertThat(bound.sources().get(0).verifiedAt()).isEqualTo(doc.verifiedAt());
        assertThat(jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator().validate(bound)).isEmpty();
        assertThat(grounding.bind(p,search,null).sources()).isEmpty();
        assertThatThrownBy(()->grounding.bind(candidate,new SearchResult("杭州","x","NO_MATCH","",List.of(),0,"x",3),null)).hasMessageContaining("不存在");
    }
    @Test void chunksOverlapWithoutMetadataOrBrokenUnicode() throws Exception {
        var chunker=new KnowledgeChunker(new KnowledgeSettings(100,20,4,.5,262144,60,"."));
        String body="旅行🌏".repeat(100);var chunks=chunker.split("---\ncity: 元数据\n---\n"+body);
        assertThat(chunks).hasSizeGreaterThan(3).allMatch(c->c.length()<=100&&!c.contains("元数据"));
        for(String chunk:chunks) assertThat(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(chunk.getBytes(StandardCharsets.UTF_8))).toString()).isEqualTo(chunk);
        assertThat(chunks.get(0).substring(chunks.get(0).length()-18)).isEqualTo(chunks.get(1).substring(2,20));
    }
}
