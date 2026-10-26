import assert from 'node:assert/strict';
import {readFileSync,writeFileSync,mkdirSync,existsSync} from 'node:fs';
import {homedir} from 'node:os';
import path from 'node:path';
import {data} from './runtime.mjs';
const directory=process.env.TRAVELBAO_DESKTOP_DATA||path.join(homedir(),'Library/Application Support/TravelBao');
const runtime=JSON.parse(readFileSync(path.join(directory,'desktop-runtime.json'),'utf8'));
assert.equal(runtime.ready,true,'桌面服务尚未就绪');
const base=`http://127.0.0.1:${runtime.backendPort}`,headers={'Content-Type':'application/json','X-TravelBao-Desktop':runtime.token};
const file=path.join(data,'verification/desktop.json');mkdirSync(path.dirname(file),{recursive:true});
const report={checkedAt:new Date().toISOString(),mode:'packaged-mac-live',bundlePath:runtime.bundlePath,checks:[],allPassed:false};
async function check(name,work){try{const detail=await work();report.checks.push({name,passed:true,detail});console.log('通过：'+name);}catch(e){report.checks.push({name,passed:false,error:e.message});throw e;}finally{writeFileSync(file,JSON.stringify(report,null,2));}}
async function api(endpoint,body){const r=await fetch(base+'/api'+endpoint,{method:body===undefined?'GET':'POST',headers,body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(100000)});assert.equal(r.status,200);return r.json();}
await check('生产页面由随包后端提供，无Vite',async()=>{const r=await fetch(base);assert.equal(r.status,200);const html=await r.text();assert.ok(html.includes('旅游宝'));assert.ok(!html.includes('/@vite/client'));const asset=html.match(/src="([^"]+\.js)"/);assert.ok(asset);assert.equal((await fetch(base+asset[1])).status,200);return {port:runtime.backendPort,asset:asset[1]};});
await check('桌面接口拒绝无会话访问',async()=>{assert.equal((await fetch(base+'/api/trips')).status,403);assert.equal((await fetch(base+'/api/trips',{headers:{'X-TravelBao-Desktop':'wrong'}})).status,403);assert.equal((await fetch(base+'/configuration.json')).status,404);return {unauthenticated:403,configurationNotServed:true};});
await check('迁移的行程、知识库和执行记录可读',async()=>{const trips=await api('/trips');assert.ok(trips.length>=1);const docs=await api('/knowledge/documents');assert.ok(docs.length>=6);const agentTrip=trips.find(t=>t.draft.agentExecutionId);assert.ok(agentTrip);const run=await api('/agent/runs/'+agentTrip.draft.agentExecutionId);assert.equal(run.status,'COMPLETED');return {trips:trips.length,documents:docs.length,agentEvents:run.events.length};});
await check('随包Ollama能检索已有向量索引',async()=>{const found=await api('/knowledge/search',{city:'杭州',query:'中国茶叶博物馆两馆区的地址'});assert.ok(found.hits.some(h=>h.documentId==='hangzhou-tea-museum'));return {status:found.status,hits:found.hits.length,model:found.model};});
await check('独立MCP发现与真实天气查询',async()=>{const tools=await api('/tourism/tools');assert.equal(tools.length,3);const date=new Date().toLocaleDateString('sv-SE',{timeZone:'Asia/Shanghai'});const result=await api('/tourism/weather_forecast',{city:'杭州',date});assert.equal(result.status,'AVAILABLE',result.message);assert.equal(result.data.date,date);return {provider:result.provider,date,temperatureUnit:result.data.units.temperature_2m_max};});
await check('打包窗口使用的真实DeepSeek流式链路',async()=>{const c=await api('/conversations',{});const r=await fetch(base+'/api/chat',{method:'POST',headers:{...headers,Accept:'text/event-stream'},body:JSON.stringify({conversationId:c.id,message:'这是旅游宝桌面版连通验收，请只回复：桌面连接成功。'}),signal:AbortSignal.timeout(45000)});assert.equal(r.status,200);const stream=await r.text();assert.ok(stream.includes('"type":"delta"'));assert.ok(stream.includes('"type":"done"'));assert.ok(!stream.includes('"type":"error"'));return {conversationId:c.id,streamCompleted:true};});
// Secret contents are never written to this report. Scan the generated bundle paths only.
await check('安装包不包含用户配置或数据库',async()=>{for(const relative of ['configuration.json','.env','.data','db/travelbao.mv.db'])assert.equal(existsSync(path.join(runtime.bundlePath,'Contents/Resources',relative)),false);return {privateDataOutsideBundle:true};});
report.allPassed=true;writeFileSync(file,JSON.stringify(report,null,2));console.log('桌面真实验收全部通过。');
