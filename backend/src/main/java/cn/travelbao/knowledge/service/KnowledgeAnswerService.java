package cn.travelbao.knowledge.service;

import cn.travelbao.ai.advisor.TravelKnowledgeAdvisor;
import cn.travelbao.ai.prompt.*;
import cn.travelbao.chat.service.ConversationGate;
import cn.travelbao.common.exception.*;
import cn.travelbao.config.TravelSettings;
import cn.travelbao.knowledge.dto.KnowledgeModels.*;
import cn.travelbao.trip.repository.TravelRepository;
import java.util.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeAnswerService {
    private final KnowledgeService knowledge;
    private final TravelRepository repository;
    private final ConversationGate gate;
    private final ChatClient client;
    private final TravelKnowledgeAdvisor advisor;
    private final ItineraryOutputParser parser;
    private final TravelSettings settings;
    public record GroundedReply(String answer,String status,List<String> citations) {}
    public KnowledgeAnswerService(KnowledgeService knowledge,TravelRepository repository,ConversationGate gate,ChatClient client,
                                  TravelKnowledgeAdvisor advisor,ItineraryOutputParser parser,TravelSettings settings) {
        this.knowledge=knowledge; this.repository=repository; this.gate=gate; this.client=client;
        this.advisor=advisor; this.parser=parser; this.settings=settings;
    }
    public Answer ask(String conversationId,String city,String message) {
        try(var ignored=gate.acquire(conversationId)) {
            repository.get(conversationId);
            SearchResult retrieval=knowledge.search(city,message);
            if (retrieval.hits().isEmpty()) return save(conversationId,message,new Answer("当前知识库没有足够资料回答这个问题。"+retrieval.message(),"INSUFFICIENT",List.of(),retrieval));
            if (!settings.modelConfigured()) throw new ServiceProblem("MODEL_NOT_CONFIGURED","请先配置本机 DeepSeek 密钥并重启后端。");
            String system="你是旅游宝知识库问答助手。只依据本次检索片段回答，当前片段是事实的唯一依据，聊天历史仅用于理解指代。"
                    +"资料不足（包括问题询问未提供的实时信息）时 status=INSUFFICIENT，citations=[]，不要凭常识或历史消息补答案。"
                    +"能够回答时 status=ANSWERED，用简明中文回答，citations 只列实际支持回答的 chunkId，不要写 URL 或虚构ID。"
                    +"不同场馆与城市的规则不能混用，注意资料适用日期。输出一个 JSON 对象，每个键只出现一次。";
            String user=message+"\n"+new BeanOutputConverter<>(GroundedReply.class).getFormat();
            var allowed=new LinkedHashMap<String,Citation>(); retrieval.hits().forEach(hit->allowed.put(hit.chunkId(),hit));
            String correction="";
            for(int attempt=0;attempt<2;attempt++) {
                String reply;
                try {
                    reply=client.prompt().system(system).user(user+correction).options(StructuredPlanGenerator.options())
                            .advisors(advisor).advisors(a->a.param(ChatMemory.CONVERSATION_ID,conversationId).param(TravelKnowledgeAdvisor.CONTEXT,retrieval)).call().content();
                } catch(Exception e) { throw ProblemMessages.describe(e); }
                try {
                    var result=parser.parse(reply,GroundedReply.class);
                    if ("INSUFFICIENT".equals(result.status())) return save(conversationId,message,new Answer("当前知识库没有足够资料回答这个问题。请补充相关资料或换一个有资料依据的问题。","INSUFFICIENT",List.of(),retrieval));
                    if (!"ANSWERED".equals(result.status()) || result.answer()==null || result.answer().isBlank() || result.answer().length()>6000
                            || result.citations()==null || result.citations().isEmpty() || result.citations().size()>retrieval.hits().size()) throw invalid();
                    var citations=new ArrayList<Citation>();
                    for(String id:new LinkedHashSet<>(result.citations())) { if (!allowed.containsKey(id)) throw invalid(); citations.add(allowed.get(id)); }
                    return save(conversationId,message,new Answer(result.answer(),"ANSWERED",List.copyOf(citations),retrieval));
                } catch(ServiceProblem e) { correction="\n上一版格式或引用未通过校验。请只使用本次真实 chunkId；无依据时返回 INSUFFICIENT 和空引用。"; }
            }
            throw invalid();
        }
    }
    private Answer save(String id,String question,Answer answer) {
        repository.addExchange(id,question,answer.answer(),answer.citations()); return answer;
    }
    private static ServiceProblem invalid() { return new ServiceProblem("INVALID_RAG_OUTPUT","回答的格式或引用未通过校验，未写入聊天记录，请重试。"); }
}
