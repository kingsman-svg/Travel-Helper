import assert from 'node:assert/strict';
import {readFileSync,writeFileSync,mkdirSync} from 'node:fs';
import {createHash,randomUUID} from 'node:crypto';
import path from 'node:path';
import {homedir} from 'node:os';
import {data,configuration,waitFor} from './runtime.mjs';
const env=configuration(),desktop=process.argv.includes('--desktop')?JSON.parse(readFileSync(path.join(process.env.TRAVELBAO_DESKTOP_DATA||path.join(homedir(),'Library/Application Support/TravelBao'),'desktop-runtime.json'),'utf8')):null;
const auth=desktop?{'X-TravelBao-Desktop':desktop.token}:{},base=`http://127.0.0.1:${desktop?.backendPort||env.BACKEND_PORT||8080}/api`,file=path.join(data,desktop?'verification/day4-desktop.json':'verification/day4.json');mkdirSync(path.dirname(file),{recursive:true});
const restarting=process.argv.includes('--after-restart');
const report=restarting?JSON.parse(readFileSync(file,'utf8')):{checkedAt:new Date().toISOString(),mode:'live',checks:[]};report.allPassed=false;report.phase=restarting?'restart-running':'running';
const flush=()=>writeFileSync(file,JSON.stringify(report,null,2));flush();
async function api(endpoint,method='GET',body,status=200){const response=await fetch(base+endpoint,{method,headers:{...auth,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(110000)});const value=await response.json();assert.equal(response.status,status,value.message||endpoint);return value;}
async function check(name,work){const start=Date.now();try{const detail=await work();report.checks.push({name,passed:true,durationMs:Date.now()-start,detail});console.log('通过：'+name);}catch(error){report.checks.push({name,passed:false,error:error.message});throw error;}finally{flush();}}
const digest=value=>createHash('sha256').update(JSON.stringify(value)).digest('hex');
const date=offset=>{const day=new Date();day.setDate(day.getDate()+offset);return day.toLocaleDateString('sv-SE');};
async function stream(body,cancel=false){
 const response=await fetch(base+'/agent/runs/stream',{method:'POST',headers:{...auth,'Content-Type':'application/json',Accept:'text/event-stream'},body:JSON.stringify(body),signal:AbortSignal.timeout(110000)});assert.equal(response.status,200);assert.ok(response.body);
 const reader=response.body.getReader(),decoder=new TextDecoder();let buffer='',cancelling;const events=[];
 function frame(text){const data=text.split('\n').filter(line=>line.startsWith('data:')).map(line=>line.slice(5).trimStart()).join('\n');if(!data)return;const event=JSON.parse(data);events.push(event);if(cancel&&event.type==='action'&&!cancelling)cancelling=api(`/agent/runs/${body.executionId}/cancel`,'POST');if(event.type==='tool_result')console.log(`工具：${event.data.name} → ${event.data.result.status}`);}
 try{while(true){const {value,done}=await reader.read();buffer+=decoder.decode(value,{stream:!done});buffer=buffer.replace(/\r\n/g,'\n');let boundary;while((boundary=buffer.indexOf('\n\n'))>=0){frame(buffer.slice(0,boundary));buffer=buffer.slice(boundary+2);}if(done)break;}if(buffer.trim())frame(buffer);if(cancelling)await cancelling;}finally{reader.releaseLock();}
 return events;
}
try{
 if(desktop)assert.equal((await fetch(base+'/health',{headers:auth})).status,200);else assert.ok(await waitFor(base+'/health'),'后端未就绪');
 if(restarting){
   await check('重启后完整执行记录与已保存行程恢复',async()=>{const run=await api('/agent/runs/'+report.executionId);assert.equal(run.status,'COMPLETED');assert.equal(digest(run.events),report.eventsDigest);const saved=await api('/trips/'+report.tripId);assert.equal(saved.draft.agentExecutionId,report.executionId);assert.equal(digest({request:saved.draft.request,itinerary:saved.draft.itinerary,agentExecutionId:saved.draft.agentExecutionId}),report.tripDigest);return {events:run.events.length,tripId:saved.id};});
   report.restartCheckedAt=new Date().toISOString();report.phase='complete';report.allPassed=report.checks.every(c=>c.passed);flush();
 }else{
   await check('MCP发现三个真实旅游工具',async()=>{const tools=await api('/tourism/tools');assert.deepEqual(tools.map(t=>t.name).sort(),['plan_route','search_places','weather_forecast']);return tools.map(t=>t.name);});
   let places;
   await check('公开天气预报返回日期、单位与来源',async()=>{const result=await api('/tourism/weather_forecast','POST',{city:'杭州',date:date(1)});report.weather=result;assert.equal(result.status,'AVAILABLE',result.message);assert.equal(result.data.date,date(1));assert.ok(result.data.units&&result.data.fetchedAt);return result;});
   await check('超预报范围与不支持城市明确返回状态',async()=>{const outside=await api('/tourism/weather_forecast','POST',{city:'杭州',date:date(30)});assert.equal(outside.status,'OUT_OF_RANGE');const city=await api('/tourism/search_places','POST',{city:'无资料测试城',keyword:'博物馆'});assert.equal(city.status,'UNSUPPORTED_CITY');return {outside:outside.status,city:city.status};});
   await check('真实地点与WGS84道路路线查询',async()=>{const found=await api('/tourism/search_places','POST',{city:'杭州',keyword:'中国茶叶博物馆'});assert.equal(found.status,'AVAILABLE');places=found.data.places.filter(p=>p.category==='tourism:museum');assert.ok(places.length>=2,'至少找到两个不同馆区的博物馆地点');const result=await api('/tourism/plan_route','POST',{city:'杭州',originPlaceId:places[0].id,destinationPlaceId:places[1].id,mode:'walking'});report.route=result;assert.equal(result.status,'AVAILABLE',result.message);assert.ok(result.data.distanceKm>0&&result.data.durationMinutes>0);assert.equal(result.data.coordinateSystem,'WGS84');const bad=await api('/tourism/plan_route','POST',{city:'杭州',originPlaceId:'invented',destinationPlaceId:places[1].id,mode:'walking'});assert.equal(bad.status,'INVALID_PLACE');return {places:places.map(p=>({id:p.id,name:p.name})),route:result};});
   const current=await api('/conversations','POST');report.conversationId=current.id;report.executionId=randomUUID();
   const request={origin:'上海',destination:'杭州',startDate:date(1),endDate:date(1),people:2,totalBudget:2000,budgetIncludes:['ACCOMMODATION','FOOD','TRANSPORT','TICKETS','OTHER'],preferences:'中国茶叶博物馆双峰馆区和龙井馆区一日游，根据已查天气安排参观；地点搜索先用简短名称“中国茶叶博物馆”，选择两个 museum 类地点查询路线。优先公交或打车，全天步行不超过60分钟。费用为两人总估计。'};
   await check('DeepSeek实际完成ReAct查询—预算校验—更新草稿',async()=>{
     const events=await stream({executionId:report.executionId,conversationId:current.id,expectedVersion:0,request});report.events=events;flush();
     const error=events.find(e=>e.type==='error');assert.ok(events.some(e=>e.type==='done'),error?.data.message||'没有成功结束事件');
     const results=events.filter(e=>e.type==='tool_result');const names=new Set(results.map(e=>e.data.name));for(const name of ['search_knowledge','weather_forecast','search_places','plan_route','calculate_budget'])assert.ok(names.has(name),`缺少实际工具调用 ${name}`);
     for(const name of ['weather_forecast','search_places','plan_route'])assert.ok(results.some(e=>e.data.name===name&&e.data.result.status==='AVAILABLE'),`${name} 未返回真实结果`);
     assert.ok(events.filter(e=>e.type==='action').length<=6);assert.ok(results.length<=10);assert.ok(events.some(e=>e.type==='retrieval'&&e.data.hits.length>0));
     const conversation=await api('/conversations/'+current.id);assert.equal(conversation.version,1);assert.equal(conversation.draft.agentExecutionId,report.executionId);assert.equal(conversation.draft.promptVersion,'v4');assert.ok(conversation.draft.itinerary.sources.length>0);assert.ok(Object.values(conversation.draft.schedule.walkingMinutesByDate).every(minutes=>minutes<=60),'必须实际满足全天步行60分钟上限');
     const stored=await api('/agent/runs/'+report.executionId);assert.equal(stored.status,'COMPLETED');assert.equal(stored.events.length,events.length);report.eventsDigest=digest(stored.events);return {rounds:events.filter(e=>e.type==='action').length,toolCalls:results.length,status:stored.status};
   });
   await check('智能体行程保存导出保留引用与执行ID',async()=>{const saved=await api(`/conversations/${current.id}/save`,'POST',{expectedVersion:1});report.tripId=saved.id;report.tripDigest=digest({request:saved.draft.request,itinerary:saved.draft.itinerary,agentExecutionId:saved.draft.agentExecutionId});const response=await fetch(base+`/trips/${saved.id}/export`,{headers:auth});assert.equal(response.status,200);const text=await response.text();assert.ok(text.includes(report.executionId));for(const source of saved.draft.itinerary.sources)assert.ok(text.includes(source.chunkId));return {tripId:saved.id};});
   await check('真实执行可取消且不会覆盖原有效行程',async()=>{const before=await api('/conversations/'+current.id),id=randomUUID();const events=await stream({executionId:id,conversationId:current.id,expectedVersion:before.version,instruction:'重新查天气与地点，保持城市、日期、人数与预算不变。'},true);const cancelled=await api('/agent/runs/'+id);assert.equal(cancelled.status,'CANCELLED');assert.ok(events.some(e=>e.type==='error'&&e.data.code==='CANCELLED'));const after=await api('/conversations/'+current.id);assert.equal(after.version,before.version);assert.deepEqual(after.draft.itinerary,before.draft.itinerary);report.cancelledExecutionId=id;return {status:cancelled.status,version:after.version};});
   report.phase='awaiting-restart';flush();console.log(`第一阶段完成；重启服务后运行 node scripts/verify-day4.mjs${desktop?' --desktop':''} --after-restart。`);
 }
}catch(error){report.phase='failed';report.error=error.message;flush();throw error;}
