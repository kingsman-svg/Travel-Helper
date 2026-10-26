package cn.travelbao.knowledge.repository;

import cn.travelbao.ai.rag.KnowledgeEmbedder;
import cn.travelbao.knowledge.dto.KnowledgeModels.*;
import cn.travelbao.common.exception.ServiceProblem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class KnowledgeRepository {
    private final JdbcTemplate jdbc; private final ObjectMapper json;
    public record StoredDocument(DocumentView view, String content) {}
    public record StoredChunk(String id, DocumentView document, int ordinal, String content, float[] vector) {}
    public record IndexMetadata(String model, String digest, int dimensions, String generation, String updatedAt) {}
    public KnowledgeRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    public List<DocumentView> list() { return jdbc.query("SELECT * FROM knowledge_documents ORDER BY created_at DESC",(rs,n)->view(rs)); }
    public Optional<DocumentView> duplicate(String city,String hash) {
        return jdbc.query("SELECT * FROM knowledge_documents WHERE city=? AND content_hash=?",(rs,n)->view(rs),city,hash).stream().findFirst();
    }
    public Optional<IndexMetadata> metadata() {
        return jdbc.query("SELECT * FROM knowledge_index_state WHERE singleton=1",(rs,n)->new IndexMetadata(rs.getString("model"),rs.getString("model_digest"),
                rs.getInt("dimensions"),rs.getString("generation"),rs.getString("updated_at"))).stream().findFirst();
    }
    public StoredDocument get(String id) {
        return jdbc.query("SELECT * FROM knowledge_documents WHERE id=?",(rs,n)->new StoredDocument(view(rs),rs.getString("content")),id)
                .stream().findFirst().orElseThrow(()->new ServiceProblem("NOT_FOUND","资料不存在。"));
    }
    public List<StoredDocument> documents() { return jdbc.query("SELECT * FROM knowledge_documents ORDER BY id",(rs,n)->new StoredDocument(view(rs),rs.getString("content"))); }
    public void create(String id,String city,String title,String url,String verified,String filename,String content,String hash,String origin) {
        jdbc.update("INSERT INTO knowledge_documents(id,city,title,source_url,verified_at,filename,content,content_hash,origin,status,created_at) VALUES(?,?,?,?,?,?,?,?,?,'INDEXING',?)",
                id,city,title,url,verified,filename,content,hash,origin,Instant.now().toString());
    }
    public void failed(String id,String message) { jdbc.update("UPDATE knowledge_documents SET status='FAILED',error_message=? WHERE id=?",message,id); }
    @Transactional
    public void publish(String id,List<String> chunks,KnowledgeEmbedder.Embeddings embeddings) {
        replaceChunks(id,chunks,embeddings.vectors()); setMetadata(embeddings);
    }
    @Transactional
    public void rebuild(Map<String,List<String>> chunks,KnowledgeEmbedder.Embeddings embeddings) {
        int position=0;
        for (var item : chunks.entrySet()) {
            int end=position+item.getValue().size();
            replaceChunks(item.getKey(),item.getValue(),embeddings.vectors().subList(position,end)); position=end;
        }
        setMetadata(embeddings);
    }
    private void replaceChunks(String id,List<String> chunks,List<float[]> vectors) {
        jdbc.update("DELETE FROM knowledge_chunks WHERE document_id=?",id);
        for (int i=0; i<chunks.size(); i++) jdbc.update("INSERT INTO knowledge_chunks(id,document_id,ordinal,content,embedding) VALUES(?,?,?,?,?)",
                id+"-"+i,id,i,chunks.get(i),encode(vectors.get(i)));
        jdbc.update("UPDATE knowledge_documents SET status='READY',error_message=NULL,chunk_count=?,indexed_at=? WHERE id=?",chunks.size(),Instant.now().toString(),id);
    }
    private void setMetadata(KnowledgeEmbedder.Embeddings embeddings) {
        jdbc.update("MERGE INTO knowledge_index_state(singleton,model,model_digest,dimensions,generation,updated_at) KEY(singleton) VALUES(1,?,?,?,?,?)",
                embeddings.identity().model(),embeddings.identity().digest(),embeddings.dimensions(),UUID.randomUUID().toString(),Instant.now().toString());
    }
    public List<StoredChunk> chunks(String city) {
        return jdbc.query("SELECT c.id AS chunk_id,c.ordinal,c.content AS chunk_content,c.embedding,d.* FROM knowledge_chunks c JOIN knowledge_documents d ON d.id=c.document_id WHERE d.status='READY' AND d.city=? ORDER BY c.id",
                (rs,n)->new StoredChunk(rs.getString("chunk_id"),view(rs),rs.getInt("ordinal"),rs.getString("chunk_content"),decode(rs.getString("embedding"))),city);
    }
    public List<ChunkView> chunkViews(String id) {
        return jdbc.query("SELECT id,ordinal,content FROM knowledge_chunks WHERE document_id=? ORDER BY ordinal",(rs,n)->new ChunkView(rs.getString("id"),rs.getInt("ordinal"),rs.getString("content")),id);
    }
    public int chunkCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunks",Integer.class); }
    private DocumentView view(ResultSet rs) throws SQLException {
        return new DocumentView(rs.getString("id"),rs.getString("city"),rs.getString("title"),rs.getString("source_url"),rs.getString("verified_at"),
                rs.getString("filename"),rs.getString("origin"),rs.getString("status"),rs.getString("error_message"),rs.getInt("chunk_count"),rs.getString("created_at"),rs.getString("indexed_at"),rs.getString("content_hash"));
    }
    private String encode(float[] vector) { try { return json.writeValueAsString(vector); } catch(JsonProcessingException e) { throw new IllegalStateException(e); } }
    private float[] decode(String vector) { try { return json.readValue(vector,float[].class); } catch(JsonProcessingException e) { throw new IllegalStateException("本地向量索引无法读取，请重建索引",e); } }
}
