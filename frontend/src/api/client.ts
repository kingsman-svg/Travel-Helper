import type { ChatEvent, IntegrationResponse, ProbeResult, Conversation, TravelRequest, Itinerary, SavedTrip } from '../types/api';
import type { KnowledgeAnswer, KnowledgeDocument, KnowledgeDetail, KnowledgeImport, KnowledgeSearch, KnowledgeState } from '../types/api';
import type { AgentEvent, AgentRun } from '../types/api';

async function jsonResponse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new Error(body?.message || '服务暂时不可用，请确认后端已启动。');
  }
  return response.json() as Promise<T>;
}

export async function integrations(): Promise<IntegrationResponse> {
  return jsonResponse(await fetch('/api/integrations'));
}

export async function probe(type: string): Promise<ProbeResult> {
  return jsonResponse(await fetch(`/api/probes/${type}`, { method: 'POST' }));
}

export async function streamChat(message: string, signal: AbortSignal, onEvent: (event: ChatEvent) => void, conversationId?: string) {
  const response = await fetch('/api/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ message, conversationId }),
    signal,
  });
  if (!response.ok) {
    await jsonResponse(response);
    return;
  }
  if (!response.body) throw new Error('浏览器无法读取响应流，请刷新后重试。');
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let terminated = false;

  function accept(frame: string) {
    const data = frame.split('\n').filter(line => line.startsWith('data:'))
      .map(line => line.slice(5).trimStart()).join('\n');
    if (!data) return;
    const event = JSON.parse(data) as ChatEvent;
    if (event.type === 'done' || event.type === 'error') terminated = true;
    onEvent(event);
  }

  try {
    while (true) {
      const { value, done } = await reader.read();
      buffer += decoder.decode(value, { stream: !done });
      buffer = buffer.replace(/\r\n/g, '\n');
      let boundary: number;
      while ((boundary = buffer.indexOf('\n\n')) !== -1) {
        accept(buffer.slice(0, boundary));
        buffer = buffer.slice(boundary + 2);
      }
      if (done) break;
    }
    if (buffer.trim()) accept(buffer);
    if (!terminated) throw new Error('连接已中断，回复可能不完整，请重试。');
  } finally {
    reader.releaseLock();
  }
}

async function request<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  return jsonResponse(await fetch(`/api${path}`, { method, headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) }));
}
export const createConversation = () => request<Conversation>('/conversations', 'POST');
export const getConversation = (id: string) => request<Conversation>(`/conversations/${id}`);
export const generatePlan = (id: string, expectedVersion: number, travel: TravelRequest) => request<Conversation>(`/conversations/${id}/plan`, 'POST', { expectedVersion, request: travel });
export const adjustPlan = (id: string, expectedVersion: number, message: string) => request<Conversation>(`/conversations/${id}/adjust`, 'POST', { expectedVersion, message });
export const editPlan = (id: string, expectedVersion: number, itinerary: Itinerary) => request<Conversation>(`/conversations/${id}/draft`, 'PUT', { expectedVersion, itinerary });
export const savePlan = (id: string, expectedVersion: number) => request<SavedTrip>(`/conversations/${id}/save`, 'POST', { expectedVersion });
export const listTrips = () => request<SavedTrip[]>('/trips');
export const getTrip = (id: string) => request<SavedTrip>(`/trips/${id}`);
export const knowledgeDocuments = () => request<KnowledgeDocument[]>('/knowledge/documents');
export const knowledgeStatus = () => request<KnowledgeState>('/knowledge/status');
export const knowledgeDocument = (id: string) => request<KnowledgeDetail>(`/knowledge/documents/${encodeURIComponent(id)}`);
export const importKnowledgeExamples = () => request<KnowledgeImport[]>('/knowledge/examples/import', 'POST');
export const rebuildKnowledge = () => request<KnowledgeState>('/knowledge/rebuild', 'POST');
export const retryKnowledge = (id: string) => request<KnowledgeImport>(`/knowledge/documents/${encodeURIComponent(id)}/index`, 'POST');
export const searchKnowledge = (city: string, query: string) => request<KnowledgeSearch>('/knowledge/search', 'POST', {city,query});
export const askKnowledge = (conversationId: string, city: string, message: string) => request<KnowledgeAnswer>('/knowledge/ask', 'POST', {conversationId,city,message});
export async function uploadKnowledge(form: FormData): Promise<KnowledgeImport> { return jsonResponse(await fetch('/api/knowledge/documents', {method:'POST',body:form})); }
export const getAgentRun = (id: string) => request<AgentRun>(`/agent/runs/${id}`);
export const cancelAgent = (id: string) => request<AgentRun>(`/agent/runs/${id}/cancel`, 'POST');
export async function latestAgentRun(id: string): Promise<AgentRun | null> { const response=await fetch(`/api/conversations/${id}/agent-runs/latest`); return response.status===204?null:jsonResponse(response); }
export async function streamAgent(body: {executionId: string; conversationId: string; expectedVersion: number; request?: TravelRequest; instruction?: string}, signal: AbortSignal, onEvent: (event: AgentEvent) => void) {
  const response=await fetch('/api/agent/runs/stream',{method:'POST',headers:{'Content-Type':'application/json',Accept:'text/event-stream'},body:JSON.stringify(body),signal});
  if(!response.ok){await jsonResponse(response);return;}
  if(!response.body)throw new Error('无法读取智能体执行流。');
  const reader=response.body.getReader(),decoder=new TextDecoder();let buffer='',terminated=false;
  function accept(frame:string){const data=frame.split('\n').filter(s=>s.startsWith('data:')).map(s=>s.slice(5).trimStart()).join('\n');if(!data)return;const event=JSON.parse(data) as AgentEvent;onEvent(event);if(event.type==='done'||event.type==='error')terminated=true;}
  try{while(true){const {value,done}=await reader.read();buffer+=decoder.decode(value,{stream:!done});buffer=buffer.replace(/\r\n/g,'\n');let boundary;while((boundary=buffer.indexOf('\n\n'))>=0){accept(buffer.slice(0,boundary));buffer=buffer.slice(boundary+2);}if(done)break;}if(buffer.trim())accept(buffer);if(!terminated)throw new Error('执行连接中断，请查看保留的执行记录或重新开始。');}
  finally{reader.releaseLock();}
}
