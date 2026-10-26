package cn.travelbao.ai.agent;
import cn.travelbao.ai.agent.AgentModels.*;
import cn.travelbao.common.exception.ServiceProblem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
@Repository
public class AgentRepository {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public AgentRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    public boolean exists(String id){return jdbc.queryForObject("SELECT COUNT(*) FROM agent_runs WHERE id=?",Integer.class,id)>0;}
    public void create(String id,String conversation){jdbc.update("INSERT INTO agent_runs(id,conversation_id,status,started_at) VALUES(?,?,'RUNNING',?)",id,conversation,Instant.now().toString());}
    public void append(Event event){try{jdbc.update("INSERT INTO agent_events(execution_id,seq,event_json) VALUES(?,?,?)",event.executionId(),event.sequence(),json.writeValueAsString(event));}catch(Exception e){throw new IllegalStateException("执行记录未能保存",e);}}
    public void finish(String id,String status,String code,String message){jdbc.update("UPDATE agent_runs SET status=?,code=?,message=?,finished_at=? WHERE id=? AND status='RUNNING'",status,code,message,Instant.now().toString(),id);}
    public void recover(){jdbc.update("UPDATE agent_runs SET status='FAILED',code='INTERRUPTED',message='上次执行因服务停止而中断，原有效行程保留。',finished_at=? WHERE status='RUNNING'",Instant.now().toString());}
    public Run get(String id){
        return jdbc.query("SELECT * FROM agent_runs WHERE id=?",(r,n)->new Run(id,r.getString("conversation_id"),r.getString("status"),r.getString("code"),r.getString("message"),r.getString("started_at"),r.getString("finished_at"),events(id)),id)
                .stream().findFirst().orElseThrow(()->new ServiceProblem("NOT_FOUND","执行记录不存在。"));
    }
    public Optional<Run> latest(String conversation){return jdbc.query("SELECT id FROM agent_runs WHERE conversation_id=? ORDER BY started_at DESC LIMIT 1",(r,n)->r.getString(1),conversation).stream().findFirst().map(this::get);}
    private List<Event> events(String id){return jdbc.query("SELECT event_json FROM agent_events WHERE execution_id=? ORDER BY seq",(r,n)->{try{return json.readValue(r.getString(1),Event.class);}catch(Exception e){throw new IllegalStateException("执行记录无法读取",e);}},id);}
}
