package cn.travelbao.ai.rag;

import cn.travelbao.common.exception.ServiceProblem;
import cn.travelbao.knowledge.dto.KnowledgeModels.*;
import cn.travelbao.knowledge.service.KnowledgeService;
import cn.travelbao.trip.dto.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class PlanKnowledgeGrounding {
    private final KnowledgeService knowledge;
    public PlanKnowledgeGrounding(KnowledgeService knowledge) { this.knowledge=knowledge; }
    public SearchResult retrieve(TravelRequest request) {
        return knowledge.search(request.destination(),request.destination()+"旅游参观指南，景点地址、开放时间与参观须知。偏好："+request.preferences());
    }
    public Itinerary bind(Itinerary itinerary,SearchResult retrieval,Itinerary previous) {
        var allowed=new LinkedHashMap<String,Itinerary.Source>();
        if (previous!=null) for (var source:previous.sources()) if (source.chunkId()!=null) allowed.put(source.chunkId(),source);
        for (var hit:retrieval.hits()) allowed.put(hit.chunkId(),new Itinerary.Source(hit.title(),hit.sourceUrl(),excerpt(hit.excerpt()),
                hit.documentId(),hit.chunkId(),hit.verifiedAt(),hit.origin()));
        // A retrieved document need not support this itinerary. Never force an unrelated citation.
        var references=new LinkedHashMap<String,Itinerary.Source>();
        for (var source:itinerary.sources()) {
            var actual=source.chunkId()==null?null:allowed.get(source.chunkId());
            if (actual==null || !Objects.equals(actual.documentId(),source.documentId())) throw invalid("行程引用了本次检索与原有效行程中不存在的资料片段。");
            references.put(actual.chunkId(),actual);
        }
        return new Itinerary(itinerary.title(),itinerary.summary(),itinerary.days(),itinerary.extraCosts(),List.copyOf(references.values()),itinerary.pendingConfirmations());
    }
    private String excerpt(String value) { return value.length()<=280?value:value.substring(0,280)+"…"; }
    private static ServiceProblem invalid(String message) { return new ServiceProblem("INVALID_MODEL_OUTPUT",message); }
}
