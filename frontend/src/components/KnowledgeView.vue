<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue';
import * as api from '../api/client';
import type { KnowledgeDocument, KnowledgeDetail, KnowledgeSearch, KnowledgeState } from '../types/api';
import SourceReferences from './SourceReferences.vue';
const emit=defineEmits<{ask:[city:string,question:string]}>();
const documents=ref<KnowledgeDocument[]>([]), state=ref<KnowledgeState|null>(null), detail=ref<KnowledgeDetail|null>(null);
const detailPanel=ref<HTMLElement>();
const busy=ref(''), error=ref(''), notice=ref(''), result=ref<KnowledgeSearch|null>(null);
const filter=ref('全部'), searchCity=ref('杭州'), query=ref('中国茶叶博物馆两个馆区的地址分别是什么？');
const city=ref('杭州'),title=ref(''),url=ref(''),verified=ref(new Date().toLocaleDateString('sv-SE')),file=ref<File|null>(null), fileInput=ref<HTMLInputElement>();
const cities=computed(()=>[...new Set(['杭州','苏州','上海',...documents.value.map(d=>d.city)])]);
const shown=computed(()=>documents.value.filter(d=>filter.value==='全部'||d.city===filter.value));
const stateLabels={READY:'索引可用',EMPTY:'等待导入',REBUILD_REQUIRED:'需要重建',UNAVAILABLE:'向量服务不可用'};
async function refresh() { [documents.value,state.value]=await Promise.all([api.knowledgeDocuments(),api.knowledgeStatus()]); }
async function action(name:string,work:()=>Promise<void>) {
  if (busy.value) return; busy.value=name; error.value=''; notice.value='';
  try { await work(); } catch(e) { error.value=e instanceof Error?e.message:'操作未完成，请重试。'; }
  finally { busy.value=''; try { await refresh(); } catch { if(!error.value)error.value='无法读取知识库状态，请检查后端连接。'; } }
}
function chooseFile(event:Event) { file.value=(event.target as HTMLInputElement).files?.[0]||null; }
async function upload() {
  if(!file.value) {error.value='请选择资料文件。';return;}
  const selected=file.value;
  if(!/\.(md|txt)$/i.test(selected.name)||selected.size>262144) {error.value='请选择不超过256KB的 .md 或 .txt 文件。';return;}
  await action('正在导入并建立索引…',async()=>{
    const body=new FormData(); body.append('city',city.value);body.append('title',title.value);body.append('sourceUrl',url.value);body.append('verifiedAt',verified.value);body.append('file',selected);
    const imported=await api.uploadKnowledge(body);notice.value=imported.duplicate?'相同城市与内容已存在，沿用原资料，没有重复建库。':'资料已导入并完成索引。';
    file.value=null;if(fileInput.value)fileInput.value.value='';
  });
}
async function examples() { await action('正在导入六份示例资料…',async()=>{ const items=await api.importKnowledgeExamples();notice.value=`已处理 ${items.length} 份资料，其中 ${items.filter(x=>x.duplicate).length} 份已存在。`; }); }
async function rebuild() { await action('正在重建向量索引，原资料会保留…',async()=>{state.value=await api.rebuildKnowledge();notice.value='索引重建完成，模型与维度已更新。';}); }
async function search() { await action('正在检索相关片段…',async()=>{result.value=await api.searchKnowledge(searchCity.value,query.value);}); }
async function open(id:string) { await action('正在读取资料…',async()=>{detail.value=await api.knowledgeDocument(id);await nextTick();detailPanel.value?.scrollIntoView({block:'start',behavior:'smooth'});}); }
async function retry(id:string) { await action('正在重新索引资料…',async()=>{await api.retryKnowledge(id);notice.value='该资料索引完成。';}); }
onMounted(()=>action('正在读取知识库…',async()=>{}));
</script>
<template>
  <section class="knowledge-view">
    <div class="eyebrow"><span></span>让旅行建议，有据可循</div><div class="knowledge-title"><div><h1>旅行知识库</h1><p class="intro">导入资料，检索原文，为问答与行程提供出处。</p></div><button class="primary-button" :disabled="!!busy" @click="examples">导入六份示例资料 ↗</button></div>
    <div v-if="error" class="notice error-notice" role="alert">{{error}}</div><div v-if="notice" class="notice success-notice" role="status">{{notice}}</div><div v-if="busy" class="working-status" role="status"><span class="loading-dot"></span>{{busy}}</div>
    <div class="knowledge-state"><div><span class="status-tag" :class="state?.status==='READY'?'passed':''">{{state?stateLabels[state.status]:'读取中'}}</span><p>{{state?.message||'正在检查索引。'}}</p><small>{{state?.documentCount||0}} 份资料 · {{state?.chunkCount||0}} 个片段 · {{state?.indexedModel||state?.configuredModel||'BGE-M3'}}{{state?.dimensions?' · '+state.dimensions+' 维':''}}</small></div><button class="secondary-button" :disabled="!!busy||!documents.length" @click="rebuild">重建索引</button></div>
    <div class="knowledge-layout">
      <div class="knowledge-main">
        <div class="section-heading"><h2>资料书架</h2><label class="sr-only" for="knowledge-filter">筛选资料城市</label><select id="knowledge-filter" v-model="filter"><option>全部</option><option v-for="item in cities" :key="item">{{item}}</option></select></div>
        <p v-if="!documents.length&&!busy" class="empty-state">先导入杭州、苏州、上海的六份示例资料，也可以上传自己的资料。</p>
        <div class="document-list"><article v-for="doc in shown" :key="doc.id" class="document-card"><div><span class="document-city">{{doc.city}}</span><span class="status-tag" :class="doc.status==='READY'&&state?.status==='READY'?'passed':''">{{doc.status==='FAILED'?'索引失败':doc.status==='INDEXING'?'索引中':state?.status==='REBUILD_REQUIRED'?'需要重建':'已索引'}}</span></div><h3>{{doc.title}}</h3><p>{{doc.origin==='CURATED_EXAMPLE'?'已核对官方来源的示例':'用户提供来源'}} · 核对日 {{doc.verifiedAt}}</p><small>{{doc.chunkCount}} 个片段 · {{doc.filename}}</small><p v-if="doc.errorMessage" class="form-error">{{doc.errorMessage}}</p><div class="document-actions"><button class="text-link" :disabled="!!busy" @click="open(doc.id)">查看原文与片段 ↗</button><a class="text-link" :href="doc.sourceUrl" target="_blank" rel="noopener noreferrer">来源网页 ↗</a><button v-if="doc.status==='FAILED'" class="text-link" :disabled="!!busy" @click="retry(doc.id)">重新索引</button></div></article></div>
        <section class="knowledge-search"><div class="section-heading"><h2>试着查一查</h2><span>这里只展示检索片段</span></div><form @submit.prevent="search"><label>检索城市<select v-model="searchCity"><option v-for="item in cities" :key="item">{{item}}</option></select></label><label>想找什么资料？<textarea v-model="query" required maxlength="2000" rows="2" placeholder="比如：茶叶博物馆两个馆区分别在哪里？"></textarea></label><div class="search-actions"><button class="secondary-button" :disabled="!!busy||!query.trim()" type="submit">检索资料</button><button class="primary-button" :disabled="!!busy||!query.trim()" type="button" @click="emit('ask',searchCity,query)">让助手依据资料回答 ↗</button></div></form><template v-if="result"><p class="search-summary">{{result.message}} · {{result.durationMs}} ms</p><SourceReferences title="本次检索命中" :citations="result.hits"/></template></section>
      </div>
      <aside class="upload-panel"><h2>添加旅行资料</h2><p>UTF-8 的 Markdown / TXT，单份不超过256KB、40000字。来源与核对日期由你提供，应用不会自动核实网页。</p><form @submit.prevent="upload"><fieldset :disabled="!!busy"><label>适用城市<input v-model.trim="city" required maxlength="60" placeholder="如：杭州"></label><label>资料标题<input v-model.trim="title" required maxlength="150" placeholder="如：景点参观须知"></label><label>来源链接<input v-model.trim="url" type="url" required maxlength="500" placeholder="https://…"></label><label>核对日期<input v-model="verified" type="date" required></label><label>资料文件<input ref="fileInput" type="file" accept=".md,.txt,text/plain,text/markdown" required @change="chooseFile"></label><button class="primary-button" type="submit">导入并建立索引</button></fieldset></form><p class="upload-note">重复内容沿用原资料；模型或向量维度变化后需要重建索引。资料与向量一起保存在本机。</p></aside>
    </div>
    <section v-if="detail" ref="detailPanel" class="document-detail" aria-label="资料详情"><div class="section-heading"><h2>{{detail.document.title}}</h2><button class="text-link" @click="detail=null">关闭详情 ×</button></div><p>{{detail.document.city}} · {{detail.document.origin==='CURATED_EXAMPLE'?'示例资料':'用户导入'}} · 核对日 {{detail.document.verifiedAt}}</p><a :href="detail.document.sourceUrl" target="_blank" rel="noopener noreferrer">打开来源网页 ↗</a><details open><summary>导入的原文</summary><pre>{{detail.content}}</pre></details><details><summary>索引片段（{{detail.chunks.length}}）</summary><article v-for="chunk in detail.chunks" :key="chunk.id"><small>{{chunk.id}}</small><pre>{{chunk.content}}</pre></article></details></section>
  </section>
</template>
