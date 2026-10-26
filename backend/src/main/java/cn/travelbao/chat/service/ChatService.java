package cn.travelbao.chat.service;

import cn.travelbao.chat.dto.ChatEvent;
import cn.travelbao.chat.dto.ChatRequest;
import cn.travelbao.ai.prompt.TravelPrompts;
import cn.travelbao.trip.repository.TravelRepository;
import cn.travelbao.common.exception.ProblemMessages;
import cn.travelbao.common.exception.ServiceProblem;
import cn.travelbao.config.TravelSettings;
import java.time.Duration;
import java.util.UUID;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class ChatService {
    private final ChatClient client;
    private final TravelSettings settings;
    private final TravelRepository repository;
    private final TravelPrompts prompts;
    private final ConversationGate gate;

    public ChatService(ChatClient client, TravelSettings settings, TravelRepository repository,
                       TravelPrompts prompts, ConversationGate gate) {
        this.client = client;
        this.settings = settings;
        this.repository = repository;
        this.prompts = prompts;
        this.gate = gate;
    }

    public Flux<ChatEvent> stream(String message) {
        return stream(new ChatRequest(message, null));
    }

    public Flux<ChatEvent> stream(ChatRequest request) {
        return Flux.defer(() -> {
            String id = UUID.randomUUID().toString();
            if (!settings.modelConfigured()) {
                return Flux.just(ChatEvent.error(id, "请先在本机 .env 中配置 DeepSeek 密钥并重启后端。", "MODEL_NOT_CONFIGURED"));
            }
            String conversation = request.conversationId() == null ? repository.create() : request.conversationId();
            return Flux.using(() -> gate.acquire(conversation), lease -> {
            var row = repository.get(conversation);
            String system = prompts.system("qa");
            if (row.content() != null) system += "\n当前行程上下文：" + prompts.encode(row.content());
            repository.addMessage(conversation, "user", request.message());
            var complete = new StringBuilder();
            Flux<ChatEvent> answer = client.prompt().system(system).user(request.message())
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversation)).stream().content()
                    .filter(text -> text != null && !text.isEmpty())
                    .doOnNext(complete::append)
                    .map(text -> ChatEvent.of("delta", id, text))
                    .switchIfEmpty(Flux.error(new ServiceProblem("EMPTY_MODEL_REPLY", "模型没有返回有效内容，请重试。")))
                    .doOnComplete(() -> repository.addMessage(conversation, "assistant", complete.toString()));
            return Flux.concat(
                    Flux.just(ChatEvent.of("conversation", id, conversation)),
                    Flux.just(ChatEvent.of("status", id, "正在整理你的旅行想法…")),
                    answer,
                    Mono.fromSupplier(() -> ChatEvent.of("done", id, "回复完成")))
                    .timeout(Duration.ofSeconds(settings.requestTimeoutSeconds()))
                    .onErrorResume(error -> {
                        var problem = ProblemMessages.describe(error);
                        return Flux.just(ChatEvent.error(id, problem.getMessage(), problem.code()));
                    });
            }, ConversationGate.Lease::close);
        }).onErrorResume(error -> {
            var problem = error instanceof ServiceProblem sp ? sp : ProblemMessages.describe(error);
            return Flux.just(ChatEvent.error(UUID.randomUUID().toString(), problem.getMessage(), problem.code()));
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
