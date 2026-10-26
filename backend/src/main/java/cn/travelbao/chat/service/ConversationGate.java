package cn.travelbao.chat.service;

import cn.travelbao.common.exception.ServiceProblem;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** A single conversation has one writer; separate conversations may run independently. */
@Component
public class ConversationGate {
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    public Lease acquire(String id) {
        if (!active.add(id)) throw new ServiceProblem("CONVERSATION_BUSY", "本会话正在处理请求，请稍后再试。");
        var closed=new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {if(closed.compareAndSet(false,true))active.remove(id);};
    }
    public interface Lease extends AutoCloseable { @Override void close(); }
}
