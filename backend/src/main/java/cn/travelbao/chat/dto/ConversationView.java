package cn.travelbao.chat.dto;

import cn.travelbao.trip.dto.TripDraft;
import java.util.List;
import cn.travelbao.knowledge.dto.KnowledgeModels.Citation;

public record ConversationView(String id, String title, int version, TripDraft draft,
                               String savedTripId, Integer savedVersion, List<MessageView> messages) {
    public record MessageView(long id, String role, String text, String createdAt, List<Citation> citations) {}
}
