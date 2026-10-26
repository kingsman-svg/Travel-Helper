export interface ChatEvent {
  type: 'conversation' | 'status' | 'delta' | 'done' | 'error';
  executionId: string;
  content: string;
  code: string | null;
  timestamp: string;
}

export type ExpenseCategory = 'ACCOMMODATION' | 'FOOD' | 'TRANSPORT' | 'TICKETS' | 'OTHER';
export const categoryLabels: Record<ExpenseCategory, string> = { ACCOMMODATION: '住宿', FOOD: '餐饮', TRANSPORT: '交通', TICKETS: '门票', OTHER: '其他' };
export interface TravelRequest {
  origin: string; destination: string; startDate: string; endDate: string; people: number;
  totalBudget: number; budgetIncludes: ExpenseCategory[]; preferences: string;
}
export interface Cost { category: ExpenseCategory; amount: number; note: string }
export interface Activity { startTime: string; endTime: string; title: string; location: string; transport: string; walkingMinutes: number; costs: Cost[] }
export interface Itinerary { title: string; summary: string; days: { date: string; theme: string; activities: Activity[] }[]; extraCosts: Cost[]; sources: { title: string; url: string; note: string; documentId?: string | null; chunkId?: string | null; verifiedAt?: string | null; origin?: string | null }[]; pendingConfirmations: string[] }
export interface TripDraft {
  request: TravelRequest; itinerary: Itinerary;
  budget: { total: number; allEstimatedCosts: number; remaining: number; overBudget: boolean; currency: string; byCategory: Record<ExpenseCategory, number> };
  schedule: { valid: boolean; conflicts: string[]; walkingMinutesByDate: Record<string, number> };
  toolExecutions: { name: string; trigger: string; summary: string; durationMs: number }[]; promptVersion: string; agentExecutionId?: string | null;
}
export interface Message { id: string | number; role: 'user' | 'assistant'; text: string; error?: boolean; createdAt?: string; citations?: KnowledgeCitation[] }
export interface Conversation { id: string; title: string; version: number; draft: TripDraft | null; savedTripId: string | null; savedVersion: number | null; messages: Message[] }
export interface SavedTrip { id: string; conversationId: string; version: number; draft: TripDraft; savedAt: string }
export interface KnowledgeDocument {
  id: string; city: string; title: string; sourceUrl: string; verifiedAt: string; filename: string; origin: 'CURATED_EXAMPLE' | 'USER_UPLOAD';
  status: 'READY' | 'INDEXING' | 'FAILED'; errorMessage: string | null; chunkCount: number; createdAt: string; indexedAt: string | null; contentHash: string;
}
export interface KnowledgeChunk { id: string; ordinal: number; content: string }
export interface KnowledgeDetail { document: KnowledgeDocument; content: string; chunks: KnowledgeChunk[] }
export interface KnowledgeCitation { documentId: string; chunkId: string; title: string; city: string; sourceUrl: string; verifiedAt: string; origin: string; excerpt: string; score: number }
export interface KnowledgeSearch { city: string; query: string; status: 'MATCHED' | 'EMPTY' | 'NO_MATCH'; message: string; hits: KnowledgeCitation[]; durationMs: number; model: string; dimensions: number }
export interface KnowledgeState { status: 'READY' | 'EMPTY' | 'REBUILD_REQUIRED' | 'UNAVAILABLE'; message: string; configuredModel: string; indexedModel: string | null; modelDigest: string | null; dimensions: number | null; documentCount: number; chunkCount: number; generation: string | null; updatedAt: string | null; busy: boolean }
export interface KnowledgeAnswer { answer: string; status: 'ANSWERED' | 'INSUFFICIENT'; citations: KnowledgeCitation[]; retrieval: KnowledgeSearch }
export interface KnowledgeImport { document: KnowledgeDocument; duplicate: boolean }
export interface AgentEvent { sequence: number; type: 'started' | 'action' | 'retrieval' | 'tool_start' | 'tool_result' | 'itinerary' | 'done' | 'error'; executionId: string; data: Record<string, unknown>; timestamp: string }
export interface AgentRun { id: string; conversationId: string; status: string; code: string | null; message: string | null; startedAt: string; finishedAt: string | null; events: AgentEvent[] }

export interface ProbeResult {
  type: string;
  status: 'passed' | 'failed' | 'unconfigured';
  message: string;
  details: Record<string, unknown>;
  durationMs: number;
  checkedAt: string;
}

export interface IntegrationState {
  configured: boolean;
  label: string;
  status: 'passed' | 'failed' | 'unverified' | 'unconfigured';
  lastProbe: Partial<ProbeResult>;
}

export interface IntegrationResponse {
  integrations: Record<'deepseek' | 'tools' | 'mcp' | 'embedding', IntegrationState>;
  stage: string;
}
