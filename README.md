# 学习 Agent 开发 / Learning Agent Development

> 一个循序渐进的 AI Agent 开发学习指南，包含原理讲解、代码示例和实战项目。
>
> A step-by-step guide to AI Agent development, covering concepts, code examples, and hands-on projects.

---

## 什么是 Agent？ / What is an Agent?

AI Agent（智能代理）是一种能够**感知环境、做出决策并采取行动**以实现目标的 AI 系统。相比普通的 LLM 调用，Agent 具有以下特点：

- **工具调用（Tool Use）**：可以调用外部工具（如搜索、计算、代码执行）
- **多步推理（Multi-step Reasoning）**：能够将复杂任务拆解成多个步骤
- **记忆（Memory）**：可以在对话中保留上下文信息
- **自主决策（Autonomous Decision-making）**：根据当前状态自主选择下一步行动

---

## 学习路径 / Learning Path

```
1. 基础概念  →  2. 工具调用  →  3. ReAct 模式  →  4. 多智能体  →  5. 实战项目
   Concepts       Tool Use       ReAct Pattern    Multi-Agent    Real Projects
```

| 章节 | 主题 | 文件 |
|------|------|------|
| 01 | Agent 基础概念 | [guide/01-introduction.md](guide/01-introduction.md) |
| 02 | 工具与函数调用 | [guide/02-tools-and-functions.md](guide/02-tools-and-functions.md) |
| 03 | ReAct 推理模式 | [guide/03-react-pattern.md](guide/03-react-pattern.md) |
| 04 | 记忆与状态管理 | [guide/04-memory-and-state.md](guide/04-memory-and-state.md) |
| 05 | 多智能体系统 | [guide/05-multi-agent.md](guide/05-multi-agent.md) |

---

## 代码示例 / Code Examples

| 示例 | 说明 | 文件 |
|------|------|------|
| 简单 Agent | 最基础的 Agent 实现 | [examples/01_simple_agent.py](examples/01_simple_agent.py) |
| 工具调用 Agent | 带工具使用的 Agent | [examples/02_tool_use_agent.py](examples/02_tool_use_agent.py) |
| ReAct Agent | 使用 ReAct 模式的 Agent | [examples/03_react_agent.py](examples/03_react_agent.py) |
| 记忆 Agent | 带长期记忆的 Agent | [examples/04_memory_agent.py](examples/04_memory_agent.py) |
| 多智能体系统 | 多个 Agent 协作 | [examples/05_multi_agent.py](examples/05_multi_agent.py) |

---

## 快速开始 / Quick Start

### 环境准备

```bash
# 安装依赖
pip install -r requirements.txt

# 配置 API Key（以 OpenAI 为例）
export OPENAI_API_KEY="your-api-key-here"
```

### 运行第一个示例

```bash
python examples/01_simple_agent.py
```

---

## 核心概念速览 / Key Concepts at a Glance

### Agent 循环 (Agent Loop)

```
┌─────────────────────────────────────────┐
│                                         │
│   感知(Perceive) → 思考(Think) → 行动(Act)  │
│         ↑                      │        │
│         └──────────────────────┘        │
│                                         │
└─────────────────────────────────────────┘
```

### 主流 Agent 框架

| 框架 | 特点 | 适用场景 |
|------|------|----------|
| [LangChain](https://langchain.com) | 生态丰富，组件化 | 快速原型，通用任务 |
| [LlamaIndex](https://llamaindex.ai) | 专注 RAG 和数据 | 知识库问答 |
| [AutoGen](https://github.com/microsoft/autogen) | 多智能体对话 | 复杂协作任务 |
| [CrewAI](https://crewai.com) | 角色扮演式多智能体 | 团队协作模拟 |
| 原生实现 | 灵活可控 | 学习理解原理 |

---

## 推荐资源 / Recommended Resources

- [OpenAI Function Calling 文档](https://platform.openai.com/docs/guides/function-calling)
- [Anthropic Tool Use 文档](https://docs.anthropic.com/en/docs/tool-use)
- [ReAct 论文](https://arxiv.org/abs/2210.03629)
- [Agents 综述论文](https://arxiv.org/abs/2309.07864)

---

## 贡献 / Contributing

欢迎提交 PR 或 Issue 来改进本学习指南！