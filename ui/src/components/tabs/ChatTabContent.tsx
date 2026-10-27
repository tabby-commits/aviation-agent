import React, { useMemo } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { Button, Popconfirm } from "antd";
import {
  PlusOutlined,
  MessageOutlined,
  DeleteOutlined,
} from "@ant-design/icons";
import { useChatSessions } from "../../hooks/useChatSessions.ts";
import { useAgents } from "../../hooks/useAgents.ts";

const ChatTabContent: React.FC = () => {
  const navigate = useNavigate();
  const { chatSessionId: activeSessionId } = useParams<{
    chatSessionId: string;
  }>();
  const { chatSessions, loading, deleteChatSession } = useChatSessions();
  const { agents } = useAgents();

  // 创建 agentId 到 agent 的映射
  const agentMap = useMemo(() => {
    const map = new Map<string, string>();
    agents.forEach((agent) => {
      map.set(agent.id, agent.name);
    });
    return map;
  }, [agents]);

  const handleCreateNewChat = () => {
    navigate("/chat");
  };

  const handleSelectChatSession = (chatSessionId: string) => {
    navigate(`/chat/${chatSessionId}`);
  };

  const handleDeleteChatSession = async (chatSessionId: string) => {
    await deleteChatSession(chatSessionId);
  };

  // 格式化标题显示
  const getDisplayTitle = (session: { title?: string; agentId: string }) => {
    if (session.title) {
      return session.title;
    }
    const agentName = agentMap.get(session.agentId);
    return agentName ? `与 ${agentName} 的研判任务` : "新研判任务";
  };

  return (
    <div className="flex flex-col h-full">
      <Button
        color="geekblue"
        variant="filled"
        icon={<PlusOutlined />}
        onClick={handleCreateNewChat}
        className="w-full font-medium"
      >
        新建研判
      </Button>
      <div className="h-3" />
      <div className="flex-1 min-h-0 overflow-y-auto workspace-scrollbar console-list">
        {loading ? (
          <div className="flex flex-col items-center justify-center h-full text-slate-500">
            <p className="text-sm text-slate-400">加载中...</p>
          </div>
        ) : chatSessions.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full text-slate-500">
            <MessageOutlined className="text-3xl mb-2 text-slate-600" />
            <p className="text-sm text-slate-400">暂无研判记录</p>
            <p className="tele mt-2 text-slate-500">START A SESSION</p>
          </div>
        ) : (
          <div className="space-y-1.5 p-1.5">
            {chatSessions.map((session) => {
              const isActive = session.id === activeSessionId;
              return (
                <div
                  key={session.id}
                  onClick={() => handleSelectChatSession(session.id)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") handleSelectChatSession(session.id);
                  }}
                  tabIndex={0}
                  className={`console-row w-full px-3 py-2.5 cursor-pointer group relative ${isActive ? "is-active" : ""}`}
                >
                  <div className="flex items-start gap-3">
                    <div
                      className={`icon-tile w-8 h-8 mt-0.5 ${isActive ? "text-flare" : "text-telemetry"}`}
                    >
                      <MessageOutlined />
                    </div>
                    <div className="flex-1 min-w-0">
                      <div className="font-medium text-slate-100 truncate text-[13.5px]">
                        {getDisplayTitle(session)}
                      </div>
                      <div
                        className="tele mt-1 text-slate-500"
                        style={{ fontSize: 9 }}
                      >
                        ID {session.id.slice(0, 8)}
                      </div>
                    </div>
                    <div onClick={(e) => e.stopPropagation()}>
                      <Popconfirm
                        title="确定要删除这条研判记录吗？"
                        description="删除后将无法恢复"
                        onConfirm={() => handleDeleteChatSession(session.id)}
                        okText="确定"
                        cancelText="取消"
                      >
                        <Button
                          type="text"
                          size="small"
                          icon={<DeleteOutlined />}
                          className="opacity-0 group-hover:opacity-100 transition-opacity shrink-0"
                          onClick={(e) => e.stopPropagation()}
                          danger
                        />
                      </Popconfirm>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
};

export default ChatTabContent;
