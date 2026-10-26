import assert from 'node:assert/strict';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { root, data, configuration, waitFor } from './runtime.mjs';

const env=configuration(), base=`http://127.0.0.1:${env.BACKEND_PORT||8080}/api`;
const directory=path.join(data,'verification'), reportPath=path.join(directory,'day3.json');
mkdirSync(directory,{recursive:true});
const restarting=process.argv.includes('--after-restart');
const report=restarting?JSON.parse(readFileSync(reportPath,'utf8')):{checkedAt:new Date().toISOString(),checks:[],qaCases:[]};
report.allPassed=false;report.phase=restarting?'restart-running':'running';
const flush=()=>writeFileSync(reportPath,JSON.stringify(report,null,2));flush();
async function api(endpoint,method='GET',body,status=200) {
  const response=await fetch(base+endpoint,{method,headers:body instanceof FormData?undefined:{'Content-Type':'application/json'},
    body:body===undefined?undefined:body instanceof FormData?body:JSON.stringify(body),signal:AbortSignal.timeout(210_000)});
  const value=await response.json();assert.equal(response.status,status,value.message||endpoint);return value;
}
async function check(name,work) {
  const start=Date.now();
  try {const detail=await work();report.checks.push({name,passed:true,durationMs:Date.now()-start,detail});console.log(`通过：${name}`);}
  catch(error){report.checks.push({name,passed:false,error:error.message});throw error;}
  finally {flush();}
}
const stableHits=result=>result.hits.map(x=>({documentId:x.documentId,chunkId:x.chunkId,sourceUrl:x.sourceUrl,excerpt:x.excerpt}));
const normalize=text=>text.normalize('NFKC').replace(/星期/g,'周').replace(/礼拜/g,'周').replace(/[\s，、。-]/g,'');
try {
  assert.ok(await waitFor(base+'/health'),'后端未就绪');
  if(restarting) {
    await check('重启后文档、向量维度与索引代次保持一致',async()=>{
      const state=await api('/knowledge/status');assert.equal(state.status,'READY');
      for(const key of ['documentCount','chunkCount','dimensions','indexedModel','modelDigest','generation'])assert.equal(state[key],report.indexState[key]);
      return {documents:state.documentCount,chunks:state.chunkCount,dimensions:state.dimensions};
    });
    await check('重启后检索结果与历史问答引用保持一致',async()=>{
      const result=await api('/knowledge/search','POST',report.restartQuery);assert.deepEqual(stableHits(result),report.restartHits);
      const conversation=await api('/conversations/'+report.answerConversationId);
      assert.deepEqual(conversation.messages.at(-1).citations,report.answerCitations);
      const saved=await api('/trips/'+report.tripId);assert.deepEqual(saved.draft.itinerary.sources,report.tripSources);
      return '检索片段、聊天来源及已保存行程引用均恢复';
    });
    report.restartCheckedAt=new Date().toISOString();report.phase='complete';report.allPassed=report.qaPassed>=8&&report.noAnswerPassed===2&&report.checks.every(c=>c.passed);flush();
  } else {
    const catalog=JSON.parse(readFileSync(path.join(root,'knowledge-data/catalog.json'),'utf8'));
    await check('六份官方示例资料导入并建立真实向量索引',async()=>{
      const imported=await api('/knowledge/examples/import','POST');assert.equal(imported.length,6);assert.ok(imported.every(x=>x.document.status==='READY'));
      const state=await api('/knowledge/status');assert.equal(state.status,'READY');assert.equal(state.dimensions,1024);assert.ok(state.modelDigest);
      for(const item of catalog){const detail=await api('/knowledge/documents/'+item.id);assert.equal(detail.document.sourceUrl,item.sourceUrl);assert.ok(detail.chunks.length>0);assert.ok(detail.content.startsWith('---'));assert.ok(detail.chunks.every(c=>!c.content.startsWith('---')));}
      return {documents:state.documentCount,chunks:state.chunkCount,dimensions:state.dimensions};
    });
    await check('重复导入与上传去重保留原出处',async()=>{
      const before=await api('/knowledge/status');const repeated=await api('/knowledge/examples/import','POST');assert.ok(repeated.every(x=>x.duplicate));
      const item=catalog[0],form=new FormData();
      for(const [key,value] of Object.entries({city:item.city,title:'重复上传不覆盖标题',sourceUrl:'https://example.org/not-authoritative',verifiedAt:item.verifiedAt}))form.append(key,value);
      form.append('file',new Blob([readFileSync(path.join(root,'knowledge-data',item.file))]),'duplicate.md');
      const uploaded=await api('/knowledge/documents','POST',form);assert.equal(uploaded.duplicate,true);assert.equal(uploaded.document.sourceUrl,item.sourceUrl);assert.equal(uploaded.document.origin,'CURATED_EXAMPLE');
      const after=await api('/knowledge/status');assert.equal(after.chunkCount,before.chunkCount);assert.equal(after.documentCount,before.documentCount);return '数量未增加，来源元数据未被重复上传覆盖';
    });
    await check('上传无效UTF-8、文件格式及超限文件被拦截',async()=>{
      for(const [filename,content,status] of [['bad.txt',new Uint8Array([195,40]),400],['bad.pdf',new Uint8Array(40).fill(65),400],['large.txt',new Uint8Array(262145).fill(65),413]]){
        const form=new FormData();for(const [key,value] of Object.entries({city:'杭州',title:'无效文件验收',sourceUrl:'https://example.org/visit',verifiedAt:catalog[0].verifiedAt}))form.append(key,value);
        form.append('file',new Blob([content]),filename);await api('/knowledge/documents','POST',form,status);
      }
      return '三个无效请求均返回明确错误';
    });
    await check('城市过滤与真实索引重建',async()=>{
      const result=await api('/knowledge/search','POST',{city:'杭州市',query:'上海博物馆地址'});assert.ok(result.hits.every(x=>x.city==='杭州'));
      const missing=await api('/knowledge/search','POST',{city:'无资料城市',query:'博物馆地址'});assert.equal(missing.hits.length,0);
      const old=await api('/knowledge/status'),rebuilt=await api('/knowledge/rebuild','POST');assert.equal(rebuilt.status,'READY');assert.equal(rebuilt.chunkCount,old.chunkCount);assert.notEqual(rebuilt.generation,old.generation);
      return {crossCityHits:result.hits.length,generation:rebuilt.generation};
    });
    const cases=JSON.parse(readFileSync(path.join(root,'knowledge-data/qa-cases.json'),'utf8'));
    for(const entry of cases) {
      const current=await api('/conversations','POST'),start=Date.now();
      try {
        const answer=await api('/knowledge/ask','POST',{conversationId:current.id,city:entry.city,message:entry.question});
        const expected=entry.expectedDocumentId;
        const citationsValid=answer.citations.every(c=>answer.retrieval.hits.some(h=>JSON.stringify(h)===JSON.stringify(c)));
        const passed=expected?answer.status==='ANSWERED'&&entry.expectedKeywords.every(k=>normalize(answer.answer).includes(normalize(k)))&&answer.citations.some(c=>c.documentId===expected)&&citationsValid:answer.status==='INSUFFICIENT'&&answer.citations.length===0;
        report.qaCases.push({...entry,passed,durationMs:Date.now()-start,conversationId:current.id,answer});
        console.log(`${passed?'通过':'未通过'}：${entry.id} ${entry.question}`);
        if(expected&&passed&&!report.answerConversationId){report.answerConversationId=current.id;report.answerCitations=answer.citations;}
      } catch(error) {report.qaCases.push({...entry,passed:false,error:error.message});console.log(`未通过：${entry.id} ${error.message}`);}
      flush();
    }
    report.qaPassed=report.qaCases.filter(c=>c.expectedDocumentId&&c.passed).length;
    report.noAnswerPassed=report.qaCases.filter(c=>!c.expectedDocumentId&&c.passed).length;flush();
    await check('固定资料问答达到8/10且两个无依据问题明确拒答',async()=>{
      assert.ok(report.qaPassed>=8,`有依据问答仅${report.qaPassed}/10通过`);assert.equal(report.noAnswerPassed,2);return {supported:report.qaPassed,total:10,insufficient:report.noAnswerPassed};
    });
    await check('真实行程引用、保存导出与来源防篡改',async()=>{
      const current=await api('/conversations','POST');
      const request={origin:'苏州',destination:'上海',startDate:'2026-11-07',endDate:'2026-11-08',people:2,totalBudget:3000,budgetIncludes:['ACCOMMODATION','FOOD','TRANSPORT','TICKETS','OTHER'],preferences:'以博物馆为主，想去上海博物馆东馆和人民广场馆，按各馆真实地址与当前参观安排规划，避免闭馆日。每天步行不超过60分钟。'};
      const generated=await api(`/conversations/${current.id}/plan`,'POST',{expectedVersion:0,request});
      const sources=generated.draft.itinerary.sources;assert.ok(sources.length>0);assert.equal(generated.draft.promptVersion,'v3');
      for(const source of sources){const detail=await api('/knowledge/documents/'+source.documentId);assert.equal(source.url,detail.document.sourceUrl);assert.ok(detail.chunks.some(c=>c.id===source.chunkId));}
      const fake=structuredClone(generated.draft.itinerary);fake.sources[0].url='https://wrong.example/';
      await api(`/conversations/${current.id}/draft`,'PUT',{expectedVersion:1,itinerary:fake},400);assert.equal((await api('/conversations/'+current.id)).version,1);
      const saved=await api(`/conversations/${current.id}/save`,'POST',{expectedVersion:1});report.tripId=saved.id;report.tripSources=sources;
      const exported=await fetch(base+`/trips/${saved.id}/export`);assert.equal(exported.status,200);const md=await exported.text();for(const source of sources){assert.ok(md.includes(source.chunkId));assert.ok(md.includes(source.verifiedAt));}
      return {tripId:saved.id,sources:sources.map(s=>s.chunkId)};
    });
    report.restartQuery={city:'杭州',query:cases[0].question};report.restartHits=stableHits(await api('/knowledge/search','POST',report.restartQuery));
    report.indexState=await api('/knowledge/status');report.phase='awaiting-restart';flush();
    console.log('第一阶段完成；停止并重新启动服务后运行 node scripts/verify-day3.mjs --after-restart。');
  }
} catch(error) {report.phase='failed';report.error=error.message;flush();throw error;}
