<script setup lang="ts">
import {computed,onMounted,ref} from 'vue';
import * as api from './api/client';
import type {Conversation,TravelRequest,Itinerary,SavedTrip} from './types/api';
import TravelForm from './components/TravelForm.vue';
import TripPlan from './components/TripPlan.vue';
import ConnectionsView from './components/ConnectionsView.vue';

import SourceReferences from './components/SourceReferences.vue';
const hasKnowledge=3>=4;
const page=ref<'planner'|'saved'|'knowledge'|'connections'>('planner');
const conversation=ref<Conversation|null>(null),savedTrips=ref<SavedTrip[]>([]);
const busy=ref(false),error=ref(''),notice=ref(''),text=ref(''),form=ref(false),adjust=ref(false),knowledgeMode=ref(false),knowledgeCity=ref('杭州');
const controller=ref<AbortController|null>(null);
const draft=computed(()=>conversation.value?.draft??null);
const saved=computed(()=>!!conversation.value?.savedTripId&&conversation.value.savedVersion===conversation.value.version);
function apply(value:Conversation){conversation.value=value;localStorage.setItem('travelbao.conversation',value.id);}
async function work(task:()=>Promise<void>){if(busy.value)return;busy.value=true;error.value='';notice.value='';try{await task();}catch(e){error.value=e instanceof Error?e.message:'操作未完成';}finally{busy.value=false;}}
async function current(){if(!conversation.value)apply(await api.createConversation());return conversation.value!;}
async function create(){await work(async()=>{apply(await api.createConversation());page.value='planner';text.value='';form.value=false;notice.value='已开始新的独立会话。';});}
async function generate(request:TravelRequest){await work(async()=>{const c=await current();apply(await api.generatePlan(c.id,c.version,request));form.value=false;adjust.value=true;notice.value='行程已通过校验，请保存。';});}
async function edit(itinerary:Itinerary){await work(async()=>{const c=await current();apply(await api.editPlan(c.id,c.version,itinerary));notice.value='编辑已校验，记得保存。';});}
async function save(){await work(async()=>{const c=await current();await api.savePlan(c.id,c.version);apply(await api.getConversation(c.id));savedTrips.value=await api.listTrips();notice.value='已保存到本机。';});}
async function open(trip:SavedTrip){await work(async()=>{apply(await api.getConversation(trip.conversationId));page.value='planner';form.value=false;adjust.value=true;});}
async function send(){if(!text.value.trim())return;const question=text.value;await work(async()=>{const c=await current();
 if(hasKnowledge&&knowledgeMode.value){await api.askKnowledge(c.id,knowledgeCity.value,question);apply(await api.getConversation(c.id));}
 else if(draft.value&&adjust.value){apply(await api.adjustPlan(c.id,c.version,question));notice.value='行程已调整，请保存新版本。';}
 else{controller.value=new AbortController();const answer={id:crypto.randomUUID(),role:'assistant' as const,text:''};c.messages.push({id:crypto.randomUUID(),role:'user',text:question},answer);const output=c.messages[c.messages.length-1]!;try{await api.streamChat(question,controller.value.signal,e=>{if(e.type==='delta')output.text+=e.content;if(e.type==='error')throw new Error(e.content);},c.id);apply(await api.getConversation(c.id));}finally{controller.value=null;}}
 text.value='';});}
onMounted(async()=>{await work(async()=>{savedTrips.value=await api.listTrips();const id=localStorage.getItem('travelbao.conversation');if(id){try{apply(await api.getConversation(id));}catch{localStorage.removeItem('travelbao.conversation');}}if(!conversation.value)apply(await api.createConversation());});});
</script>
<template><main style="max-width:1200px;margin:20px auto;padding:30px">
<nav style="display:flex;gap:12px;flex-wrap:wrap;margin-bottom:35px"><button class="primary-button" @click="page='planner'">旅行规划</button><button class="secondary-button" @click="page='saved'">已保存行程</button><button class="secondary-button" @click="page='connections'">连接状态</button><button class="secondary-button" :disabled="busy" @click="create">新会话</button></nav>
<p class="notice" v-if="error" role="alert">{{error}}</p><p class="notice" v-if="notice">{{notice}}</p>
<section v-if="page==='planner'"><div class="eyebrow">旅游宝 · 阶段3</div><h1>把旅行，安排得更合心意。</h1>
<TravelForm v-if="!draft||form" :busy="busy" :initial="draft?.request" @submit="generate"/>
<section v-else><div class="draft-toolbar"><p>{{saved?'已保存':'待保存草稿'}} · 版本{{conversation?.version}}</p><button class="secondary-button" :disabled="busy" @click="form=true">重新填写需求</button><button class="primary-button" :disabled="busy||saved" @click="save">保存行程</button><a v-if="conversation?.savedTripId" :href="`/api/trips/${conversation.savedTripId}/export`" download>导出已保存版本</a></div><TripPlan :draft="draft" :busy="busy" @edit="edit"/></section>
<section class="chat-section"><h2>继续聊聊旅行</h2><div class="conversation"><article class="message" v-for="message in conversation?.messages" :key="message.id"><strong>{{message.role==='user'?'我':'旅游宝'}}</strong><p class="message-body">{{message.text}}</p><SourceReferences v-if="message.citations?.length" :citations="message.citations"/></article></div>
<form @submit.prevent="send"><label v-if="draft"><input type="checkbox" v-model="adjust" :disabled="busy">调整当前行程</label><label v-if="hasKnowledge"><input type="checkbox" v-model="knowledgeMode" :disabled="busy">依据知识库回答</label><label v-if="hasKnowledge&&knowledgeMode">资料城市<input v-model="knowledgeCity" required></label><label for="message">旅行需求</label><textarea id="message" v-model="text" :disabled="busy" rows="3" maxlength="2000" style="display:block;width:100%;margin:15px 0"/><button class="primary-button" :disabled="busy||!text.trim()">发送</button><button type="button" v-if="controller" @click="controller?.abort()">停止回复</button></form></section></section>
<section v-else-if="page==='saved'" class="saved-view"><h1>已保存行程</h1><div class="saved-grid"><article class="saved-card" v-for="trip in savedTrips" :key="trip.id"><h2>{{trip.draft.itinerary.title}}</h2><p>{{trip.draft.request.startDate}} — {{trip.draft.request.endDate}}</p><p>¥{{trip.draft.budget.total}} · 版本{{trip.version}}</p><button class="primary-button" :disabled="busy" @click="open(trip)">打开行程</button><a :href="`/api/trips/${trip.id}/export`" download>导出Markdown</a></article></div></section>
<ConnectionsView v-else/>
</main></template>
