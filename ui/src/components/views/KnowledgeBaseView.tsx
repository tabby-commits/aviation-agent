import React, { useMemo, useState } from "react";
import { useParams } from "react-router-dom";
import {
  Card,
  Typography,
  Button,
  Upload,
  Table,
  Popconfirm,
  Space,
  message,
  Empty,
} from "antd";
import {
  DatabaseOutlined,
  UploadOutlined,
  DeleteOutlined,
  FileOutlined,
  InboxOutlined,
} from "@ant-design/icons";
import type { UploadProps } from "antd";
import { useKnowledgeBases } from "../../hooks/useKnowledgeBases.ts";
import { useDocuments } from "../../hooks/useDocuments.ts";
import { uploadDocument, type DocumentVO } from "../../api/api.ts";

const { Title, Text, Paragraph } = Typography;

const KnowledgeBaseView: React.FC = () => {
  const { knowledgeBaseId } = useParams<{ knowledgeBaseId?: string }>();
  const { knowledgeBases } = useKnowledgeBases();
  const { documents, loading, refreshDocuments, deleteDocument } =
    useDocuments(knowledgeBaseId);

  const [uploading, setUploading] = useState(false);

  // 查找当前知识库的详细信息
  const currentKnowledgeBase = useMemo(() => {
    if (!knowledgeBaseId) return null;
    return (
      knowledgeBases.find((kb) => kb.knowledgeBaseId === knowledgeBaseId) ||
      null
    );
  }, [knowledgeBaseId, knowledgeBases]);

  // 处理文件上传
  const handleUpload: UploadProps["customRequest"] = async (options) => {
    const { file, onSuccess, onError } = options;

    if (!knowledgeBaseId) {
      message.error("请先选择情报库");
      return;
    }

    setUploading(true);

    try {
      await uploadDocument(knowledgeBaseId, file as File);
      message.success("情报材料上传成功");
      await refreshDocuments();
      onSuccess?.(file);
    } catch (error) {
      message.error(error instanceof Error ? error.message : "上传失败");
      onError?.(error as Error);
    } finally {
      setUploading(false);
    }
  };

  // 格式化文件大小
  const formatFileSize = (bytes: number): string => {
    if (bytes === 0) return "0 B";
    const k = 1024;
    const sizes = ["B", "KB", "MB", "GB"];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return Math.round((bytes / Math.pow(k, i)) * 100) / 100 + " " + sizes[i];
  };

  // 表格列定义
  const columns = [
    {
      title: "材料名称",
      dataIndex: "filename",
      key: "filename",
      render: (text: string) => (
        <Space>
          <FileOutlined className="text-telemetry" />
          <span>{text}</span>
        </Space>
      ),
    },
    {
      title: "材料类型",
      dataIndex: "filetype",
      key: "filetype",
      width: 120,
      render: (text: string) => (
        <span
          className="tele text-ink-2"
          style={{ fontSize: 10, letterSpacing: "0.08em" }}
        >
          {text}
        </span>
      ),
    },
    {
      title: "大小",
      dataIndex: "size",
      key: "size",
      width: 120,
      render: (size: number) => (
        <span style={{ fontFamily: "var(--font-tele)", fontSize: 12 }}>
          {formatFileSize(size)}
        </span>
      ),
    },
    {
      title: "操作",
      key: "action",
      width: 100,
      render: (_: unknown, record: DocumentVO) => (
        <Popconfirm
          title="确定要删除这份情报材料吗？"
          description="删除后将无法恢复"
          onConfirm={() => deleteDocument(record.id)}
          okText="确定"
          cancelText="取消"
        >
          <Button type="text" danger icon={<DeleteOutlined />} size="small">
            删除
          </Button>
        </Popconfirm>
      ),
    },
  ];

  // 未选择知识库时的提示
  if (!knowledgeBaseId) {
    return (
      <div className="flex flex-col h-full items-center justify-center p-6">
        <Empty
          image={<DatabaseOutlined className="text-5xl text-slate-300" />}
          description={
            <div className="mt-4">
              <Title level={4} type="secondary">
                未选择情报库
              </Title>
              <Text type="secondary" className="text-sm">
                请从左侧情报库列表中选择资料来源查看详情
              </Text>
              <div className="tele text-slate-400 mt-3">Select An Archive</div>
            </div>
          }
        />
      </div>
    );
  }

  // 知识库不存在
  if (!currentKnowledgeBase) {
    return (
      <div className="flex flex-col h-full items-center justify-center p-6">
        <Empty
          description={
            <div className="mt-4">
              <Title level={4} type="secondary">
                情报库不存在
              </Title>
              <Text type="secondary" className="text-sm">
                请检查情报库 ID 是否正确
              </Text>
            </div>
          }
        />
      </div>
    );
  }

  // 显示知识库详情和文档列表
  return (
    <div className="flex flex-col h-full p-6 overflow-y-auto workspace-scrollbar">
      <div className="max-w-6xl w-full mx-auto">
        {/* 档案头 */}
        <div className="mb-4">
          <Card className="panel-surface">
            <div className="flex items-start gap-4">
              <div
                className="w-14 h-14 rounded-lg flex items-center justify-center text-2xl shrink-0"
                style={{
                  background: "#f3fafc",
                  border: "1px solid #d8e7ee",
                  color: "#3fa9bc",
                }}
              >
                <DatabaseOutlined />
              </div>
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-3 flex-wrap">
                  <span className="tele text-telemetry">Archive</span>
                  <Title level={3} className="mb-0! text-ink">
                    {currentKnowledgeBase.name}
                  </Title>
                </div>
                {currentKnowledgeBase.description && (
                  <Paragraph className="text-ink-2 mb-0 mt-1">
                    {currentKnowledgeBase.description}
                  </Paragraph>
                )}
                <div className="flex items-center gap-4 mt-2 flex-wrap">
                  <Text type="secondary" className="tele" style={{ fontSize: 10 }}>
                    ID {currentKnowledgeBase.knowledgeBaseId.slice(0, 8)}
                  </Text>
                  <Text type="secondary" className="tele" style={{ fontSize: 10 }}>
                    DOCS {documents.length}
                  </Text>
                </div>
              </div>
            </div>
          </Card>
        </div>

        {/* 上传文档区域 */}
        <div className="mb-4">
          <Card className="panel-surface">
            <div className="flex items-center justify-between flex-wrap gap-4">
              <div className="flex items-center gap-3">
                <InboxOutlined className="text-xl text-slate-400" />
                <div>
                  <div className="text-sm font-medium text-ink">
                    上传情报材料
                  </div>
                  <Text type="secondary" className="text-xs">
                    支持格式: Markdown · 材料经清洗、切分后进入检索证据池
                  </Text>
                </div>
              </div>
              <Upload
                customRequest={handleUpload}
                showUploadList={false}
                accept=".md"
                disabled={uploading}
              >
                <Button
                  type="primary"
                  icon={<UploadOutlined />}
                  loading={uploading}
                >
                  选择材料上传
                </Button>
              </Upload>
            </div>
          </Card>
        </div>

        {/* 文档列表 */}
        <div className="mb-4">
          <Card
            title={`情报材料列表 (${documents.length})`}
            className="panel-surface"
          >
            {loading ? (
              <div className="text-center py-8">
                <Text type="secondary">加载中...</Text>
              </div>
            ) : documents.length === 0 ? (
              <Empty
                description={
                  <Text type="secondary">暂无情报材料，请上传材料</Text>
                }
              />
            ) : (
              <Table
                columns={columns}
                dataSource={documents}
                rowKey="id"
                pagination={{
                  pageSize: 10,
                  // showSizeChanger: true,
                  showTotal: (total) => `共 ${total} 条`,
                }}
              />
            )}
          </Card>
        </div>
      </div>
    </div>
  );
};

export default KnowledgeBaseView;
