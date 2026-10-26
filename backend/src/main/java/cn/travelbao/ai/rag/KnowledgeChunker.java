package cn.travelbao.ai.rag;

import cn.travelbao.config.KnowledgeSettings;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeChunker {
    private final int size, overlap;
    public KnowledgeChunker(KnowledgeSettings settings) {
        size = settings.chunkSize(); overlap = settings.chunkOverlap();
        if (size < 100 || overlap < 0 || overlap >= size) throw new IllegalArgumentException("分块配置不正确");
    }
    public List<String> split(String content) {
        // Upload metadata is authoritative; YAML front matter remains visible in the original document only.
        if (content.startsWith("---\n")) {
            int boundary=content.indexOf("\n---",4);
            if (boundary>=0) content=content.substring(boundary+4).strip();
        }
        var result = new ArrayList<String>(); int start = 0;
        while (start < content.length()) {
            int end = Math.min(start+size,content.length());
            if (end < content.length()) {
                int boundary = content.lastIndexOf('\n',end);
                if (boundary > start+size/2) end = boundary+1;
                if (Character.isHighSurrogate(content.charAt(end-1))) end--;
            }
            String chunk = content.substring(start,end).strip();
            if (!chunk.isBlank()) result.add(chunk);
            if (end == content.length()) break;
            start = Math.max(start+1,end-overlap);
            if (Character.isLowSurrogate(content.charAt(start))) start++;
        }
        return List.copyOf(result);
    }
}
