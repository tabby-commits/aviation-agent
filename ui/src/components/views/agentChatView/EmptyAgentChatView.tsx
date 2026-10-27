import React, { useState, useMemo } from "react";
import { Select } from "antd";
import { DownOutlined } from "@ant-design/icons";
import { Sender } from "@ant-design/x";
import { useNavigate } from "react-router-dom";
import {
  type AgentVO,
  createChatMessage,
  createChatSession,
} from "../../../api/api.ts";
import { useChatSessions } from "../../../hooks/useChatSessions.ts";
import OrbitMark from "../../OrbitMark.tsx";

interface DefaultAgentChatViewProps {
  handleSendMessage: (message: string) => void;
  loading: boolean;
  agents: AgentVO[];
}

/** 竞争力分析框架：四个一级维度与指标编号区间（对应论文表2） */
const FRAMEWORK_DIMS = [
  { code: "D1", name: "战略重要性", range: "01–05", note: "政策强度 · 安全关联 · 项目部署" },
  { code: "D2", name: "技术前瞻性", range: "06–08", note: "清单优先级 · 范式变革" },
  { code: "D3", name: "科技创新能力", range: "09–18", note: "论文 · 参数 · 机构 · 标准" },
  { code: "D4", name: "产业成熟度", range: "19–28", note: "工程产品 · 供应链 · 市场规模" },
];

/** Agent Loop 七环节（对应论文 3.3.1） */
const AGENT_LOOP = [
  "任务理解",
  "计划生成",
  "Skill 加载",
  "并行检索",
  "证据观察",
  "分析校验",
  "综合生成",
];

/** 任务模板：填入输入框后由用户确认发送 */
const MISSION_TEMPLATES = [
  {
    label: "低轨星座 · 创新能力对比",
    prompt:
      "对比中美低轨卫星星座网络的科技创新能力，重点关注论文产出规模、高被引表现与国际合作结构",
  },
  {
    label: "可复用火箭 · 产业成熟度",
    prompt:
      "分析中美可复用火箭的产业成熟度，聚焦回收复用记录、发射频次与产品迭代速度",
  },
  {
    label: "航天政策 · 战略重要性",
    prompt:
      "梳理近五年中美航天政策对低轨卫星星座的战略定位、重大项目部署与资源安排",
  },
];

