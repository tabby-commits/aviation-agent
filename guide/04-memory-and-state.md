# 04 - 记忆与状态管理 / Memory and State Management

## 为什么需要记忆？

默认的 LLM 是无状态的——每次调用都是独立的，不记得之前的对话内容。但真实的 Agent 需要：

- 记住用户的偏好和历史操作
- 在长任务中维护中间结果
- 跨会话保持上下文
- 从过去的经验中学习

---

## 记忆的类型

```
┌─────────────────────────────────────────────────────┐
│                    Agent 记忆系统                     │
│                                                     │
│  ┌──────────────┐  ┌──────────────┐  ┌───────────┐ │
│  │  短期记忆     │  │  长期记忆     │  │  情节记忆  │ │
│  │ (对话历史)    │  │ (向量数据库)   │  │ (经验库)  │ │
│  └──────────────┘  └──────────────┘  └───────────┘ │
└─────────────────────────────────────────────────────┘
```

| 类型 | 说明 | 实现方式 |
|------|------|----------|
| **短期记忆** | 当前会话的对话历史 | 消息列表 |
| **工作记忆** | 任务执行中的中间状态 | 变量/字典 |
| **长期记忆** | 跨会话持久化的知识 | 向量数据库 |
| **情节记忆** | 过去的经验和案例 | 结构化存储 |

---

## 短期记忆：对话历史管理

### 基础对话历史

```python
from openai import OpenAI
from datetime import datetime

client = OpenAI()

class ConversationAgent:
    def __init__(self, system_prompt: str = "你是一个有帮助的助手。"):
        self.messages = [{"role": "system", "content": system_prompt}]
        self.max_history = 20  # 保留最近 20 条消息
    
    def chat(self, user_input: str) -> str:
        self.messages.append({"role": "user", "content": user_input})
        
        response = client.chat.completions.create(
            model="gpt-4o-mini",
            messages=self.messages
        )
        
        assistant_message = response.choices[0].message.content
        self.messages.append({"role": "assistant", "content": assistant_message})
        
        # 保留消息数量上限（保留 system prompt）
        if len(self.messages) > self.max_history + 1:
            self.messages = [self.messages[0]] + self.messages[-(self.max_history):]
        
        return assistant_message
    
    def clear_history(self):
        """清空对话历史，只保留系统提示"""
        self.messages = [self.messages[0]]

# 使用示例
agent = ConversationAgent("你是一个 Python 编程助手。")
print(agent.chat("什么是列表推导式？"))
print(agent.chat("能给我一个例子吗？"))
print(agent.chat("刚才你给的例子，我能修改成字典推导式吗？"))
```

### 对话摘要（解决上下文窗口限制）

```python
class SummarizingAgent:
    def __init__(self):
        self.messages = []
        self.summary = ""
        self.summary_threshold = 10  # 消息超过此数量时进行摘要
    
    def summarize_history(self):
        """将历史对话压缩为摘要"""
        if len(self.messages) < self.summary_threshold:
            return
        
        old_messages = self.messages[:-4]  # 保留最近 4 条
        
        summary_response = client.chat.completions.create(
            model="gpt-4o-mini",
            messages=[
                {"role": "system", "content": "请将以下对话历史压缩成简洁的摘要，保留重要信息。"},
                {"role": "user", "content": str(old_messages)}
            ]
        )
        
        self.summary = summary_response.choices[0].message.content
        self.messages = self.messages[-4:]  # 只保留最近 4 条
    
    def get_context_messages(self) -> list:
        """构建包含摘要的消息列表"""
        messages = []
        if self.summary:
            messages.append({
                "role": "system",
                "content": f"对话历史摘要：{self.summary}"
            })
        messages.extend(self.messages)
        return messages
```

---

## 长期记忆：向量数据库

长期记忆通过将信息转换为向量嵌入存储在向量数据库中，支持语义搜索。

### 使用 ChromaDB 实现长期记忆

