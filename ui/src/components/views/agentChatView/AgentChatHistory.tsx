import React, { useState, useRef, useEffect, useCallback } from "react";
import { Bubble } from "@ant-design/x";
import XMarkdown from "@ant-design/x-markdown";
import {
  ToolOutlined,
  CheckCircleOutlined,
  DownOutlined,
  RightOutlined,
} from "@ant-design/icons";
import type { ChatMessageVO, ReportStep, SseMessageType, ToolCall, ToolResponse } from "../../../types";
import OrbitMark from "../../OrbitMark.tsx";
import ReportView from "./ReportView.tsx";

interface AgentChatHistoryProps {
  messages: ChatMessageVO[];
  displayAgentStatus?: boolean;
  agentStatusText?: string;
  agentStatusType?: SseMessageType;
  reportSteps?: ReportStep[];
}

// 报告流水线步骤时间线：显示每个 Agent 调用、检索、核验与计算步骤，可展开原始输入输出
const ReportStepTimeline: React.FC<{ steps: ReportStep[] }> = ({ steps }) => {
  const [openStep, setOpenStep] = useState<number | null>(null);
  if (steps.length === 0) return null;
  return (
    <div className="mb-3 rounded-xl border border-line/80 bg-white/70 backdrop-blur px-4 py-3">
      <p className="tele text-[10px] text-slate-400 mb-2">PIPELINE STEPS · {steps.length}</p>
      <ol className="space-y-1.5 max-h-72 overflow-y-auto workspace-scrollbar pr-1">
        {steps.map((step) => (
          <li key={step.step} className="text-xs">
            <div
              className="flex items-start gap-2 cursor-pointer group"
              onClick={() => setOpenStep(openStep === step.step ? null : step.step)}
            >
              <span className="tele text-signal shrink-0 w-8 text-right">{String(step.step).padStart(2, "0")}</span>
              <span className="tele text-slate-400 shrink-0 w-28 truncate" title={step.toolName}>
                {step.toolName}
              </span>
              <span className="text-slate-600 min-w-0 flex-1">
                {step.note || step.statusText}
                {step.taskId && (
                  <span className="tele text-slate-300 ml-1.5">[{step.taskId}]</span>
                )}
              </span>
              {step.data && (
                <span className="tele text-slate-300 shrink-0 group-hover:text-signal">
                  {openStep === step.step ? "−" : "+"}
                </span>
              )}
            </div>
            {openStep === step.step && step.data && (
              <pre className="term-pre whitespace-pre-wrap break-words mt-1 ml-10 text-[11px]">
                {step.data}
              </pre>
            )}
          </li>
        ))}
      </ol>
    </div>
  );
};

// 工具调用展示组件（终端 chip，用于 assistant 消息内）
const ToolCallDisplay: React.FC<{ toolCall: ToolCall }> = ({ toolCall }) => {
  let parsedArgs: Record<string, unknown> = {};
  try {
    parsedArgs = JSON.parse(toolCall.arguments) as Record<string, unknown>;
  } catch {
    // 如果解析失败，使用原始字符串
  }

  const argCount = Object.keys(parsedArgs).length;
  const argPreview = argCount > 0
    ? Object.keys(parsedArgs).slice(0, 2).join(", ") + (argCount > 2 ? "..." : "")
    : toolCall.arguments.slice(0, 50) + (toolCall.arguments.length > 50 ? "..." : "");

  return (
    <span className="term-chip">
      <ToolOutlined style={{ fontSize: 10, color: "#7e96c0" }} />
      <span className="term-name">{toolCall.name}</span>
      {argPreview && (
        <>
          <span style={{ color: "#3d5478" }}>·</span>
          <span className="truncate max-w-[220px]" style={{ color: "#8ba2c6" }}>
            {argPreview}
          </span>
        </>
      )}
    </span>
  );
};