const EmptyAgentChatView: React.FC<DefaultAgentChatViewProps> = ({
  loading,
  agents,
}) => {
  const [message, setMessage] = useState("");
  const [selectedAgentId, setSelectedAgentId] = useState<string | null>(null);

  const navigate = useNavigate();
  const { refreshChatSessions } = useChatSessions();

  // 计算实际选中的 agent ID（如果用户没有选择，则使用默认的第一个）
  const effectiveAgentId = useMemo(() => {
    if (selectedAgentId) {
      return selectedAgentId;
    }
    return agents.length > 0 ? agents[0].id : null;
  }, [selectedAgentId, agents]);

  const effectiveAgentName = useMemo(() => {
    return agents.find((a) => a.id === effectiveAgentId)?.name ?? null;
  }, [agents, effectiveAgentId]);

  const handleSubmit = async () => {
    if (!effectiveAgentId || !message.trim()) return;
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
    navigate(`/chat/${response.chatSessionId}`);
  };

  return (
    <div className="flex flex-col h-full min-w-0">
      <div className="flex-1 overflow-y-auto workspace-scrollbar">
        <div className="relative max-w-4xl mx-auto px-8 pt-14 pb-10">
          {/* 背景装饰：大型轨道参照线 */}
          <div
            className="pointer-events-none absolute -top-10 -right-16 opacity-[0.55] hidden lg:block"
            aria-hidden="true"
          >
            <svg width="380" height="380" viewBox="0 0 48 48" fill="none">
              <g transform="rotate(-24 24 24)">
                <ellipse
                  cx="24"
                  cy="24"
                  rx="20"
                  ry="10.5"
                  stroke="#c9d6e8"
                  strokeWidth="0.28"
                  strokeDasharray="1.2 1.2"
                />
                <ellipse
                  cx="24"
                  cy="24"
                  rx="14"
                  ry="7.2"
                  stroke="#d8e2ef"
                  strokeWidth="0.24"
                />
                <circle cx="44" cy="24" r="0.9" fill="#e8590c" />
              </g>
              <circle cx="24" cy="24" r="2.2" fill="#f3c3a6" />
            </svg>
          </div>

          {/* 简报头 */}
          <div className="flex items-center gap-2.5 select-none">
            <OrbitMark size={26} />
            <span className="tele text-signal-deep">Mission Brief</span>
            <span className="text-slate-300">·</span>
            <span className="tele text-slate-400">
              Aerospace S&amp;T Intelligence
            </span>
          </div>
          <h1 className="font-display text-[34px] leading-snug font-semibold text-ink tracking-tight mt-4 mb-2">
            启动一次情报研判
          </h1>
          <p className="text-[15px] text-ink-2 max-w-xl leading-relaxed">
            选择执行智能体，在统一的四维二十八指标框架下完成问题分解、
            证据检索与中美对比分析，关键结论均可回查来源。
          </p>

          {/* 任务卡：执行智能体 + 输入 */}
          <div className="panel-surface rounded-xl mt-7 p-5">
            <div className="flex items-center justify-between flex-wrap gap-3">
              <div className="flex items-center gap-3">
                <span className="tele text-slate-400">Executing Agent</span>
                <span className="text-sm font-medium text-ink">
                  执行智能体
                </span>
              </div>
              {agents.length > 0 ? (
                <Select
                  value={effectiveAgentId}
                  onChange={(value) => setSelectedAgentId(value)}
                  style={{ width: 240 }}
                  suffixIcon={<DownOutlined className="text-gray-400" />}
                  placeholder="选择情报智能体"
                  options={agents.map((agent) => ({
                    value: agent.id,
                    label: agent.name,
                  }))}
                />
              ) : (
                <span className="text-xs text-amber-600">
                  暂无可用智能体，请先在左侧「情报智能体」中创建
                </span>
              )}
            </div>
            <div className="mt-3.5">
              <Sender
                onSubmit={handleSubmit}
                value={message}
                loading={loading}
                placeholder={
                  effectiveAgentName
                    ? `向「${effectiveAgentName}」下达研判任务...`
                    : "输入航天科技情报问题或研判任务..."
                }
                onChange={(value) => {
                  setMessage(value);
                }}
              />
            </div>
            <div className="mt-3.5 flex items-start gap-2 flex-wrap">
              <span className="tele text-slate-400 mt-1.5 shrink-0">
                Templates
              </span>
              {MISSION_TEMPLATES.map((tpl) => (
                <button
                  key={tpl.label}
                  type="button"
                  onClick={() => setMessage(tpl.prompt)}
                  className="text-left text-[12.5px] leading-5 px-3 py-1.5 rounded-md border border-line bg-white text-ink-2 cursor-pointer transition-all hover:border-[#f3c3a6] hover:bg-[#fdf0e8] hover:text-signal-deep"
                >
                  {tpl.label}
                </button>
              ))}
            </div>
          </div>

          {/* 竞争力分析框架条 */}
          <div className="mt-8">
            <div className="flex items-baseline gap-3 select-none">
              <span className="tele text-slate-400">
                Framework · 4 Dim / 28 Ind
              </span>
              <span className="text-xs text-slate-400">
                竞争力分析框架
              </span>
            </div>
            <div className="grid grid-cols-2 xl:grid-cols-4 gap-3 mt-3">
              {FRAMEWORK_DIMS.map((dim) => (
                <div
                  key={dim.code}
                  className="panel-surface rounded-lg px-4 py-3.5 group transition-colors hover:border-[#f3c3a6]"
                >
                  <div className="flex items-baseline justify-between">
                    <span className="tele text-signal">{dim.code}</span>
                    <span className="tele text-slate-400" style={{ fontSize: 9 }}>
                      IND {dim.range}
                    </span>
                  </div>
                  <div className="text-[14px] font-semibold text-ink mt-1.5">
                    {dim.name}
                  </div>
                  <div className="text-[11px] text-slate-400 mt-1 leading-relaxed">
                    {dim.note}
                  </div>
                </div>
              ))}
            </div>
          </div>

          {/* Agent Loop 环节条 */}
          <div className="mt-6 select-none">
            <div className="flex items-baseline gap-3">
              <span className="tele text-slate-400">Agent Loop</span>
              <span className="text-xs text-slate-400">推理 — 行动 — 观察</span>
            </div>
            <div className="mt-2.5 flex items-center flex-wrap gap-y-2">
              {AGENT_LOOP.map((step, i) => (
                <React.Fragment key={step}>
                  <span className="inline-flex items-center gap-1.5 text-[12px] text-ink-2">
                    <span className="tele text-telemetry" style={{ fontSize: 9 }}>
                      {String(i + 1).padStart(2, "0")}
                    </span>
                    {step}
                  </span>
                  {i < AGENT_LOOP.length - 1 && (
                    <span className="mx-2 text-slate-300 text-[11px]">→</span>
                  )}
                </React.Fragment>
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

export default EmptyAgentChatView;