```python
import chromadb
from openai import OpenAI

client = OpenAI()
chroma_client = chromadb.Client()
collection = chroma_client.create_collection("agent_memory")

def store_memory(content: str, metadata: dict = None):
    """存储记忆到向量数据库"""
    # 生成嵌入向量
    response = client.embeddings.create(
        model="text-embedding-3-small",
        input=content
    )
    embedding = response.data[0].embedding
    
    # 生成唯一 ID
    import hashlib
    doc_id = hashlib.md5(content.encode()).hexdigest()
    
    collection.add(
        embeddings=[embedding],
        documents=[content],
        metadatas=[metadata or {}],
        ids=[doc_id]
    )
    print(f"✅ 已存储记忆: {content[:50]}...")

def recall_memory(query: str, n_results: int = 3) -> list[str]:
    """从向量数据库检索相关记忆"""
    response = client.embeddings.create(
        model="text-embedding-3-small",
        input=query
    )
    query_embedding = response.data[0].embedding
    
    results = collection.query(
        query_embeddings=[query_embedding],
        n_results=n_results
    )
    
    return results["documents"][0] if results["documents"] else []

class MemoryAgent:
    def __init__(self):
        self.short_term = []
    
    def chat(self, user_input: str) -> str:
        # 从长期记忆检索相关信息
        relevant_memories = recall_memory(user_input)
        
        # 构建系统提示
        system_content = "你是一个有帮助的助手。"
        if relevant_memories:
            memory_text = "\n".join(f"- {m}" for m in relevant_memories)
            system_content += f"\n\n相关历史信息：\n{memory_text}"
        
        # 构建消息
        messages = [
            {"role": "system", "content": system_content},
            *self.short_term[-6:],  # 最近 6 条消息
            {"role": "user", "content": user_input}
        ]
        
        response = client.chat.completions.create(
            model="gpt-4o-mini",
            messages=messages
        )
        
        answer = response.choices[0].message.content
        
        # 更新短期记忆
        self.short_term.append({"role": "user", "content": user_input})
        self.short_term.append({"role": "assistant", "content": answer})
        
        # 异步存储重要信息到长期记忆
        self._maybe_store_memory(user_input, answer)
        
        return answer
    
    def _maybe_store_memory(self, user_input: str, answer: str):
        """判断是否需要存储到长期记忆"""
        important_keywords = ["我叫", "我是", "我喜欢", "我需要", "记住"]
        if any(keyword in user_input for keyword in important_keywords):
            store_memory(
                f"用户说：{user_input}",
                {"type": "user_preference", "timestamp": str(datetime.now())}
            )
```

---

## 工作记忆：任务状态管理

```python
from dataclasses import dataclass, field
from typing import Any
from enum import Enum

class TaskStatus(Enum):
    PENDING = "pending"
    IN_PROGRESS = "in_progress"
    COMPLETED = "completed"
    FAILED = "failed"

@dataclass
class TaskState:
    goal: str
    status: TaskStatus = TaskStatus.PENDING
    steps_completed: list[str] = field(default_factory=list)
    current_step: str = ""
    context: dict[str, Any] = field(default_factory=dict)
    errors: list[str] = field(default_factory=list)

class StatefulAgent:
    def __init__(self):
        self.state: TaskState | None = None
    
    def start_task(self, goal: str) -> None:
        self.state = TaskState(goal=goal, status=TaskStatus.IN_PROGRESS)
        print(f"🚀 开始任务: {goal}")
    
    def complete_step(self, step_description: str, result: Any = None) -> None:
        if self.state:
            self.state.steps_completed.append(step_description)
            if result is not None:
                self.state.context[f"step_{len(self.state.steps_completed)}"] = result
            print(f"✅ 完成步骤: {step_description}")
    
    def get_progress_summary(self) -> str:
        if not self.state:
            return "没有进行中的任务"
        
        return f"""
任务目标: {self.state.goal}
状态: {self.state.status.value}
已完成步骤 ({len(self.state.steps_completed)}):
{chr(10).join(f"  {i+1}. {step}" for i, step in enumerate(self.state.steps_completed))}
当前步骤: {self.state.current_step}
"""
```

---

## 下一步

👉 [05 - 多智能体系统](05-multi-agent.md)