// 工具响应展示组件（可折叠，展开为终端读数）
const ToolResponseDisplay: React.FC<{ toolResponse: ToolResponse }> = ({
  toolResponse,
}) => {
  const [expanded, setExpanded] = useState(false);

  let parsedData: unknown = null;
  let isJson = false;
  let dataPreview = "";

  try {
    parsedData = JSON.parse(toolResponse.responseData);
    isJson = true;
    const jsonStr = JSON.stringify(parsedData);
    dataPreview = jsonStr.length > 100 ? jsonStr.slice(0, 100) + "..." : jsonStr;
  } catch {
    dataPreview = toolResponse.responseData.length > 100
      ? toolResponse.responseData.slice(0, 100) + "..."
      : toolResponse.responseData;
  }

  return (
    <div className="my-1.5 text-xs">
      <div className="resp-chip" onClick={() => setExpanded(!expanded)}>
        {expanded ? (
          <DownOutlined className="text-slate-400" />
        ) : (
          <RightOutlined className="text-slate-400" />
        )}
        <CheckCircleOutlined className="text-telemetry" />
        <span className="font-medium" style={{ fontFamily: "var(--font-tele)", fontSize: 11 }}>
          {toolResponse.name}
        </span>
        <span className="text-slate-300">·</span>
        <span className="text-slate-500 truncate flex-1">{dataPreview}</span>
      </div>
      {expanded && (
        <div className="ml-5 mt-2">
          {isJson ? (
            <pre className="term-pre whitespace-pre-wrap break-words">
              {JSON.stringify(parsedData, null, 2)}
            </pre>
          ) : (
            <div className="term-pre whitespace-pre-wrap break-words">
              {toolResponse.responseData}
            </div>
          )}
        </div>
      )}
    </div>
  );
};

