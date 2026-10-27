import React from "react";
import { useParams } from "react-router-dom";
import { Button } from "antd";
import { PlusOutlined, DatabaseOutlined } from "@ant-design/icons";
import type { KnowledgeBase } from "../../types";

interface KnowledgeBaseTabContentProps {
  knowledgeBases: KnowledgeBase[];
  onCreateKnowledgeBaseClick?: () => void;
  onSelectKnowledgeBase?: (knowledgeBaseId: string) => void;
}

const KnowledgeBaseTabContent: React.FC<KnowledgeBaseTabContentProps> = ({
  knowledgeBases,
  onCreateKnowledgeBaseClick,
  onSelectKnowledgeBase,
}) => {
  const { knowledgeBaseId: activeKbId } = useParams<{
    knowledgeBaseId: string;
  }>();

  return (
    <div className="flex flex-col h-full">
      <Button
        color="geekblue"
        variant="filled"
        icon={<PlusOutlined />}
        onClick={onCreateKnowledgeBaseClick}
        className="w-full font-medium"
      >
        新建情报库
      </Button>
      <div className="h-3" />
      <div className="flex-1 overflow-y-auto workspace-scrollbar console-list">
        {knowledgeBases.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full text-slate-500">
            <DatabaseOutlined className="text-3xl mb-2 text-slate-600" />
            <p className="text-sm text-slate-400">暂无情报库</p>
            <p className="tele mt-2 text-slate-500">REGISTER A SOURCE</p>
          </div>
        ) : (
          <div className="space-y-1.5 p-1.5">
            {knowledgeBases.map((kb) => {
              const isActive = kb.knowledgeBaseId === activeKbId;
              return (
                <div
                  key={kb.knowledgeBaseId}
                  onClick={() => onSelectKnowledgeBase?.(kb.knowledgeBaseId)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter")
                      onSelectKnowledgeBase?.(kb.knowledgeBaseId);
                  }}
                  tabIndex={0}
                  className={`console-row w-full px-3 py-2.5 cursor-pointer ${isActive ? "is-active" : ""}`}
                >
                  <div className="flex items-start gap-3">
                    <div
                      className={`icon-tile w-8 h-8 mt-0.5 ${isActive ? "text-flare" : "text-telemetry"}`}
                    >
                      <DatabaseOutlined />
                    </div>
                    <div className="flex-1 min-w-0">
                      <div className="font-medium text-slate-100 truncate text-[13.5px]">
                        {kb.name}
                      </div>
                      {kb.description && (
                        <div className="text-xs text-slate-400 mt-0.5 line-clamp-2">
                          {kb.description}
                        </div>
                      )}
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

export default KnowledgeBaseTabContent;
