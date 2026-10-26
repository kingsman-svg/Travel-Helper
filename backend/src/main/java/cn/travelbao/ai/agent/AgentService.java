package cn.travelbao.ai.agent;

import cn.travelbao.ai.agent.AgentModels.*;
import cn.travelbao.ai.prompt.*;
import cn.travelbao.ai.rag.PlanKnowledgeGrounding;
import cn.travelbao.chat.service.ConversationGate;
import cn.travelbao.common.exception.*;
import cn.travelbao.config.*;
import cn.travelbao.knowledge.dto.KnowledgeModels.*;
import cn.travelbao.knowledge.service.KnowledgeService;
import cn.travelbao.trip.dto.*;
import cn.travelbao.trip.repository.TravelRepository;
import cn.travelbao.trip.service.*;
import com.fasterxml.jackson.databind.*;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.*;

@Service
public class AgentService {
    private final AgentDecisionClient decisions;
    private final TourismGateway gateway;
    private final AgentRepository executions;
    private final TravelRepository repository;
    private final TripService trips;
    private final ItineraryValidator validator;
    private final ItineraryOutputParser parser;
    private final KnowledgeService knowledge;
    private final PlanKnowledgeGrounding grounding;
    private final ConversationGate gate;
    private final ChatMemory memory;
    private final ObjectMapper json;
    private final TravelSettings travel;
    private final AgentSettings settings;
    private final TransactionTemplate transaction;
    private final ExecutorService workers=Executors.newFixedThreadPool(2);
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    private final Map<String,Session> active=new ConcurrentHashMap<>();
    public AgentService(AgentDecisionClient decisions,TourismGateway gateway,AgentRepository executions,TravelRepository repository,TripService trips,
                        ItineraryValidator validator,ItineraryOutputParser parser,KnowledgeService knowledge,PlanKnowledgeGrounding grounding,
                        ConversationGate gate,ChatMemory memory,ObjectMapper json,TravelSettings travel,AgentSettings settings,PlatformTransactionManager manager){
        this.decisions=decisions;this.gateway=gateway;this.executions=executions;this.repository=repository;this.trips=trips;this.validator=validator;
        this.parser=parser;this.knowledge=knowledge;this.grounding=grounding;this.gate=gate;this.memory=memory;this.json=json;this.travel=travel;this.settings=settings;transaction=new TransactionTemplate(manager);
    }
    @EventListener(ApplicationReadyEvent.class) void recover(){executions.recover();}
    public Flux<Event> stream(Request request){return Flux.create(sink->start(request,sink),FluxSink.OverflowStrategy.BUFFER);}
    private synchronized void start(Request request,FluxSink<Event> sink){
        ConversationGate.Lease lease=null;
        try {
            if(!travel.modelConfigured())throw problem("MODEL_NOT_CONFIGURED","请先配置本机 DeepSeek 密钥。");
            if(active.size()>=2)throw problem("AGENT_BUSY","已有两个规划正在执行，请稍后再试。");
            if(executions.exists(request.executionId()))throw problem("VERSION_CONFLICT","执行ID已经使用，请重新开始。");
            lease=gate.acquire(request.conversationId());var row=repository.get(request.conversationId());
            if(row.version()!=request.expectedVersion())throw TravelRepository.stale();
            TravelRequest confirmed=request.request()!=null?request.request():row.content()==null?null:row.content().request();validator.request(confirmed);
            Itinerary previous=request.request()==null&&row.content()!=null?row.content().itinerary():null;
            executions.create(request.executionId(),request.conversationId());
            var session=new Session(request,confirmed,previous,lease,sink);active.put(request.executionId(),session);
            sink.onCancel(()->session.stop("CANCELLED","CANCELLED","已停止智能规划，原有效行程保留。"));
            session.emit("started",Map.of("conversationId",request.conversationId(),"maxSteps",settings.maxSteps(),"maxToolCalls",settings.maxToolCalls(),"timeoutSeconds",settings.timeoutSeconds()));
            session.deadline=timer.schedule(()->session.stop("TIMED_OUT","AGENT_TIMEOUT","已达到执行时间上限，原有效行程保留。"),settings.timeoutSeconds(),TimeUnit.SECONDS);
            session.work=workers.submit(()->run(session));
        }catch(Exception error){if(lease!=null)lease.close();var p=safe(error);sink.next(new Event(1,"error",request.executionId(),Map.of("code",p.code(),"message",p.getMessage()),Instant.now()));sink.complete();}
    }
    public Run get(String id){return executions.get(id);}
    public Optional<Run> latest(String conversation){repository.get(conversation);return executions.latest(conversation);}
    public Run cancel(String id){var session=active.get(id);if(session!=null)session.stop("CANCELLED","CANCELLED","已停止智能规划，原有效行程保留。");return executions.get(id);}
    private void run(Session s){
        s.thread=Thread.currentThread();
        try {
            s.check();var callbacks=callbacks(gateway.definitions());s.check();
            var messages=new ArrayList<Message>();
            String system=new ClassPathResource("prompts/v4/agent.txt").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            system+="\n今天（Asia/Shanghai）："+LocalDate.now(ZoneId.of("Asia/Shanghai"))+"。支持城市：杭州、苏州、上海。";
            messages.add(new SystemMessage(system));
            // Keep incomplete tool dialogues out of the normal conversation memory.
            for(var m:memory.get(s.request.conversationId()))if(m instanceof UserMessage||m instanceof AssistantMessage a&&!a.hasToolCalls())messages.add(m);
            String user="权威需求："+encode(s.confirmed)+(s.previous==null?"":"\n当前有效行程："+encode(s.previous))+"\n用户要求："+Objects.toString(s.request.instruction(),"按真实查询规划旅行");
            messages.add(new UserMessage(user));
            for(int round=1;round<=settings.maxSteps();round++){
                s.check();s.emit("action",Map.of("round",round,"summary",round==1?"检索旅行资料，并向模型提供真实工具定义。":"根据已返回的工具结果继续规划。"));
                var output=decisions.decide(List.copyOf(messages),callbacks,s.retrieval());s.check();
                if(output==null)throw problem("INVALID_MODEL_OUTPUT","模型没有返回有效决策。");
                if(output.hasToolCalls()){
                    var calls=output.getToolCalls();
                    if(calls.size()>settings.maxToolCalls()-s.calls)throw problem("TOOL_LIMIT","工具调用次数达到上限，原行程保留。");
                    var ids=new HashSet<String>();for(var call:calls)if(call.id()==null||call.id().isBlank()||!ids.add(call.id())||call.arguments()==null||call.arguments().length()>80000)throw problem("INVALID_MODEL_OUTPUT","模型工具参数无效。");
                    messages.add(output);var responses=new ArrayList<ToolResponseMessage.ToolResponse>();
                    for(var call:calls){s.check();responses.add(new ToolResponseMessage.ToolResponse(call.id(),call.name(),execute(s,call)));}
                    messages.add(ToolResponseMessage.builder().responses(responses).build());
                    // A validated final submission completes directly, including on the last permitted round.
                    if(s.prepared!=null){s.commit();return;}
                }else{
                    try {
                        var finalReply=parser.parse(output.getText(),Completion.class);
                        if(s.prepared==null||!s.preparedId.equals(finalReply.preparedPlanId()))throw problem("INVALID_MODEL_OUTPUT","请先调用 calculate_budget 校验完整行程，再使用返回的 preparedPlanId 完成。");
                        s.commit();return;
                    }catch(ServiceProblem error){messages.add(output);messages.add(new UserMessage("完成格式未通过校验："+error.getMessage()+"。继续使用真实工具并返回最终JSON。"));}
                }
            }
            throw problem("STEP_LIMIT","已达到模型决策轮数上限，原有效行程保留。");
        }catch(Exception error){var p=safe(error);s.stop(p.code().equals("AGENT_TIMEOUT")?"TIMED_OUT":p.code().equals("STEP_LIMIT")||p.code().equals("TOOL_LIMIT")?"LIMIT_REACHED":"FAILED",p.code(),p.getMessage());}
        finally{s.lease.close();}
    }
    private List<ToolCallback> callbacks(List<TourismGateway.Definition> external){
        var result=new ArrayList<ToolCallback>();
        for(var tool:external)result.add(definition(tool.name(),tool.description(),tool.inputSchema()));
        result.add(definition("search_knowledge","按当前旅行城市检索本机知识库，query为问题或准确场馆名，返回可引用的真实片段。","{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"maxLength\":2000}},\"required\":[\"query\"],\"additionalProperties\":false}"));
        result.add(definition("calculate_budget","提交完整拟定行程（参数直接为itinerary对象），检查费用、时间、来源和步行；校验成功后系统直接完成规划，失败则读取错误并修正。",new BeanOutputConverter<>(Itinerary.class).getJsonSchema()));
        return List.copyOf(result);
    }
    private ToolCallback definition(String name,String description,String schema){return new ToolCallback(){public ToolDefinition getToolDefinition(){return new DefaultToolDefinition(name,description,schema);}public String call(String arguments){throw new IllegalStateException("Automatic tool execution is disabled");}};}
    private String execute(Session s,AssistantMessage.ToolCall call){
        s.calls++;s.prepared=null;long start=System.nanoTime();JsonNode arguments;
        try{arguments=json.readTree(call.arguments());if(arguments==null||!arguments.isObject())throw new IllegalArgumentException();}
        catch(Exception e){return toolError(s,call,Map.of(),"INVALID_ARGUMENT","工具参数必须是JSON对象。",start);}
        Map<String,Object> display=json.convertValue(arguments,Map.class);
        s.emit("tool_start",Map.of("callId",call.id(),"name",call.name(),"arguments",display,"trigger","模型选择"));
        Object result;
        try{
            s.check();
            if(call.name().equals("search_knowledge")){
                s.attempts.add(call.name());String query=arguments.path("query").asText();var found=knowledge.search(s.confirmed.destination(),query);
                found.hits().forEach(hit->s.hits.put(hit.chunkId(),hit));s.attempts.add(call.name());s.prepared=null;
                s.warnings.remove(call.name());
                s.emit("retrieval",found);result=Map.of("status",found.status(),"message",found.message(),"hits",found.hits());
            }else if(Set.of("weather_forecast","search_places","plan_route").contains(call.name())){
                String city=KnowledgeService.city(arguments.path("city").asText());
                if(!city.equals(KnowledgeService.city(s.confirmed.destination())))throw problem("INVALID_TOOL_ARGUMENT","工具城市必须与权威目的地一致。");
                if(call.name().equals("weather_forecast")){
                    LocalDate date;try{date=LocalDate.parse(arguments.path("date").asText());}catch(Exception e){throw problem("INVALID_TOOL_ARGUMENT","天气日期必须为YYYY-MM-DD。");}
                    if(date.isBefore(s.confirmed.startDate())||date.isAfter(s.confirmed.endDate()))throw problem("INVALID_TOOL_ARGUMENT","天气查询必须针对本次旅行日期，不能用今天代替未来旅行天气。");
                }
                if(call.name().equals("plan_route"))for(String key:List.of("originPlaceId","destinationPlaceId"))if(!s.placeIds.contains(arguments.path(key).asText()))throw problem("INVALID_TOOL_ARGUMENT","路线必须使用本次地点搜索返回的ID。");
                s.attempts.add(call.name());s.prepared=null;
                var value=gateway.call(call.name(),display);result=value;
                if(!value.path("status").asText().equals("AVAILABLE"))s.warnings.put(call.name(),value.path("message").asText());else s.warnings.remove(call.name());
                if(call.name().equals("plan_route")){if(value.path("data").path("hasFerry").asBoolean())s.warnings.put("ferry","已查道路路线包含渡轮，需另行确认运营与班次；预计耗时不含实时等待。");else s.warnings.remove("ferry");}
                if(call.name().equals("search_places")&&value.path("status").asText().equals("AVAILABLE"))for(var place:value.path("data").path("places"))s.placeIds.add(place.path("id").asText());
            }else if(call.name().equals("calculate_budget")){
                if(!s.attempts.containsAll(Set.of("search_knowledge","weather_forecast","search_places")))throw problem("MISSING_TOOLS","先查询知识库、天气和地点；失败状态也必须如实记录。");
                if(s.placeIds.size()>=2&&!s.attempts.contains("plan_route"))throw problem("MISSING_TOOLS","已有多个地点，请先查询一次路线并读取结果。");
                var plan=parser.parse(call.arguments());validator.check(s.confirmed,plan,"v4");
                plan=AdjustmentRules.apply(s.previous,plan,Objects.toString(s.request.instruction(),""));plan=grounding.bind(plan,s.retrieval(),s.previous);
                var notes=new LinkedHashSet<>(s.warnings.values());notes.addAll(plan.pendingConfirmations());
                plan=new Itinerary(plan.title(),plan.summary(),plan.days(),plan.extraCosts(),plan.sources(),notes.stream().limit(20).map(n->n.length()>300?n.substring(0,300):n).toList());
                var checked=validator.check(s.confirmed,plan,"v4");s.prepared=checked;s.preparedId=UUID.randomUUID().toString();s.attempts.add(call.name());
                result=Map.of("status","VALID","preparedPlanId",s.preparedId,"budget",checked.budget(),"schedule",checked.schedule(),"itinerary",checked.itinerary());
            }else throw problem("INVALID_TOOL","模型请求了未注册的工具。");
        }catch(Exception error){var p=safe(error);if(Set.of("MCP_UNAVAILABLE","KNOWLEDGE_UNAVAILABLE").contains(p.code()))s.warnings.put(call.name(),p.getMessage());result=Map.of("status","ERROR","code",p.code(),"message",p.getMessage());}
        s.check();String serialized=encode(result);s.emit("tool_result",Map.of("callId",call.id(),"name",call.name(),"result",result,"durationMs",elapsed(start)));return serialized;
    }
    private String toolError(Session s,AssistantMessage.ToolCall call,Map<String,Object> arguments,String code,String message,long start){
        s.emit("tool_start",Map.of("callId",call.id(),"name",call.name(),"arguments",arguments,"trigger","模型选择"));var result=Map.of("status","ERROR","code",code,"message",message);
        s.emit("tool_result",Map.of("callId",call.id(),"name",call.name(),"result",result,"durationMs",elapsed(start)));return encode(result);
    }
    private final class Session {
        final Request request;final TravelRequest confirmed;final Itinerary previous;final ConversationGate.Lease lease;final FluxSink<Event> sink;
        final long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(settings.timeoutSeconds());
        final Map<String,Citation> hits=new LinkedHashMap<>();final Set<String> placeIds=new HashSet<>(),attempts=new HashSet<>();final Map<String,String> warnings=new LinkedHashMap<>();
        volatile Future<?> work;volatile ScheduledFuture<?> deadline;volatile Thread thread;boolean terminal;long sequence;int calls;TripDraft prepared;String preparedId;
        Session(Request request,TravelRequest confirmed,Itinerary previous,ConversationGate.Lease lease,FluxSink<Event> sink){this.request=request;this.confirmed=confirmed;this.previous=previous;this.lease=lease;this.sink=sink;}
        synchronized void check(){if(terminal||Thread.currentThread().isInterrupted())throw problem("CANCELLED","执行已停止。");if(System.nanoTime()>=end)throw problem("AGENT_TIMEOUT","已达到执行时间上限，原有效行程保留。");}
        SearchResult retrieval(){return new SearchResult(confirmed.destination(),"本次智能体检索",hits.isEmpty()?"NO_MATCH":"MATCHED","仅包含本次执行实际命中的资料",List.copyOf(hits.values()),0,travel.embeddingModel(),0);}
        synchronized void emit(String type,Object data){if(!terminal)publish(type,data);}
        private void publish(String type,Object data){var event=new Event(++sequence,type,request.executionId(),data,Instant.now());executions.append(event);sink.next(event);}
        synchronized void stop(String state,String code,String message){
            if(terminal)return;terminal=true;
            try{executions.finish(request.executionId(),state,code,message);publish("error",Map.of("status",state,"code",code,"message",message));sink.complete();}
            finally{cleanup();if(work!=null&&Thread.currentThread()!=thread)work.cancel(true);}
        }
        synchronized void commit(){
            check();
            var conversation=transaction.execute(tx->{
                repository.updateDraft(request.conversationId(),request.expectedVersion(),new TripDraft.Content(confirmed,prepared.itinerary(),"v4",request.executionId()),
                        request.request()==null?Objects.toString(request.instruction(),"智能调整"):confirmed.origin()+"出发，"+confirmed.people()+"人去"+confirmed.destination()+"（智能体规划）",
                        "智能规划已完成："+prepared.itinerary().title()+"。"+prepared.itinerary().summary());
                memory.add(request.conversationId(),List.of(new UserMessage("旅行需求："+encode(confirmed)+"；"+Objects.toString(request.instruction(),"生成行程")),new AssistantMessage(prepared.itinerary().summary())));
                executions.finish(request.executionId(),"COMPLETED",null,"行程已通过校验并更新草稿。");return trips.conversation(request.conversationId());
            });
            terminal=true;try{publish("itinerary",conversation);publish("done",Map.of("status","COMPLETED","message","行程已通过校验，记得保存。","toolCalls",calls));sink.complete();}finally{cleanup();}
        }
        private void cleanup(){active.remove(request.executionId(),this);if(deadline!=null)deadline.cancel(false);lease.close();}
    }
    private static ServiceProblem problem(String code,String message){return new ServiceProblem(code,message);}
    private static ServiceProblem safe(Exception error){return error instanceof ServiceProblem p?p:ProblemMessages.describe(error);}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("执行数据格式不正确",e);}}
    private static long elapsed(long start){return (System.nanoTime()-start)/1_000_000;}
    @PreDestroy void close(){for(var s:List.copyOf(active.values()))s.stop("FAILED","INTERRUPTED","服务停止，原有效行程保留。");workers.shutdownNow();timer.shutdownNow();}
}
