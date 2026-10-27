import React from "react";
import { Button, Dropdown, Modal } from "antd";
import type { MenuProps } from "antd";
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  MoreOutlined,
  RobotOutlined,
} from "@ant-design/icons";
import type { AgentVO } from "../../api/api.ts";
import { formatDateTime } from "../../utils";

interface AgentTabContentProps {
  agents: AgentVO[];
  onCreateAgentClick: () => void;
  onSelectAgent: (agentId: string) => void;
  onEditAgent?: (agent: AgentVO) => void;
  onDeleteAgent?: (agentId: string) => void;
}

const AgentTabContent: React.FC<AgentTabContentProps> = ({
  agents,
  onCreateAgentClick,
  onSelectAgent,
  onEditAgent,
  onDeleteAgent,
}) => {
  // 创建右键菜单
  const getContextMenuItems = (agent: AgentVO): MenuProps["items"] => {
    const items: MenuProps["items"] = [];

    if (onEditAgent) {
      items.push({
        key: "edit",
        label: "编辑",
        icon: <EditOutlined />,
        onClick: (e) => {
          e.domEvent.stopPropagation();
          onEditAgent(agent);
        },
      });
    }

    if (onDeleteAgent) {
      items.push({
        key: "delete",
        label: "删除",
        icon: <DeleteOutlined />,
        danger: true,
        onClick: (e) => {
          e.domEvent.stopPropagation();
          Modal.confirm({
            title: "确定要删除这个情报智能体吗？",
            content: "删除后将无法恢复",
            okText: "确定",
            cancelText: "取消",
            okType: "danger",
            onOk: () => {
              onDeleteAgent(agent.id);
            },
          });
        },
      });
    }

    return items;
  };

  return (
    <div className="flex flex-col h-full">
      <Button
        color="geekblue"
        variant="filled"
        icon={<PlusOutlined />}
        onClick={onCreateAgentClick}
        className="w-full font-medium"
      >
        新建情报智能体
      </Button>
      <div className="h-3" />
      <div className="flex-1 overflow-y-auto workspace-scrollbar console-list p-1.5">
        {agents.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full text-slate-500">
            <RobotOutlined className="text-3xl mb-2 text-slate-600" />
            <p className="text-sm text-slate-400">暂无情报智能体</p>
            <p className="tele mt-2 text-slate-500">CONFIGURE AN AGENT</p>
          </div>
        ) : (
          <div className="space-y-1.5">
            {agents.map((agent) => {
              const menuItems = getContextMenuItems(agent);
              const hasMenu = menuItems && menuItems.length > 0;
              return (
                <div
                  key={agent.id}
                  onClick={() => onSelectAgent(agent.id)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") onSelectAgent(agent.id);
                  }}
                  tabIndex={0}
                  className="console-row w-full px-3 py-2.5 cursor-pointer group relative"
                >
                  <div className="flex items-start gap-3">
                    <div className="icon-tile w-8 h-8 text-flare mt-0.5">
                      <RobotOutlined />
                    </div>
                    <div className="flex-1 min-w-0">
                      <div className="font-medium text-slate-100 truncate text-[13.5px]">
                        {agent.name}
                      </div>
                      {agent.description && (
                        <div className="text-xs text-slate-400 mt-0.5 line-clamp-1">
                          {agent.description}
                        </div>
                      )}
                      {agent.updatedAt && (
                        <div className="tele mt-1.5 text-slate-500" style={{ fontSize: 9 }}>
                          {formatDateTime(agent.updatedAt)}
                        </div>
                      )}
                    </div>
                    {hasMenu && (
                      <div
                        onClick={(e) => e.stopPropagation()}
                        onContextMenu={(e) => e.stopPropagation()}
                        className="opacity-0 group-hover:opacity-100 transition-opacity shrink-0"
                      >
                        <Dropdown
                          menu={{ items: menuItems }}
                          trigger={["contextMenu", "click"]}
                          placement="bottomRight"
                        >
                          <Button
                            type="text"
                            size="small"
                            icon={<MoreOutlined />}
                            onClick={(e) => e.stopPropagation()}
                            className="text-slate-400 hover:text-slate-200"
                          />
                        </Dropdown>
                      </div>
                    )}
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

export default AgentTabContent;
