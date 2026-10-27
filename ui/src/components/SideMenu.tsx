import React, { useState } from "react";
import { Tabs, type TabsProps, Dropdown, message } from "antd";
import { LogoutOutlined, UserOutlined } from "@ant-design/icons";
import { useNavigate } from "react-router-dom";
import AgentTabContent from "./tabs/AgentTabContent.tsx";
import AddAgentModal from "./modals/AddAgentModal.tsx";
import ChatTabContent from "./tabs/ChatTabContent.tsx";
import KnowledgeBaseTabContent from "./tabs/KnowledgeBaseTabContent.tsx";
import AddKnowledgeBaseModal from "./modals/AddKnowledgeBaseModal.tsx";
import OrbitMark from "./OrbitMark.tsx";
import { useAgents } from "../hooks/useAgents.ts";
import { useKnowledgeBases } from "../hooks/useKnowledgeBases.ts";
import { logout } from "../api/api.ts";

interface SideMenuProps {
  children?: React.ReactNode;
}

const SideMenu: React.FC<SideMenuProps> = () => {
  const navigate = useNavigate();

  const [isAddAgentModalOpen, setIsAddAgentModalOpen] = useState(false);
  const toggleAddAgentModal = () => {
    setIsAddAgentModalOpen(!isAddAgentModalOpen);
    setEditingAgent(null);
  };

  const [editingAgent, setEditingAgent] = useState<
    import("../api/api.ts").AgentVO | null
  >(null);

  /**
   * 添加知识库模态框状态
   */
  const [isAddKnowledgeBaseModalOpen, setIsAddKnowledgeBaseModalOpen] =
    useState(false);
  const toggleAddKnowledgeBaseModal = () => {
    setIsAddKnowledgeBaseModalOpen(!isAddKnowledgeBaseModalOpen);
  };
  const { agents, createAgentHandle, deleteAgentHandle, updateAgentHandle } =
    useAgents();

  const [activeKey, setActiveKey] = useState(() => {
    if (location.pathname.startsWith("/agent")) return "agent";
    if (location.pathname.startsWith("/knowledge-base")) return "knowledgeBase";
    if (location.pathname.startsWith("/chat")) return "chat";
    return "agent";
  });

  const { knowledgeBases, createKnowledgeBaseHandle } = useKnowledgeBases();

  // 处理标签页切换
  const handleTabChange = (key: string) => {
    setActiveKey(key);
  };

  const tabLabel = (cn: string, en: string) => (
    <span className="select-none inline-flex flex-col leading-tight gap-[2px]">
      <span className="tele" style={{ fontSize: 8.5, opacity: 0.55 }}>
        {en}
      </span>
      <span>{cn}</span>
    </span>
  );

  const items: TabsProps["items"] = [
    {
      key: "agent",
      label: tabLabel("情报智能体", "AGENTS"),
      children: (
        <AgentTabContent
          agents={agents}
          onSelectAgent={() => {}}
          onCreateAgentClick={toggleAddAgentModal}
          onEditAgent={(agent) => {
            setEditingAgent(agent);
            setIsAddAgentModalOpen(true);
          }}
          onDeleteAgent={deleteAgentHandle}
        />
      ),
    },
    {
      key: "chat",
      label: tabLabel("研判记录", "SESSIONS"),
      children: <ChatTabContent />,
    },
    {
      key: "knowledgeBase",
      label: tabLabel("情报库", "ARCHIVES"),
      children: (
        <KnowledgeBaseTabContent
          knowledgeBases={knowledgeBases}
          onCreateKnowledgeBaseClick={toggleAddKnowledgeBaseModal}
          onSelectKnowledgeBase={(knowledgeBaseId) => {
            navigate(`/knowledge-base/${knowledgeBaseId}`);
          }}
        />
      ),
    },
  ];

  return (
    <div className="px-4 flex flex-col h-full">
      {/* 品牌区：轨道徽标 + 台标 */}
      <div className="pt-5 pb-4">
        <div className="flex items-center gap-3 mx-1">
          <OrbitMark size={38} />
          <div className="min-w-0">
            <div className="text-[16px] font-semibold text-white tracking-tight leading-tight select-none">
              航天科技情报工作台
            </div>
          </div>
        </div>
        <div className="tele text-flare/80 mt-3 mx-1 select-none">
          Aerospace Intelligence Console
        </div>
      </div>
      <div className="console-divider" />
      <div className="flex-1 min-h-0 flex flex-col pt-3">
        <Tabs activeKey={activeKey} onChange={handleTabChange} items={items} />
      </div>
      {/* 台尾：登录账号 + 框架状态读数（对应论文四维二十八指标与三类 Skill） */}
      <div className="pb-4">
        <div className="console-divider" />
        <div className="pt-3 mx-1 flex items-center justify-between select-none">
          <Dropdown
            menu={{
              items: [{ key: "logout", icon: <LogoutOutlined />, label: "退出登录" }],
              onClick: async ({ key }) => {
                if (key !== "logout") return;
                try {
                  await logout();
                } catch {
                  // 会话已失效时直接回登录页即可
                }
                message.success("已退出登录");
                window.location.href = "/login";
              },
            }}
          >
            <button className="inline-flex items-center gap-1.5 text-slate-300 hover:text-white transition-colors">
              <UserOutlined />
              <span>admin</span>
            </button>
          </Dropdown>
          <span className="inline-flex items-center gap-1.5">
            <span
              className="tele-blink inline-block w-1.5 h-1.5 rounded-full"
              style={{ background: "#3fa9bc" }}
            />
            <span className="tele text-telemetry">RAG</span>
          </span>
        </div>
      </div>
      <AddAgentModal
        open={isAddAgentModalOpen}
        onClose={toggleAddAgentModal}
        createAgentHandle={createAgentHandle}
        updateAgentHandle={updateAgentHandle}
        editingAgent={editingAgent}
      />
      <AddKnowledgeBaseModal
        open={isAddKnowledgeBaseModalOpen}
        onClose={toggleAddKnowledgeBaseModal}
        createKnowledgeBaseHandle={createKnowledgeBaseHandle}
      />
    </div>
  );
};

export default SideMenu;
