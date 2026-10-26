package cn.travelbao.trip.service;

import cn.travelbao.ai.prompt.TravelPrompts;
import cn.travelbao.ai.prompt.StructuredPlanGenerator;
import cn.travelbao.chat.dto.ConversationView;
import cn.travelbao.chat.service.ConversationGate;
import cn.travelbao.common.exception.*;
import cn.travelbao.config.TravelSettings;
import cn.travelbao.trip.dto.*;
import cn.travelbao.trip.repository.TravelRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TripService {
    private final TravelRepository repository;
    private final ItineraryValidator validator;
    private final StructuredPlanGenerator generator;
    private final TravelSettings settings;
    private final ConversationGate gate;
    public TripService(TravelRepository repository, ItineraryValidator validator, StructuredPlanGenerator generator,
                       TravelSettings settings, ConversationGate gate) {
        this.repository = repository; this.validator = validator; this.generator = generator;
        this.settings = settings; this.gate = gate;
    }
    public ConversationView create() { return conversation(repository.create()); }
    public ConversationView conversation(String id) {
        var row = repository.get(id);
        var saved = repository.savedFor(id);
        return new ConversationView(id, row.title(), row.version(), row.content() == null ? null : checked(row.content()),
                saved.map(TravelRepository.SavedTrip::id).orElse(null), saved.map(TravelRepository.SavedTrip::version).orElse(null), repository.messages(id));
    }
    public ConversationView generate(String id, int version, TravelRequest request) {
        validator.request(request);
        return modelPlan(id, version, request, null, "生成旅行行程", "planning");
    }
    public ConversationView adjust(String id, int version, String message) {
        return modelPlan(id, version, null, null, message, "adjustment");
    }
    private ConversationView modelPlan(String id, int version, TravelRequest request, Itinerary previous, String message, String type) {
        try (var ignored = gate.acquire(id)) {
            var row = repository.get(id);
            if (row.version() != version) throw TravelRepository.stale();
            if (type.equals("adjustment")) {
                if (row.content() == null) throw new ServiceProblem("INVALID_REQUEST", "先生成行程，再提出调整要求。");
                request = row.content().request(); previous = row.content().itinerary();
            }
            if (!settings.modelConfigured()) throw new ServiceProblem("MODEL_NOT_CONFIGURED", "请先在本机配置 DeepSeek 密钥并重启后端。");
            Itinerary itinerary = generator.generate(id,type,request,previous,message);
            if (Thread.currentThread().isInterrupted()) throw new ServiceProblem("CANCELLED", "生成已中断，原行程已保留。");
            repository.updateDraft(id, version, new TripDraft.Content(request, itinerary,TravelPrompts.VERSION),
                    previous == null ? request.origin() + "出发，" + request.people() + "人去" + request.destination() + "，" + request.startDate() + "至" + request.endDate() : message,
                    (previous == null ? "已生成：" : "已调整：") + itinerary.title() + "。" + itinerary.summary());
            return conversation(id);
        }
    }
    public ConversationView edit(String id, int version, Itinerary itinerary) {
        try (var ignored = gate.acquire(id)) {
            var row = repository.get(id);
            if (row.content() == null) throw new ServiceProblem("INVALID_REQUEST", "尚无可编辑的行程。");
            if (!itinerary.sources().equals(row.content().itinerary().sources())) throw new ServiceProblem("INVALID_ITINERARY","资料引用由系统维护，手动编辑不能新增或修改来源。");
            validator.check(row.content().request(), itinerary, TravelPrompts.VERSION);
            repository.updateDraft(id, version, new TripDraft.Content(row.content().request(), itinerary,row.content().promptVersion(),row.content().agentExecutionId()), "手动编辑行程", "编辑已通过日期、费用与时间检查。");
            return conversation(id);
        }
    }
    public SavedTripView save(String id, int version) {
        try (var ignored = gate.acquire(id)) {
            var row = repository.get(id);
            if (row.content() == null) throw new ServiceProblem("INVALID_REQUEST", "请先生成行程。");
            checked(row.content());
            return view(repository.save(id, version));
        }
    }
    public List<SavedTripView> list() { return repository.trips().stream().map(this::view).toList(); }
    public SavedTripView get(String id) { return view(repository.trip(id)); }
    private TripDraft checked(TripDraft.Content content) {
        var d=validator.check(content.request(),content.itinerary(),content.promptVersion()==null?"v2":content.promptVersion());
        return new TripDraft(d.request(),d.itinerary(),d.budget(),d.schedule(),d.toolExecutions(),d.promptVersion(),content.agentExecutionId());
    }
    private SavedTripView view(TravelRepository.SavedTrip trip) {
        return new SavedTripView(trip.id(), trip.conversationId(), trip.version(), checked(trip.content()), trip.savedAt());
    }
    public record SavedTripView(String id, String conversationId, int version, TripDraft draft, String savedAt) {}
}
