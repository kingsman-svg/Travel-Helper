package cn.travelbao.trip.repository;

import cn.travelbao.chat.dto.ConversationView;
import cn.travelbao.common.exception.ServiceProblem;
import cn.travelbao.trip.dto.TripDraft;
import cn.travelbao.knowledge.dto.KnowledgeModels.Citation;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class TravelRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public record ConversationRow(String id, String title, int version, TripDraft.Content content) {}
    public record SavedTrip(String id, String conversationId, int version, TripDraft.Content content, String savedAt) {}
    public TravelRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    public String create() {
        String id = UUID.randomUUID().toString(), now = Instant.now().toString();
        jdbc.update("INSERT INTO conversations(id,title,created_at,updated_at) VALUES(?,?,?,?)", id, "新的旅行", now, now);
        return id;
    }
    public ConversationRow get(String id) {
        return jdbc.query("SELECT * FROM conversations WHERE id=?", (rs,n) -> new ConversationRow(rs.getString("id"),
                rs.getString("title"), rs.getInt("version"), decode(rs.getString("draft_json"))), id)
                .stream().findFirst().orElseThrow(() -> new ServiceProblem("NOT_FOUND", "会话不存在，请开始新的对话。"));
    }
    public List<ConversationView.MessageView> messages(String id) {
        return jdbc.query("SELECT * FROM conversation_messages WHERE conversation_id=? ORDER BY seq", (rs,n) ->
                new ConversationView.MessageView(rs.getLong("seq"), rs.getString("role"), rs.getString("content"), rs.getString("created_at"), citations(rs.getString("citations_json"))), id);
    }
    public void addMessage(String id, String role, String content) {
        jdbc.update("INSERT INTO conversation_messages(conversation_id,role,content,created_at) VALUES(?,?,?,?)", id, role, content, Instant.now().toString());
    }
    @Transactional
    public void addExchange(String id,String question,String answer,List<Citation> citations) {
        addMessage(id,"user",question);
        try {
            jdbc.update("INSERT INTO conversation_messages(conversation_id,role,content,created_at,citations_json) VALUES(?,?,?,?,?)",
                    id,"assistant",answer,Instant.now().toString(),json.writeValueAsString(citations));
        } catch(JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    private List<Citation> citations(String value) {
        if (value==null) return List.of();
        try { return json.readValue(value,new TypeReference<List<Citation>>(){}); }
        catch(JsonProcessingException e) { throw new IllegalStateException("本地引用记录无法读取",e); }
    }
    @Transactional
    public void updateDraft(String id, int expectedVersion, TripDraft.Content content, String userText, String assistantText) {
        int updated = jdbc.update("UPDATE conversations SET title=?,version=version+1,draft_json=?,updated_at=? WHERE id=? AND version=?",
                content.itinerary().title(), encode(content), Instant.now().toString(), id, expectedVersion);
        if (updated != 1) throw stale();
        addMessage(id, "user", userText);
        addMessage(id, "assistant", assistantText);
    }
    @Transactional
    public SavedTrip save(String conversationId, int version) {
        var row = get(conversationId);
        if (row.version() != version) throw stale();
        if (row.content() == null) throw new ServiceProblem("INVALID_REQUEST", "先生成一个有效行程，再保存。");
        var existing = savedFor(conversationId);
        String id = existing.map(SavedTrip::id).orElseGet(() -> UUID.randomUUID().toString());
        String now = Instant.now().toString();
        if (existing.isPresent()) jdbc.update("UPDATE trips SET source_version=?,draft_json=?,saved_at=? WHERE id=?",
                version, encode(row.content()), now, id);
        else jdbc.update("INSERT INTO trips(id,conversation_id,source_version,draft_json,saved_at) VALUES(?,?,?,?,?)",
                id, conversationId, version, encode(row.content()), now);
        return new SavedTrip(id, conversationId, version, row.content(), now);
    }
    public Optional<SavedTrip> savedFor(String id) {
        return jdbc.query("SELECT * FROM trips WHERE conversation_id=?", this::savedRow, id).stream().findFirst();
    }
    public SavedTrip trip(String id) {
        return jdbc.query("SELECT * FROM trips WHERE id=?", this::savedRow, id).stream().findFirst()
                .orElseThrow(() -> new ServiceProblem("NOT_FOUND", "行程不存在。"));
    }
    public List<SavedTrip> trips() { return jdbc.query("SELECT * FROM trips ORDER BY saved_at DESC", this::savedRow); }
    private SavedTrip savedRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new SavedTrip(rs.getString("id"), rs.getString("conversation_id"), rs.getInt("source_version"),
                decode(rs.getString("draft_json")), rs.getString("saved_at"));
    }
    private TripDraft.Content decode(String value) {
        if (value == null) return null;
        try { return json.readValue(value, TripDraft.Content.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("本地行程数据无法读取", e); }
    }
    private String encode(TripDraft.Content value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("本地行程数据无法存储", e); }
    }
    public static ServiceProblem stale() { return new ServiceProblem("VERSION_CONFLICT", "行程已有更新，请重新打开后再操作。"); }
}
