export type MessageType = "user" | "assistant" | "system" | "tool";

export interface KnowledgeBase {
  knowledgeBaseId: string;
  name: string;
  description: string;
}

export interface ToolCall {
  id: string;
  type: string;
  name: string;
  arguments: string;
}

export interface ToolResponse {
  id: string;
  name: string;
  responseData: string;
}

export interface ChatMessageVOMetadata {
  toolCalls?: ToolCall[];
  toolResponse?: ToolResponse;
  reportId?: string;
}

export interface ChatMessageVO {
  id: string;
  sessionId: string;
  role: MessageType;
  content: string;
  metadata?: ChatMessageVOMetadata;
}

export type SseMessageType =
  | "AI_GENERATED_CONTENT"
  | "AI_PLANNING"
  | "AI_THINKING"
  | "AI_EXECUTING"
  | "REPORT_STAGE"
  | "REPORT_STEP"
  | "AI_DONE";

export interface SseMessagePayload {
  message: ChatMessageVO;
  statusText: string;
  done: boolean;
  stage?: string;
  step?: number;
  totalSteps?: number;
  toolName?: string;
  taskId?: string | null;
  /** REPORT_STEP 携带：{note: 人可读摘要, data: 裁剪后的该步输入输出 JSON 字符串} */
  detail?: { note?: string; data?: string };
}

/** 报告流水线的单步过程事件（REPORT_STEP） */
export interface ReportStep {
  step: number;
  toolName: string;
  taskId?: string | null;
  statusText: string;
  note?: string;
  data?: string;
}

export interface SseMessageMetadata {
  chatMessageId: string;
}

export interface SseMessage {
  type: SseMessageType;
  payload: SseMessagePayload;
  metadata: SseMessageMetadata;
}
