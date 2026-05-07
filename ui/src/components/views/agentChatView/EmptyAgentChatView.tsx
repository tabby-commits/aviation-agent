import React, { useState, useMemo } from "react";
import { Card, Space, Typography, Select } from "antd";
import {
  BulbOutlined,
  MessageOutlined,
  RobotOutlined,
  DownOutlined,
} from "@ant-design/icons";
import { Sender } from "@ant-design/x";
import { useNavigate } from "react-router-dom";
import {
  type AgentVO,
  createChatMessage,
  createChatSession,
} from "../../../api/api.ts";
import { getAgentEmoji } from "../../../utils";
import { useChatSessions } from "../../../hooks/useChatSessions.ts";

const { Title, Text } = Typography;

interface DefaultAgentChatViewProps {
  handleSendMessage: (message: string) => void;
  loading: boolean;
  agents: AgentVO[];
}

const EmptyAgentChatView: React.FC<DefaultAgentChatViewProps> = ({
  loading,
  agents,
}) => {
  const [message, setMessage] = useState("");
  const [selectedAgentId, setSelectedAgentId] = useState<string | null>(null);

  const navigate = useNavigate();
  const { refreshChatSessions } = useChatSessions();

  // 为每个 agent 生成 emoji
  const agentsWithEmoji = useMemo(() => {
    return agents.map((agent) => ({
      ...agent,
      emoji: getAgentEmoji(agent.id),
    }));
  }, [agents]);

  // 计算实际选中的 agent ID（如果用户没有选择，则使用默认的第一个）
  const effectiveAgentId = useMemo(() => {
    if (selectedAgentId) {
      return selectedAgentId;
    }
    return agents.length > 0 ? agents[0].id : null;
  }, [selectedAgentId, agents]);

  return (
    <div className="flex flex-col h-full min-w-0">
      {/* Agent 选择器 - 顶部 */}
      {agents.length > 0 && (
        <div className="border-b border-slate-200/80 bg-white/90 px-6 py-3">
          <div className="max-w-4xl mx-auto flex items-center justify-start">
            <Select
              value={effectiveAgentId}
              onChange={(value) => setSelectedAgentId(value)}
              style={{ width: 200 }}
              className="agent-selector"
              suffixIcon={<DownOutlined className="text-gray-400" />}
              placeholder="选择情报智能体"
              optionRender={(option) => (
                <div className="flex items-center gap-2">
                  <span className="text-lg">
                    {agentsWithEmoji.find((a) => a.id === option.value)?.emoji}
                  </span>
                  <span className="text-sm">{option.label}</span>
                </div>
              )}
              options={agentsWithEmoji.map((agent) => ({
                value: agent.id,
                label: agent.name,
              }))}
            />
          </div>
        </div>
      )}
      <div className="flex-1 flex items-center justify-center p-6">
        <div className="max-w-2xl w-full space-y-6">
          <div className="text-center mb-8">
            <Title level={2} className="mb-2">
              启动情报研判
            </Title>
            <Text type="secondary" className="text-base">
              选择情报智能体，围绕航天科技动态、任务态势和资料线索展开分析
            </Text>
          </div>

          <Space direction="vertical" size="large" className="w-full">
            <Card
              hoverable
              className="cursor-pointer transition-all hover:-translate-y-0.5"
            >
              <Space size="middle">
                <div className="w-12 h-12 rounded-lg bg-blue-50 border border-blue-100 flex items-center justify-center">
                  <RobotOutlined className="text-blue-600 text-xl" />
                </div>
                <div>
                  <Title level={5} className="mb-1">
                    任务态势分析
                  </Title>
                  <Text type="secondary">
                    研判航天任务进展、机构动向和技术路线变化
                  </Text>
                </div>
              </Space>
            </Card>

            <Card
              hoverable
              className="cursor-pointer transition-all hover:-translate-y-0.5"
            >
              <Space size="middle">
                <div className="w-12 h-12 rounded-lg bg-emerald-50 border border-emerald-100 flex items-center justify-center">
                  <BulbOutlined className="text-emerald-600 text-xl" />
                </div>
                <div>
                  <Title level={5} className="mb-1">
                    情报库检索
                  </Title>
                  <Text type="secondary">
                    基于已归档材料检索证据，辅助形成分析结论
                  </Text>
                </div>
              </Space>
            </Card>

            <Card
              hoverable
              className="cursor-pointer transition-all hover:-translate-y-0.5"
            >
              <Space size="middle">
                <div className="w-12 h-12 rounded-lg bg-amber-50 border border-amber-100 flex items-center justify-center">
                  <MessageOutlined className="text-amber-600 text-xl" />
                </div>
                <div>
                  <Title level={5} className="mb-1">
                    快速发起研判
                  </Title>
                  <Text type="secondary">
                    输入情报问题、目标对象或分析任务，立即创建研判记录
                  </Text>
                </div>
              </Space>
            </Card>
          </Space>
        </div>
      </div>
      <div className="border-t border-slate-200/80 bg-white/90">
        {/* 输入框 */}
        <div className="max-w-4xl mx-auto px-6 pb-4 pt-4">
          <Sender
            onSubmit={async () => {
              if (!effectiveAgentId) return;
              console.log("发送消息", message);
              const response = await createChatSession({
                agentId: effectiveAgentId,
                title: message.slice(0, 20),
              });
              await createChatMessage({
                sessionId: response.chatSessionId ?? "",
                content: message,
                role: "user",
                agentId: effectiveAgentId,
              });
              // 刷新聊天会话列表
              await refreshChatSessions();
              setMessage("");
              navigate(
                `/chat/${response.chatSessionId}`,
              );
            }}
            value={message}
            loading={loading}
            placeholder="输入航天科技情报问题或研判任务..."
            onChange={(value) => {
              setMessage(value);
            }}
          />
        </div>
      </div>
    </div>
  );
};

export default EmptyAgentChatView;
