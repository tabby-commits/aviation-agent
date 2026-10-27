import React, { useCallback, useEffect, useState } from "react";
import { useParams, useNavigate, useLocation } from "react-router-dom";
import { message as antdMessage } from "antd";
import { RobotOutlined } from "@ant-design/icons";
import AgentChatHistory from "./agentChatView/AgentChatHistory.tsx";
import AgentChatInput from "./agentChatView/AgentChatInput.tsx";
import {
  createChatMessage,
  createChatSession,
  getChatMessagesBySessionId,
  getChatSession,
} from "../../api/api.ts";
import { useAgents } from "../../hooks/useAgents.ts";
import { useChatSessions } from "../../hooks/useChatSessions.ts";
import { API_ORIGIN } from "../../api/http.ts";
import EmptyAgentChatView from "./agentChatView/EmptyAgentChatView.tsx";
import type { ChatMessageVO, ReportStep, SseMessage, SseMessageType } from "../../types";

const AgentChatView: React.FC = () => {
  const { chatSessionId } = useParams<{ chatSessionId: string }>();
  const navigate = useNavigate();
  const { state } = useLocation();
  const [loading, setLoading] = useState(false);
  const { agents } = useAgents();
  const { refreshChatSessions } = useChatSessions();

  const [messages, setMessages] = useState<ChatMessageVO[]>([]);

  const addMessage = (message: ChatMessageVO) => {
    setMessages((prevMessages) => [...prevMessages, message]);
  };

  const [agentId, setAgentId] = useState<string>("");
  const [sessionTitle, setSessionTitle] = useState<string>("");

  const getChatMessages = useCallback(async () => {
    if (!chatSessionId) {
      return;
    }
    const resp = await getChatMessagesBySessionId(chatSessionId);
    setMessages(resp.chatMessages);

    const fetchData = async () => {
      const resp = await getChatSession(chatSessionId);
      // setChatSession(resp.chatSession);
      setAgentId(resp.chatSession.agentId);
      setSessionTitle(resp.chatSession.title ?? "");
    };
    fetchData().then();
  }, [chatSessionId]);

  useEffect(() => {
    if (!chatSessionId) {
      return;
    }
    getChatMessages().then();
  }, [chatSessionId, getChatMessages]);

  const handleSendMessage = async (value: string | { text: string }) => {
    // 处理 Sender 组件可能传递的不同格式
    const message = typeof value === "string" ? value : value.text;

    console.log(message);

    if (message || message?.trim()) {
      // 新一轮提问清空上一轮的过程步骤
      setReportSteps([]);
    }
    if (!message || !message.trim()) return;

    // 如果没有 chatSessionId，创建新会话
    if (!chatSessionId) {
      if (!agentId) {
        antdMessage.warning("请先创建一个情报智能体");
        return;
      }
      setLoading(true);
      try {
        const response = await createChatSession({
          agentId: agentId,
          title: message.slice(0, 20),
        });
        // 刷新聊天会话列表
        await refreshChatSessions();
        // 导航到新创建的会话
        navigate(`/chat/${response.chatSessionId}`, {
          replace: true,
          // 携带初始化消息
          state: {
            init: false,
            initMessage: message,
          },
        });
      } catch (error) {
        console.error("创建研判会话失败:", error);
        antdMessage.error("创建研判会话失败，请重试");
      } finally {
        setLoading(false);
      }
    } else {
      if (state?.init) {
        console.log("init", state.initMessage);
        await createChatMessage({
          agentId: agentId ?? "",
          sessionId: chatSessionId,
          role: "user",
          content: state.initMessage ?? "",
        });
      } else {
        console.log("ask", message);
        await createChatMessage({
          agentId: agentId ?? "",
          sessionId: chatSessionId,
          role: "user",
          content: message,
        });
      }
      await getChatMessages();
    }
  };

  const [displayAgentStatus, setDisplayAgentStatus] = useState<boolean>(false);
  const [agentStatusText, setAgentStatusText] = useState("");
  const [agentStatusType, setAgentStatusType] = useState<
    SseMessageType | undefined
  >(undefined);
  const [reportSteps, setReportSteps] = useState<ReportStep[]>([]);

  useEffect(() => {
    // sse 连接处理, 不是对话消息不开连接
    if (!chatSessionId) {
      return;
    }
    // withCredentials: 跨域 5173→8080 必须显式携带会话 Cookie，否则登录后 SSE 连不上
    const es = new EventSource(
      `${API_ORIGIN}/sse/connect/${chatSessionId}`,
      { withCredentials: true },
    );
    es.onmessage = (event) => {
      console.log("Received message:", event.data);
    };
    es.onerror = (error) => {
      console.error("SSE error:", error);
    };

    es.addEventListener("message", (event) => {
      // 解析 JSON
      const message = JSON.parse(event.data) as SseMessage;
      if (message.type === "AI_GENERATED_CONTENT") {
        // 将 AI 生成的内容存到 messages 中
        addMessage(message.payload.message);
      } else if (message.type === "AI_PLANNING") {
        setDisplayAgentStatus(true);
        setAgentStatusText(message.payload.statusText);
        setAgentStatusType("AI_PLANNING");
      } else if (message.type === "AI_THINKING") {
        setDisplayAgentStatus(true);
        setAgentStatusText(message.payload.statusText);
        setAgentStatusType("AI_THINKING");
      } else if (message.type === "AI_EXECUTING") {
        setDisplayAgentStatus(true);
        setAgentStatusText(message.payload.statusText);
        setAgentStatusType("AI_EXECUTING");
      } else if (message.type === "REPORT_STAGE") {
        setDisplayAgentStatus(true);
        setAgentStatusText(message.payload.statusText);
        setAgentStatusType("REPORT_STAGE");
      } else if (message.type === "REPORT_STEP") {
        // 报告流水线单步事件：Agent 调用、检索、抓取、核验、计算与汇总过程
        setDisplayAgentStatus(true);
        setAgentStatusText(message.payload.statusText);
        setAgentStatusType("REPORT_STEP");
        setReportSteps((prev) => {
          const incoming: ReportStep = {
            step: message.payload.step ?? prev.length + 1,
            toolName: message.payload.toolName ?? "",
            taskId: message.payload.taskId,
            statusText: message.payload.statusText,
            note: message.payload.detail?.note,
            data: message.payload.detail?.data,
          };
          const rest = prev.filter((s) => s.step !== incoming.step);
          return [...rest, incoming].sort((a, b) => a.step - b.step).slice(-24);
        });
      } else if (message.type === "AI_DONE") {
        setDisplayAgentStatus(false);
        setAgentStatusText("");
        setAgentStatusType(undefined);
      } else {
        // 未适配的事件类型（如 AGENTIC_*）忽略，保持向后兼容
        console.log("Ignored SSE message type:", message.type);
      }
    });

    es.addEventListener("init", (event) => {
      console.log("Received init message:", event.data);
    });

    return () => {
      console.log("Closing SSE connection.");
      es.close();
    };
  }, [chatSessionId]);

  // 如果没有 chatSessionId，显示提示界面
  if (!chatSessionId) {
    return (
      <EmptyAgentChatView
        agents={agents}
        loading={loading}
        handleSendMessage={handleSendMessage}
      />
    );
  }

  // 如果有 chatSessionId，显示正常的聊天界面
  const activeAgentName = agents.find((a) => a.id === agentId)?.name;

  return (
    <div className="flex flex-col h-full min-w-0">
      {/* 会话信息条 */}
      <div className="shrink-0 border-b border-line/80 bg-white/70 backdrop-blur px-6 py-2.5">
        <div className="max-w-4xl mx-auto flex items-center gap-3 min-w-0">
          <span className="tele text-signal-deep shrink-0">Session</span>
          <span className="text-[13.5px] font-medium text-ink truncate">
            {sessionTitle || "研判任务"}
          </span>
          {activeAgentName && (
            <span className="shrink-0 inline-flex items-center gap-1.5 text-[11px] text-ink-2 border border-line rounded-full px-2.5 py-0.5 bg-white">
              <RobotOutlined className="text-signal" />
              {activeAgentName}
            </span>
          )}
          <span className="tele text-slate-400 ml-auto shrink-0 hidden sm:inline">
            ID {chatSessionId.slice(0, 8)}
          </span>
        </div>
      </div>
      <AgentChatHistory
        messages={messages}
        displayAgentStatus={displayAgentStatus}
        agentStatusText={agentStatusText}
        agentStatusType={agentStatusType}
        reportSteps={reportSteps}
      />
      <div className="shrink-0 px-6 pb-5 pt-3">
        <div className="max-w-4xl mx-auto">
          <AgentChatInput onSend={handleSendMessage} />
          <div className="tele text-center text-slate-400 mt-2 select-none">
            Evidence-Based · 关键结论均可回查来源
          </div>
        </div>
      </div>
    </div>
  );
};

export default AgentChatView;