const AgentChatHistory: React.FC<AgentChatHistoryProps> = ({
  messages,
  displayAgentStatus = false,
  agentStatusText = "",
  agentStatusType,
  reportSteps = [],
}) => {
  // 滚动容器引用
  const scrollContainerRef = useRef<HTMLDivElement>(null);
  // 是否允许自动滚动（用户是否接近底部）
  const [isNearBottom, setIsNearBottom] = useState(true);
  // 容错阈值（像素）
  const SCROLL_THRESHOLD = 20;
  // 上一次消息数量，用于检测新消息
  const prevMessagesLengthRef = useRef(messages.length);

  // 检查是否接近底部
  const checkIfNearBottom = useCallback(() => {
    const container = scrollContainerRef.current;
    if (!container) return false;

    const { scrollTop, clientHeight, scrollHeight } = container;
    const distanceFromBottom = scrollHeight - scrollTop - clientHeight;
    return distanceFromBottom <= SCROLL_THRESHOLD;
  }, []);

  // 滚动到底部
  const scrollToBottom = useCallback(() => {
    const container = scrollContainerRef.current;
    if (!container) return;

    // 使用 requestAnimationFrame 确保 DOM 更新完成后再滚动
    requestAnimationFrame(() => {
      if (container) {
        container.scrollTop = container.scrollHeight;
      }
    });
  }, []);

  // 处理滚动事件，实时更新是否接近底部的状态
  const handleScroll = useCallback(() => {
    const nearBottom = checkIfNearBottom();
    setIsNearBottom(nearBottom);
  }, [checkIfNearBottom]);

  // 监听滚动事件
  useEffect(() => {
    const container = scrollContainerRef.current;
    if (!container) return;

    // 初始化时检查是否在底部（延迟执行以避免同步 setState）
    const initTimer = setTimeout(() => {
      setIsNearBottom(checkIfNearBottom());
    }, 0);

    container.addEventListener("scroll", handleScroll, { passive: true });

    return () => {
      clearTimeout(initTimer);
      container.removeEventListener("scroll", handleScroll);
    };
  }, [handleScroll, checkIfNearBottom]);

  // 监听消息变化，决定是否自动滚动
  useEffect(() => {
    const hasNewMessage = messages.length > prevMessagesLengthRef.current;
    prevMessagesLengthRef.current = messages.length;

    // 如果有新消息且用户接近底部，则自动滚动
    if (hasNewMessage && isNearBottom) {
      scrollToBottom();
    }
  }, [messages, isNearBottom, scrollToBottom]);

  // 当 displayAgentStatus 变化时，如果用户接近底部，也自动滚动
  useEffect(() => {
    if (displayAgentStatus && isNearBottom) {
      scrollToBottom();
    }
  }, [displayAgentStatus, isNearBottom, scrollToBottom]);

  // 状态标签（双语遥测读数）
  const getStatusLabel = () => {
    switch (agentStatusType) {
      case "AI_PLANNING":
        return { cn: "规划中", en: "PLANNING" };
      case "AI_THINKING":
        return { cn: "思考中", en: "THINKING" };
      case "AI_EXECUTING":
        return { cn: "执行中", en: "EXECUTING" };
      default:
        return { cn: "处理中", en: "WORKING" };
    }
  };

  const statusLabel = getStatusLabel();

  return (
    <div
      ref={scrollContainerRef}
      className="flex-1 px-6 py-6 overflow-y-scroll workspace-scrollbar"
    >
      <div className="max-w-4xl mx-auto">
      {messages.map((message) => {
        return (
          <div className="mb-4 msg-enter" key={message.id}>
            {/* Assistant 消息 */}
            {message.role === "assistant" && (
              <Bubble
                avatar={
                  <span className="inline-flex mt-0.5">
                    <OrbitMark size={30} />
                  </span>
                }
                content={
                  <div className="w-full">
                    {/* 工具调用展示 */}
                    {message.metadata?.toolCalls &&
                      message.metadata.toolCalls.length > 0 && (
                        <div className="mb-2 flex flex-wrap gap-1.5">
                          {message.metadata.toolCalls.map((toolCall) => (
                            <ToolCallDisplay key={toolCall.id} toolCall={toolCall} />
                          ))}
                        </div>
                      )}
                    {/* 消息内容 */}
                    {message.content && (
                      <div>
                        <XMarkdown
                          streaming={{ enableAnimation: false, hasNextChunk: true }}
                        >
                          {message.content}
                        </XMarkdown>
                      </div>
                    )}
                    {message.metadata?.reportId && (
                      <ReportView reportId={message.metadata.reportId} />
                    )}
                  </div>
                }
                placement="start"
              />
            )}

            {/* Tool 消息 - 简洁展示，不使用气泡 */}
            {message.role === "tool" && message.metadata?.toolResponse && (
              <div className="flex justify-start">
                <div className="max-w-[85%]">
                  <ToolResponseDisplay toolResponse={message.metadata.toolResponse} />
                </div>
              </div>
            )}

            {/* User 消息 */}
            {message.role === "user" && (
              <Bubble content={message.content} placement="end" />
            )}

            {/* System 消息 */}
            {message.role === "system" && (
              <div className="flex justify-center">
                <div className="px-3 py-1 border border-line bg-white text-slate-500 rounded-full flex items-center gap-2">
                  <span className="tele" style={{ fontSize: 9 }}>SYS</span>
                  <span className="text-xs">{message.content}</span>
                </div>
              </div>
            )}
          </div>
        );
      })}
      {(reportSteps.length > 0 || displayAgentStatus) && (
        <div className="mb-3 msg-enter">
          {reportSteps.length > 0 && <ReportStepTimeline steps={reportSteps} />}
          {displayAgentStatus && (
            <div className="status-strip inline-flex items-center gap-2.5 px-3.5 py-2 max-w-full">
              <span
                className="tele-blink inline-block w-1.5 h-1.5 rounded-full shrink-0"
                style={{ background: "#ffb25c" }}
              />
              <span className="tele text-flare shrink-0">
                {statusLabel.en}
              </span>
              <span className="text-[12px] font-medium text-slate-200 shrink-0">
                {statusLabel.cn}
              </span>
              <span className="text-[12px] text-slate-400 truncate">
                {agentStatusText}
              </span>
            </div>
          )}
        </div>
      )}
      </div>
    </div>
  );
};

export default AgentChatHistory;
