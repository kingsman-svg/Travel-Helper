<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue';
import * as api from './api/client';
import type { Conversation, TravelRequest, Itinerary, SavedTrip, Message, KnowledgeSearch, AgentRun } from './types/api';
import TravelForm from './components/TravelForm.vue';
import TripPlan from './components/TripPlan.vue';
import ConnectionsView from './components/ConnectionsView.vue';
import KnowledgeView from './components/KnowledgeView.vue';
import SourceReferences from './components/SourceReferences.vue';
import AgentPanel from './components/AgentPanel.vue';

const page = ref<'planner' | 'saved' | 'knowledge' | 'connections'>('planner');
const conversation = ref<Conversation | null>(null);
const savedTrips = ref<SavedTrip[]>([]);
const messages = ref<Message[]>([]);
const text = ref(''), error = ref(''), notice = ref(''), busy = ref(false), loading = ref(true);
const showForm = ref(false), mode = ref<'adjust' | 'qa' | 'knowledge' | 'agent'>('qa');
const agentEnabled=ref(true), agentRun=ref<AgentRun|null>(null), agentAbort=ref<AbortController|null>(null), cancelling=ref(false);
const agentAnchor=ref<HTMLElement>();
const status = ref(''), end = ref<HTMLElement>(), composer = ref<HTMLTextAreaElement>();
const knowledgeCity = ref('杭州'), lastRetrieval = ref<KnowledgeSearch | null>(null);
const abort = ref<AbortController | null>(null);
const draft = computed(() => conversation.value?.draft ?? null);
const saved = computed(() => !!conversation.value?.savedTripId && conversation.value.savedVersion === conversation.value.version);
function apply(value: Conversation) {
  const changed=conversation.value?.id!==value.id;
  conversation.value = value;
  messages.value = value.messages;
  localStorage.setItem('travelbao.conversation', value.id);
  if(changed)agentRun.value=null;
  if(!agentAbort.value&&(changed||!agentRun.value))void loadAgent(value.id,value.draft?.agentExecutionId);
}
async function loadAgent(conversationId:string,executionId?:string|null){
  try{const run=executionId?await api.getAgentRun(executionId):await api.latestAgentRun(conversationId);if(conversation.value?.id===conversationId&&!agentAbort.value)agentRun.value=run;}
  catch{/* The conversation remains usable when historical execution records cannot be loaded. */}
}
async function runAgent(current:Conversation,request?:TravelRequest,instruction?:string){
  const id=crypto.randomUUID();agentAbort.value=new AbortController();
  agentRun.value={id,conversationId:current.id,status:'RUNNING',code:null,message:null,startedAt:new Date().toISOString(),finishedAt:null,events:[]};
  try{
    await api.streamAgent({executionId:id,conversationId:current.id,expectedVersion:current.version,request,instruction},agentAbort.value.signal,event=>{
      const run=agentRun.value;if(!run||run.id!==event.executionId)return;
      if(!run.events.some(e=>e.sequence===event.sequence))run.events.push(event);
      if(event.type==='started')void nextTick(()=>agentAnchor.value?.scrollIntoView({block:'start',behavior:'smooth'}));
      if(event.type==='action')status.value=String(event.data.summary);
      if(event.type==='itinerary'){apply(event.data as unknown as Conversation);showForm.value=false;mode.value='agent';}
      if(event.type==='done'){run.status='COMPLETED';run.message=String(event.data.message);notice.value='智能规划已完成并通过校验，请保存当前草稿。';}
      if(event.type==='error'){run.status=String(event.data.status||'FAILED');run.message=String(event.data.message);run.code=String(event.data.code);error.value=run.message;}
    });
  }catch(e){if(!(e instanceof DOMException&&e.name==='AbortError'&&agentRun.value?.status!=='RUNNING'))throw e;}
  finally{agentAbort.value=null;try{const run=await api.getAgentRun(id);if(agentRun.value?.id===id)agentRun.value=run;if(run.status==='COMPLETED')apply(await api.getConversation(current.id));}catch{if(agentRun.value?.status==='RUNNING'){agentRun.value.status='FAILED';agentRun.value.message='执行连接中断，请重试。';}}}
}
async function stopAgent(){
  if(!agentRun.value||cancelling.value)return;cancelling.value=true;
  try{agentRun.value=await api.cancelAgent(agentRun.value.id);agentAbort.value?.abort();}
  catch(e){error.value=e instanceof Error?e.message:'停止请求未完成，请重试。';}
  finally{cancelling.value=false;}
}
const agentPolling=window.setInterval(()=>{if(agentRun.value?.status==='RUNNING'&&!agentAbort.value)void loadAgent(agentRun.value.conversationId,agentRun.value.id);},1500);
onUnmounted(()=>{window.clearInterval(agentPolling);agentAbort.value?.abort();});
async function ensure() {
  if (!conversation.value) apply(await api.createConversation());
  return conversation.value!;
}
async function action(label: string, work: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true; error.value = ''; notice.value = ''; status.value = label;
  try { await work(); } catch (e) { error.value = e instanceof Error ? e.message : '操作未完成，请重试。'; }
  finally { busy.value = false; status.value = ''; }
}
async function newConversation() {
  await action('正在开始新的旅行…', async () => {
    apply(await api.createConversation()); text.value = ''; showForm.value = false; page.value = 'planner';
    mode.value = 'qa'; lastRetrieval.value = null;
    notice.value = '已开始新会话，不会继承上一段旅行的偏好。';
  });
}
async function generate(request: TravelRequest) {
  await action('正在生成行程，随后检查时间和费用…', async () => {
    const current = await ensure();
    if(agentEnabled.value){await runAgent(current,request);if(agentRun.value?.status!=='COMPLETED')return;}
    else apply(await api.generatePlan(current.id, current.version, request));
    showForm.value = false; mode.value = agentEnabled.value?'agent':'adjust'; knowledgeCity.value = request.destination; notice.value = '行程已生成并通过检查，点击保存即可加入“已保存行程”。';
  });
}
async function edit(itinerary: Itinerary) {
  await action('正在检查编辑后的行程…', async () => {
    const current = await ensure();
    apply(await api.editPlan(current.id, current.version, itinerary)); notice.value = '编辑已应用，预算和时间检查已更新，请保存此版本。';
  });
}
async function save() {
  await action('正在保存行程…', async () => {
    const current = await ensure();
    await api.savePlan(current.id, current.version); apply(await api.getConversation(current.id));
    savedTrips.value = await api.listTrips(); notice.value = '行程已保存到本机。';
  });
}
async function openSaved(trip: SavedTrip) {
  await action('正在打开行程…', async () => {
    apply(await api.getConversation(trip.conversationId)); page.value = 'planner'; showForm.value = false; mode.value = 'adjust';
    knowledgeCity.value = conversation.value?.draft?.request.destination || '杭州';
    if (!saved.value) notice.value = '已恢复该行程的最新草稿；导出文件使用上次明确保存的版本。';
    window.scrollTo({ top: 0 });
  });
}
async function loadSaved() {
  page.value = 'saved';
  await action('正在读取已保存行程…', async () => { savedTrips.value = await api.listTrips(); });
}
async function beginKnowledge(city: string, question: string) {
  page.value = 'planner'; mode.value = 'knowledge'; knowledgeCity.value = city; text.value = question;
  await nextTick(); composer.value?.focus(); composer.value?.scrollIntoView({ block: 'center', behavior: 'smooth' });
}
function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) { event.preventDefault(); void send(); }
}
async function send() {
  const message = text.value.trim(); if (!message || busy.value) return;
  await action(mode.value === 'knowledge' ? '正在检索资料并核对回答引用…' : draft.value && mode.value === 'adjust' ? '正在调整行程，并重新检查预算和时间…' : '旅行助手正在回复…', async () => {
    const current = await ensure(); text.value = '';
    if (mode.value === 'agent'&&draft.value) {
      try{await runAgent(current,undefined,message);if(agentRun.value?.status!=='COMPLETED')text.value=message;}catch(e){text.value=message;throw e;}
    } else if (mode.value === 'knowledge') {
      lastRetrieval.value = null;
      try {
        const answer = await api.askKnowledge(current.id, knowledgeCity.value, message);
        lastRetrieval.value = answer.retrieval; apply(await api.getConversation(current.id));
        await nextTick(); end.value?.scrollIntoView({ block: 'nearest' });
      } catch(e) { text.value = message; throw e; }
    } else if (draft.value && mode.value === 'adjust') {
      try { apply(await api.adjustPlan(current.id, current.version, message)); notice.value = '行程已调整，城市、人数、日期与预算口径保持一致。记得保存新版本。'; }
      catch (e) { text.value = message; throw e; }
    } else {
      messages.value.push({ id: crypto.randomUUID(), role: 'user', text: message });
      const answerId = crypto.randomUUID();
      messages.value.push({ id: answerId, role: 'assistant', text: '' });
      abort.value = new AbortController();
      try {
        await api.streamChat(message, abort.value.signal, event => {
          const answer = messages.value.find(m => m.id === answerId)!;
          if (event.type === 'delta') answer.text += event.content;
          if (event.type === 'status') status.value = event.content;
          if (event.type === 'error') { answer.error = true; answer.text += '\n' + event.content; }
          void nextTick(() => end.value?.scrollIntoView({ block: 'nearest' }));
        }, current.id);
      } catch (e) {
        const answer = messages.value.find(m => m.id === answerId)!;
        const cancelled = e instanceof DOMException && e.name === 'AbortError';
        answer.text += cancelled ? '\n已停止生成。' : '\n' + (e instanceof Error ? e.message : '连接中断，请重试。');
        answer.error = !cancelled;
      } finally { abort.value = null; }
    }
  });
}
onMounted(async () => {
  try {
    const id = localStorage.getItem('travelbao.conversation');
    if (id) {
      try { apply(await api.getConversation(id)); if (draft.value) { mode.value = 'adjust'; knowledgeCity.value = draft.value.request.destination; } }
      catch (e) { error.value = e instanceof Error ? e.message : '会话恢复失败'; }
    }
  } finally { loading.value = false; }
});
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <a class="brand" href="#" @click.prevent="page = 'planner'" aria-label="旅游宝首页"><span class="brand-symbol"><svg viewBox="0 0 32 32" fill="none" aria-hidden="true"><path d="M5 24 15.5 7 27 24H5Z" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/><path d="m11 15 4 4 4-5M6 28h20" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg></span><span>旅游宝<small>TRAVEL, YOUR WAY</small></span></a>
      <div class="workspace-label">我的旅行空间</div>
      <nav class="main-nav" aria-label="主要导航">
        <button :disabled="busy" :class="{ active: page === 'planner' }" @click="page = 'planner'"><span class="nav-icon">✦</span>旅行规划<span class="nav-arrow">↗</span></button>
        <button :disabled="busy" :class="{ active: page === 'saved' }" @click="loadSaved"><span class="nav-icon">▤</span>已保存行程</button>
        <button :disabled="busy" :class="{ active: page === 'knowledge' }" @click="page = 'knowledge'"><span class="nav-icon">▧</span>旅行知识库</button>
        <button :disabled="busy" :class="{ active: page === 'connections' }" @click="page = 'connections'"><span class="nav-icon">◈</span>连接状态</button>
      </nav>
      <button class="new-trip" :disabled="busy || loading" @click="newConversation">＋ 开始新的对话</button>
      <div class="sidebar-bottom"><div class="journey-art" aria-hidden="true"><svg viewBox="0 0 180 130" fill="none"><circle cx="129" cy="35" r="18" fill="#ebcf97"/><path d="M0 100 62 31l52 69" fill="#c9d8c6"/><path d="m65 100 55-54 60 54" fill="#9cb6a4"/><path d="M0 97c44-14 54 18 94 7 31-8 49-4 86 8v18H0Z" fill="#759985"/><path d="M53 130c13-24 62-19 59-36" stroke="#f5efe1" stroke-width="7" stroke-linecap="round"/></svg></div><p>留一点时间，<br>给路上的风景。</p><div class="local-indicator"><span></span>本机旅行空间 <span class="version">v0.4</span></div></div>
    </aside>
    <main class="main">
      <header class="topbar"><div class="breadcrumb">我的旅行空间 <span>/</span>{{ { planner: '旅行规划', saved: '已保存行程', knowledge: '旅行知识库', connections: '连接状态' }[page] }}</div><button class="mobile-new text-link" :disabled="busy || loading" @click="newConversation">＋ 新会话</button><span class="connection-pill">本机存储 · DeepSeek</span></header>
      <div class="global-feedback"><div v-if="error" class="notice error-notice" role="alert">{{ error }}<button @click="error = ''">关闭</button></div><div v-if="notice" class="notice success-notice" role="status">{{ notice }}<button @click="notice = ''">关闭</button></div><div v-if="busy" class="working-status" role="status"><span class="loading-dot"></span>{{ status }}</div></div>
      <section v-if="page === 'planner'" class="workspace">
        <p v-if="loading" class="intro">正在恢复旅行空间…</p>
        <template v-else>
          <div v-if="!draft || showForm" class="planning-start"><div class="eyebrow"><span></span>下一段旅程，从一个想法开始</div><h1>想去哪里，<em>一起计划。</em></h1><p class="intro">定下时间、预算和小小偏好，剩下的慢慢安排。</p><label class="agent-toggle"><input v-model="agentEnabled" type="checkbox" :disabled="busy"><span>智能体规划<small>查询天气、地点与路线，展示执行过程 · 支持杭州、苏州、上海</small></span></label><TravelForm :busy="busy" :initial="draft?.request" @generate="generate"/><button v-if="draft" class="text-link" :disabled="busy" @click="showForm = false">← 保留当前行程</button></div>
          <div v-if="agentRun" ref="agentAnchor"><AgentPanel :run="agentRun" :cancelling="cancelling" @cancel="stopAgent"/></div>
          <template v-if="draft && !showForm">
            <div class="trip-toolbar"><div><span class="status-tag" :class="saved ? 'passed' : ''">{{ saved ? '已保存到本机' : '待保存的草稿' }}</span><small>版本 {{ conversation?.version }}</small></div><div class="toolbar-actions"><button class="secondary-button" :disabled="busy" @click="showForm = true">重新填写需求</button><a v-if="conversation?.savedTripId" class="secondary-button" :href="`/api/trips/${conversation.savedTripId}/export`" download>导出已保存版本 ↓</a><button class="primary-button" :disabled="busy || saved" @click="save">{{ saved ? '已保存 ✓' : '保存行程' }}</button></div></div>
            <TripPlan :draft="draft" :busy="busy" @edit="edit"/>
          </template>
          <section class="chat-section">
            <div class="section-heading"><h2>{{ draft ? '把行程，聊得更合心意' : '还没想好？先和我聊聊' }}</h2><span>本会话记忆已启用</span></div>
            <div v-if="messages.length" class="conversation" aria-label="旅行对话" aria-live="polite"><article v-for="message in messages" :key="message.id" class="message" :class="[message.role, { 'message-error': message.error }]"><div v-if="message.role === 'assistant'" class="assistant-avatar">✦</div><div class="message-main"><div v-if="message.role === 'assistant'" class="message-author">旅游宝 <span>旅行助手</span></div><div class="message-body">{{ message.text || status || '正在准备回复…' }}</div><SourceReferences v-if="message.citations?.length" :citations="message.citations"/></div></article><div ref="end"></div></div>
            <form class="composer-area" @submit.prevent="send"><div class="chat-modes"><button v-if="draft" type="button" :disabled="busy" :class="{ active: mode === 'agent' }" @click="mode = 'agent'">智能调整</button><button v-if="draft" type="button" :disabled="busy" :class="{ active: mode === 'adjust' }" @click="mode = 'adjust'">调整行程</button><button type="button" :disabled="busy" :class="{ active: mode === 'qa' }" @click="mode = 'qa'">咨询建议</button><button type="button" :disabled="busy" :class="{ active: mode === 'knowledge' }" @click="mode = 'knowledge'">查知识库</button><span>{{ mode === 'agent' ? '查询真实工具后调整行程，支持停止' : mode === 'knowledge' ? '依据本次资料回答，附上可展开的出处' : mode === 'adjust' ? '会更新上方行程，保存后生效于导出' : '仅回答问题，保留行程安排' }}</span></div><label v-if="mode === 'knowledge'" class="knowledge-city">资料城市<input v-model.trim="knowledgeCity" :disabled="busy" maxlength="60" required list="knowledge-cities"><datalist id="knowledge-cities"><option>杭州</option><option>苏州</option><option>上海</option></datalist></label><p v-if="lastRetrieval && mode === 'knowledge'" class="search-summary">本次检索：{{ lastRetrieval.city }} · 命中 {{ lastRetrieval.hits.length }} 个片段 · {{ lastRetrieval.durationMs }} ms。回答下方仅列实际引用。</p><div class="composer-box"><label class="sr-only" for="travel-message">输入旅行需求</label><textarea id="travel-message" ref="composer" v-model="text" :disabled="busy" :maxlength="mode === 'knowledge' || mode === 'agent' ? 2000 : 4000" rows="2" :placeholder="mode === 'agent' ? '比如：查一下第二天的天气，尽量安排室内活动，其他条件不变…' : mode === 'knowledge' ? '比如：中国茶叶博物馆两个馆区的地址分别是什么？' : draft && mode === 'adjust' ? '比如：第二天少走路，多安排一些休息…' : '比如：带长辈出游，应该提前考虑什么？'" @keydown="onKeydown"></textarea><div class="composer-bottom"><span class="composer-hint">本会话会记住近期交流，可新建会话开始另一段旅行。</span><button v-if="agentAbort" type="button" class="stop-button" :disabled="cancelling" @click="stopAgent">停止智能规划 ■</button><button v-else-if="abort" type="button" class="stop-button" @click="abort?.abort()">停止生成 ■</button><button v-else type="submit" class="send-button" :disabled="busy || !text.trim()">{{ mode === 'agent' ? '智能调整' : mode === 'knowledge' ? '检索并回答' : draft && mode === 'adjust' ? '调整行程' : '发送消息' }} ↑</button></div></div><div class="composer-footnote"><span>Enter 发送 · Shift + Enter 换行</span><span>智能体模式可查询天气、地点与道路路线</span></div></form>
          </section>
        </template>
      </section>
      <section v-else-if="page === 'saved'" class="saved-view"><div class="eyebrow"><span></span>想走的路，好好收藏</div><h1>已保存行程</h1><p class="intro">保存的版本留在本机，重新打开后可以继续调整。</p><p v-if="!savedTrips.length && !busy" class="empty-state">还没有保存的行程。先生成一份旅行计划，再点击“保存行程”。</p><div class="saved-grid"><article v-for="trip in savedTrips" :key="trip.id" class="saved-card"><span class="eyebrow">{{ trip.draft.request.destination }} · {{ trip.draft.itinerary.days.length }} 天</span><h2>{{ trip.draft.itinerary.title }}</h2><p>{{ trip.draft.request.startDate }} — {{ trip.draft.request.endDate }} · {{ trip.draft.request.people }} 人</p><strong>¥{{ trip.draft.budget.total }} <small>预算包含项估算</small></strong><p class="saved-time">版本 {{ trip.version }} · {{ new Date(trip.savedAt).toLocaleString('zh-CN') }}</p><div><button class="primary-button" :disabled="busy" @click="openSaved(trip)">打开行程 ↗</button><a class="text-link" :href="`/api/trips/${trip.id}/export`" download>导出 Markdown ↓</a></div></article></div></section>
      <KnowledgeView v-else-if="page === 'knowledge'" @ask="beginKnowledge"/>
      <ConnectionsView v-else/>
    </main>
  </div>
</template>
