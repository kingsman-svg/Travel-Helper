package cn.travelbao.knowledge.service;

import cn.travelbao.ai.rag.*;
import cn.travelbao.common.exception.ServiceProblem;
import cn.travelbao.config.*;
import cn.travelbao.knowledge.dto.KnowledgeModels.*;
import cn.travelbao.knowledge.repository.KnowledgeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeService {
    private final KnowledgeRepository repository;
    private final KnowledgeEmbedder embedder;
    private final KnowledgeChunker chunker;
    private final KnowledgeSettings settings;
    private final TravelSettings travel;
    private final ObjectMapper json;
    private final AtomicBoolean busy = new AtomicBoolean();
    public KnowledgeService(KnowledgeRepository repository, KnowledgeEmbedder embedder, KnowledgeChunker chunker,
                            KnowledgeSettings settings, TravelSettings travel, ObjectMapper json) {
        this.repository=repository; this.embedder=embedder; this.chunker=chunker; this.settings=settings; this.travel=travel; this.json=json;
    }
    @EventListener(ApplicationReadyEvent.class)
    void recoverInterruptedImports() {
        for (var doc : repository.list()) if (doc.status().equals("INDEXING")) repository.failed(doc.id(),"上次导入未完成，请重新索引。");
    }
    public List<DocumentView> list() { return repository.list(); }
    public DocumentDetail document(String id) {
        var doc=repository.get(id); return new DocumentDetail(doc.view(),doc.content(),repository.chunkViews(id));
    }
    public IndexState state() {
        var metadata=repository.metadata();
        String status="EMPTY", message="还没有可检索资料，请导入资料。";
        if (metadata.isPresent()) {
            var indexed=metadata.get();
            status="READY"; message="本地文档与向量索引已保存。";
            try { compatible(indexed,embedder.identity()); }
            catch (ServiceProblem e) { status=e.code().equals("INDEX_REBUILD_REQUIRED") ? "REBUILD_REQUIRED" : "UNAVAILABLE"; message=e.getMessage(); }
        }
        return new IndexState(status,message,travel.embeddingModel(),metadata.map(KnowledgeRepository.IndexMetadata::model).orElse(null),
                metadata.map(KnowledgeRepository.IndexMetadata::digest).orElse(null),metadata.map(KnowledgeRepository.IndexMetadata::dimensions).orElse(null),
                repository.list().size(),repository.chunkCount(),metadata.map(KnowledgeRepository.IndexMetadata::generation).orElse(null),
                metadata.map(KnowledgeRepository.IndexMetadata::updatedAt).orElse(null),busy.get());
    }
    public ImportResult upload(String city,String title,String sourceUrl,String verifiedAt,String filename,byte[] bytes) {
        return importDocument(null,city,title,sourceUrl,verifiedAt,filename,bytes,"USER_UPLOAD");
    }
    private ImportResult importDocument(String preferredId,String city,String title,String sourceUrl,String verifiedAt,String filename,byte[] bytes,String origin) {
        if (!busy.compareAndSet(false,true)) throw problem("KNOWLEDGE_BUSY","资料正在导入或重建，请稍后再试。");
        String id=null;
        try {
            city=city(city); title=bounded(title,150,"资料标题"); sourceUrl=url(sourceUrl);
            filename=bounded(filename,200,"文件名");
            if (!(filename.toLowerCase(Locale.ROOT).endsWith(".md") || filename.toLowerCase(Locale.ROOT).endsWith(".txt"))) throw invalid("仅支持 UTF-8 的 .md 或 .txt 文件。");
            LocalDate date;
            try { date=LocalDate.parse(verifiedAt); } catch(Exception e) { throw invalid("请填写正确的资料核对日期。"); }
            if (date.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")))) throw invalid("核对日期不能晚于今天。");
            String content=decode(bytes), hash=hash(content);
            if (chunker.split(content).isEmpty()) throw invalid("资料只有元数据，没有可检索的正文。");
            var duplicate=repository.duplicate(city,hash);
            if (duplicate.isPresent()) {
                id=duplicate.get().id();
                if (duplicate.get().status().equals("READY")) {
                    var meta=repository.metadata().orElseThrow(()->rebuildRequired("索引元数据缺失")); compatible(meta,embedder.identity());
                    return new ImportResult(duplicate.get(),true);
                }
            }
            var identity=embedder.identity();
            repository.metadata().ifPresent(meta->compatible(meta,identity));
            if (id==null) {
                if (repository.list().size() >= settings.maxDocuments()) throw invalid("本机知识库已达到资料数量上限，请精简资料后再导入。");
                id=preferredId==null ? UUID.randomUUID().toString() : preferredId;
                if (preferredId!=null && repository.list().stream().anyMatch(d->d.id().equals(preferredId)))
                    throw invalid("示例资料内容已有变更，请以新文件导入，避免覆盖原始来源。");
                repository.create(id,city,title,sourceUrl,date.toString(),filename,content,hash,origin);
            }
            index(id);
            return new ImportResult(repository.get(id).view(),false);
        } catch (ServiceProblem e) {
            if (id!=null && !repository.get(id).view().status().equals("READY")) repository.failed(id,e.getMessage());
            throw e;
        } finally { busy.set(false); }
    }
    public ImportResult retry(String id) {
        if (!busy.compareAndSet(false,true)) throw problem("KNOWLEDGE_BUSY","资料正在导入或重建，请稍后再试。");
        try {
            repository.get(id); repository.metadata().ifPresent(meta->compatible(meta,embedder.identity()));
            index(id); return new ImportResult(repository.get(id).view(),false);
        } catch (ServiceProblem e) { if (!repository.get(id).view().status().equals("READY")) repository.failed(id,e.getMessage()); throw e; }
        finally { busy.set(false); }
    }
    private void index(String id) {
        var doc=repository.get(id); var pieces=chunker.split(doc.content());
        var embeddings=embed(pieces.stream().map(text->embeddingText(doc.view(),text)).toList());
        repository.metadata().ifPresent(meta->{ compatible(meta,embeddings.identity()); if (meta.dimensions()!=embeddings.dimensions()) throw rebuildRequired("向量维度已改变"); });
        repository.publish(id,pieces,embeddings);
    }
    public List<ImportResult> importExamples() {
        try {
            Path root=Path.of(settings.examplesPath()).toAbsolutePath().normalize();
            CatalogItem[] catalog=json.readValue(root.resolve("catalog.json").toFile(),CatalogItem[].class);
            var results=new ArrayList<ImportResult>();
            for (var item : catalog) {
                Path file=root.resolve(item.file()).normalize();
                if (!file.startsWith(root) || !Files.isRegularFile(file)) throw invalid("示例资料文件不存在。");
                results.add(importDocument(item.id(),item.city(),item.title(),item.sourceUrl(),item.verifiedAt(),file.getFileName().toString(),Files.readAllBytes(file),"CURATED_EXAMPLE"));
            }
            return List.copyOf(results);
        } catch (ServiceProblem e) { throw e; }
        catch (Exception e) { throw problem("KNOWLEDGE_IMPORT_FAILED","示例资料目录不可读取，请检查 knowledge-data 配置。"); }
    }
    public IndexState rebuild() {
        if (!busy.compareAndSet(false,true)) throw problem("KNOWLEDGE_BUSY","资料正在导入或重建，请稍后再试。");
        try {
            var docs=repository.documents();
            if (docs.isEmpty()) throw invalid("没有可以重建的资料。");
            var chunks=new LinkedHashMap<String,List<String>>(); var inputs=new ArrayList<String>();
            for (var doc : docs) {
                var pieces=chunker.split(doc.content()); chunks.put(doc.view().id(),pieces);
                for (var piece : pieces) inputs.add(embeddingText(doc.view(),piece));
            }
            repository.rebuild(chunks,embed(inputs));
        } finally { busy.set(false); }
        return state();
    }
    private KnowledgeEmbedder.Embeddings embed(List<String> texts) {
        var vectors=new ArrayList<float[]>(); KnowledgeEmbedder.ModelIdentity identity=null; int dimensions=0;
        for (int from=0; from<texts.size(); from+=16) {
            var batch=embedder.embed(texts.subList(from,Math.min(from+16,texts.size())));
            if (identity!=null && (!identity.equals(batch.identity()) || dimensions!=batch.dimensions())) throw rebuildRequired("处理期间模型或维度发生变化");
            identity=batch.identity(); dimensions=batch.dimensions(); vectors.addAll(batch.vectors());
        }
        return new KnowledgeEmbedder.Embeddings(identity,List.copyOf(vectors),dimensions);
    }
    public SearchResult search(String city,String query) {
        long start=System.nanoTime(); city=city(city); query=bounded(query,2000,"问题");
        var metadata=repository.metadata();
        if (metadata.isEmpty()) return empty(city,query,"EMPTY","知识库还没有完成索引的资料。",start);
        var chunks=repository.chunks(city);
        if (chunks.isEmpty()) return empty(city,query,"NO_MATCH","该城市尚无可用资料，请先导入或切换城市。",start);
        var meta=metadata.get(); compatible(meta,embedder.identity());
        var embedded=embedder.embed(List.of(query)); compatible(meta,embedded.identity());
        if (embedded.dimensions()!=meta.dimensions()) throw rebuildRequired("查询向量维度与已保存索引不同");
        var hits=new ArrayList<Citation>(); float[] vector=embedded.vectors().get(0);
        for (var chunk : chunks) {
            if (chunk.vector().length!=meta.dimensions()) throw rebuildRequired("本地索引向量维度不一致");
            double score=cosine(vector,chunk.vector());
            if (score >= settings.minScore()) {
                var doc=chunk.document();
                hits.add(new Citation(doc.id(),chunk.id(),doc.title(),doc.city(),doc.sourceUrl(),doc.verifiedAt(),doc.origin(),chunk.content(),score));
            }
        }
        hits.sort(Comparator.comparingDouble(Citation::score).reversed().thenComparing(Citation::chunkId));
        var selected=hits.stream().limit(settings.topK()).toList();
        return new SearchResult(city,query,selected.isEmpty()?"NO_MATCH":"MATCHED",selected.isEmpty()?"没有达到相关性阈值的资料片段。":"已按城市检索本机资料；并非实时查询。",
                selected,elapsed(start),meta.model(),meta.dimensions());
    }
    private SearchResult empty(String city,String query,String status,String message,long start) { return new SearchResult(city,query,status,message,List.of(),elapsed(start),travel.embeddingModel(),0); }
    private void compatible(KnowledgeRepository.IndexMetadata meta,KnowledgeEmbedder.ModelIdentity identity) {
        if (!meta.model().equals(identity.model()) || !meta.digest().equals(identity.digest())) throw rebuildRequired("Embedding 模型名称或权重已变化");
    }
    private String decode(byte[] bytes) {
        if (bytes==null || bytes.length==0 || bytes.length>settings.maxUploadBytes()) throw invalid("文件须为非空文本，大小不超过256KB。");
        try {
            String value=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString().replaceFirst("^\\uFEFF","").replace("\r\n","\n").replace('\r','\n').strip();
            if (value.length()<20 || value.length()>40000 || value.codePoints().anyMatch(c->Character.isISOControl(c) && c!='\n' && c!='\t')) throw invalid("资料须为20–40000字的 UTF-8 文本，不能包含二进制控制字符。");
            return value;
        } catch (CharacterCodingException e) { throw invalid("文件不是有效 UTF-8 编码，请转换编码后重试。"); }
    }
    private static String embeddingText(DocumentView doc,String text) { return doc.city()+" · "+doc.title()+"\n"+text; }
    private static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }
    public static String city(String value) { String city=bounded(value,60,"城市"); return city.endsWith("市")?city.substring(0,city.length()-1):city; }
    private static String bounded(String value,int length,String label) { if (value==null || value.isBlank() || value.strip().length()>length) throw invalid(label+"不能为空且长度不能超过"+length+"字。"); return value.strip(); }
    private static String url(String value) {
        value=bounded(value,500,"来源链接");
        try { var uri=URI.create(value); if (!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null) throw invalid("来源必须是有效的 HTTP/HTTPS 链接。"); }
        catch(IllegalArgumentException e) { throw invalid("来源链接格式不正确。"); }
        return value;
    }
    private static double cosine(float[] left,float[] right) {
        double dot=0,a=0,b=0;
        for(int i=0;i<left.length;i++) { dot+=(double)left[i]*right[i]; a+=(double)left[i]*left[i]; b+=(double)right[i]*right[i]; }
        if (!Double.isFinite(dot) || a<=0 || b<=0) throw rebuildRequired("本地索引向量无效");
        return Math.max(-1,Math.min(1,dot/Math.sqrt(a*b)));
    }
    private static long elapsed(long start) { return (System.nanoTime()-start)/1_000_000; }
    private static ServiceProblem invalid(String message) { return problem("INVALID_KNOWLEDGE",message); }
    private static ServiceProblem rebuildRequired(String message) { return problem("INDEX_REBUILD_REQUIRED",message+"，请重建知识索引后再检索。"); }
    private static ServiceProblem problem(String code,String message) { return new ServiceProblem(code,message); }
}
