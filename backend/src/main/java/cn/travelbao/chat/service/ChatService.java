package cn.travelbao.chat.service;
import cn.travelbao.chat.dto.*;
import cn.travelbao.config.TravelSettings;
import cn.travelbao.common.exception.ProblemMessages;
import java.util.UUID;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
@Service public class ChatService {
 private final ChatClient client;private final TravelSettings settings;
 public ChatService(ChatClient client,TravelSettings settings){this.client=client;this.settings=settings;}
 public Flux<ChatEvent> stream(ChatRequest request){return Flux.defer(()->{
  String id=UUID.randomUUID().toString(),conversation=request.conversationId()==null?UUID.randomUUID().toString():request.conversationId();
  if(!settings.modelConfigured())return Flux.just(ChatEvent.error(id,"请配置本机DeepSeek密钥后重启。","MODEL_NOT_CONFIGURED"));
  var answer=client.prompt().system("你是旅游宝旅行助手。日期、票价和交通如未查询应说明需确认，不虚构已调用工具。").user(request.message()).advisors(a->a.param(ChatMemory.CONVERSATION_ID,conversation)).stream().content().map(text->ChatEvent.of("delta",id,text));
  return Flux.concat(Flux.just(ChatEvent.of("conversation",id,conversation)),answer,Flux.just(ChatEvent.of("done",id,"回复完成"))).onErrorResume(e->{var p=ProblemMessages.describe(e);return Flux.just(ChatEvent.error(id,p.getMessage(),p.code()));});
 }).subscribeOn(Schedulers.boundedElastic());}
}
