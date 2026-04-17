# 05 - 多智能体系统 / Multi-Agent Systems

## 为什么需要多智能体？

单个 Agent 在处理复杂任务时面临挑战：

- **上下文窗口限制**：复杂任务信息量超出单次处理能力
- **专业化需求**：不同子任务需要不同的专业知识
- **并行效率**：串行执行效率低下
- **错误传播**：单点失败影响整体任务

多智能体系统通过**分工协作**解决上述问题。

---

## 多智能体模式

### 1. 主管-工作者模式（Supervisor-Worker）

```
                   ┌──────────────┐
用户 ──→ 任务 ──→  │  Supervisor  │
                   │   (协调者)    │
                   └──────┬───────┘
                          │ 分配子任务
              ┌───────────┼───────────┐
              ↓           ↓           ↓
        ┌─────────┐ ┌─────────┐ ┌─────────┐
        │ Worker1 │ │ Worker2 │ │ Worker3 │
        │ (搜索)  │ │ (分析)  │ │ (写作)  │
        └─────────┘ └─────────┘ └─────────┘
              │           │           │
              └───────────┼───────────┘
                          ↓
                     ┌──────────┐
                     │ 汇总结果  │
                     └──────────┘
```

### 2. 流水线模式（Pipeline）

```
输入 ──→ Agent1(数据收集) ──→ Agent2(分析) ──→ Agent3(生成报告) ──→ 输出
```

### 3. 对话协作模式（Conversation）

```
Agent A ←──── 对话 ────→ Agent B
  (提出方案)              (评审批评)
      ↑                      │
      └──────────────────────┘
         (迭代改进直到达成共识)
```

---

## 实现主管-工作者系统

```python
from openai import OpenAI
import json
from typing import Callable

client = OpenAI()

# ─── 专业化 Worker Agent ──────────────────────────────────────────────────────

class WorkerAgent:
    def __init__(self, name: str, role: str, skills: list[str]):
        self.name = name
        self.role = role
        self.skills = skills
        self.system_prompt = f"""你是 {name}，一个专业的 {role}。
你的专长包括：{', '.join(skills)}
请专注于你的专业领域，给出专业、准确的回答。"""
    
    def execute(self, task: str) -> str:
        response = client.chat.completions.create(
            model="gpt-4o-mini",
            messages=[
                {"role": "system", "content": self.system_prompt},
                {"role": "user", "content": task}
            ]
        )
        return response.choices[0].message.content
    
    def __repr__(self):
        return f"WorkerAgent({self.name}, skills={self.skills})"

# ─── Supervisor Agent ─────────────────────────────────────────────────────────

class SupervisorAgent:
    def __init__(self, workers: list[WorkerAgent]):
        self.workers = {w.name: w for w in workers}
        self.worker_descriptions = "\n".join(
            f"- {w.name} ({w.role}): 擅长 {', '.join(w.skills)}"
            for w in workers
        )
    
    def plan(self, task: str) -> list[dict]:
        """制定任务执行计划"""
        planning_prompt = f"""你是一个任务协调者。根据以下任务和可用的工作者，制定执行计划。

任务: {task}

可用工作者:
{self.worker_descriptions}

请以 JSON 格式返回执行步骤列表，每个步骤包含：
- worker: 负责的工作者名称
- subtask: 具体的子任务描述
- depends_on: 依赖的前置步骤索引列表（从 0 开始）

示例格式：
[
  {{"worker": "Researcher", "subtask": "搜索相关信息", "depends_on": []}},
  {{"worker": "Writer", "subtask": "根据研究结果撰写报告", "depends_on": [0]}}
]"""
        
        response = client.chat.completions.create(
            model="gpt-4o-mini",
            messages=[{"role": "user", "content": planning_prompt}],
            response_format={"type": "json_object"}
        )
        
        result = json.loads(response.choices[0].message.content)
        # 处理不同的 JSON 结构
        if isinstance(result, list):
            return result
        # 如果返回的是包含列表的对象
        for key in ["steps", "plan", "tasks"]:
            if key in result:
                return result[key]
        return list(result.values())[0] if result else []
    
    def execute(self, task: str) -> str:
        """协调执行完整任务"""
        print(f"\n{'='*60}")
        print(f"📋 任务: {task}")
        print(f"{'='*60}\n")
        
        # 制定计划
        steps = self.plan(task)
        print(f"📌 执行计划 ({len(steps)} 步):")
        for i, step in enumerate(steps):
            print(f"  {i+1}. [{step['worker']}] {step['subtask']}")
        print()
        
        # 执行步骤
        results = {}
        for i, step in enumerate(steps):
            worker_name = step["worker"]
            subtask = step["subtask"]
            
            # 等待依赖完成（串行执行依赖任务）
            context = ""
            for dep_idx in step.get("depends_on", []):
                if dep_idx in results:
                    context += f"\n前置步骤 {dep_idx+1} 的结果:\n{results[dep_idx]}\n"
            
            full_task = subtask
            if context:
                full_task = f"{subtask}\n\n参考信息:{context}"
            
            if worker_name not in self.workers:
                print(f"⚠️  未找到工作者: {worker_name}")
                continue
            
            print(f"🔄 步骤 {i+1}: [{worker_name}] 正在执行...")
            result = self.workers[worker_name].execute(full_task)
            results[i] = result
            print(f"✅ 步骤 {i+1} 完成\n")
        
        # 汇总结果
        if results:
            all_results = "\n\n".join(
                f"步骤 {i+1} ({steps[i]['worker']}):\n{result}"
                for i, result in results.items()
            )
            
            summary_response = client.chat.completions.create(
                model="gpt-4o-mini",
                messages=[{
                    "role": "user",
                    "content": f"原始任务: {task}\n\n各步骤结果:\n{all_results}\n\n请整合以上结果，生成最终的综合回答。"
                }]
            )
            return summary_response.choices[0].message.content
        
        return "任务执行失败：没有成功完成的步骤"


# ─── 使用示例 ─────────────────────────────────────────────────────────────────

def create_research_team() -> SupervisorAgent:
    """创建一个研究团队"""
    researcher = WorkerAgent(
        name="Researcher",
        role="研究员",
        skills=["信息收集", "数据分析", "事实核查"]
    )
    
    analyst = WorkerAgent(
        name="Analyst",
        role="分析师",
        skills=["数据解读", "趋势分析", "洞察提炼"]
    )
    
    writer = WorkerAgent(
        name="Writer",
        role="技术写作者",
        skills=["内容撰写", "结构化表达", "报告生成"]
    )
    
    return SupervisorAgent(workers=[researcher, analyst, writer])


if __name__ == "__main__":
    team = create_research_team()
    result = team.execute("分析 AI Agent 技术的发展趋势和主要应用场景，并给出未来展望")
    print(f"\n{'='*60}")
    print("📄 最终报告:")
    print(f"{'='*60}")
    print(result)
```

---

## AutoGen 多智能体框架

[AutoGen](https://github.com/microsoft/autogen) 是微软开源的多智能体对话框架：

```python
# 安装: pip install pyautogen
import autogen

config_list = [{"model": "gpt-4o-mini", "api_key": "your-api-key"}]

# 创建用户代理
user_proxy = autogen.UserProxyAgent(
    name="User",
    human_input_mode="NEVER",
    max_consecutive_auto_reply=10,
    code_execution_config={"work_dir": "output", "use_docker": False},
)

# 创建助手代理
assistant = autogen.AssistantAgent(
    name="Assistant",
    llm_config={"config_list": config_list},
    system_message="你是一个专业的 Python 程序员。"
)

# 创建代码审查员
code_reviewer = autogen.AssistantAgent(
    name="CodeReviewer",
    llm_config={"config_list": config_list},
    system_message="你是一个代码审查专家，专注于代码质量、安全性和最佳实践。"
)

# 创建群组对话
groupchat = autogen.GroupChat(
    agents=[user_proxy, assistant, code_reviewer],
    messages=[],
    max_round=12
)

manager = autogen.GroupChatManager(
    groupchat=groupchat,
    llm_config={"config_list": config_list}
)

# 启动对话
user_proxy.initiate_chat(
    manager,
    message="请编写一个 Python 函数来实现快速排序，然后进行代码审查。"
)
```

---

## 多智能体设计原则

### 1. 单一职责
每个 Agent 专注于一个特定领域，避免功能重叠。

### 2. 明确接口
Agent 之间通过清晰定义的接口通信，避免紧耦合。

### 3. 错误隔离
一个 Agent 的失败不应该导致整个系统崩溃。

```python
def safe_agent_call(agent: WorkerAgent, task: str) -> tuple[bool, str]:
    """安全的 Agent 调用，带错误处理"""
    try:
        result = agent.execute(task)
        return True, result
    except Exception as e:
        return False, f"Agent {agent.name} 执行失败: {str(e)}"
```

### 4. 可观测性
记录 Agent 间的通信，便于调试和优化。

```python
import logging
from datetime import datetime

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("multi-agent")

def log_agent_interaction(sender: str, receiver: str, message: str, result: str):
    logger.info(f"[{datetime.now().isoformat()}] {sender} → {receiver}")
    logger.info(f"  任务: {message[:100]}...")
    logger.info(f"  结果: {result[:100]}...")
```

---

## 总结：选择合适的 Agent 架构

| 场景 | 推荐架构 |
|------|----------|
| 简单的单轮问答 | 单 Agent + Function Calling |
| 需要多步骤推理 | ReAct Agent |
| 需要跨会话记忆 | 带长期记忆的 Agent |
| 复杂的多领域任务 | 主管-工作者多智能体 |
| 代码生成和审查 | 对话协作多智能体（AutoGen） |
| 流程自动化 | 流水线多智能体 |

---

## 🎉 恭喜完成学习！

你已经学习了 AI Agent 开发的核心概念：
- ✅ Agent 基础概念与工作原理
- ✅ 工具与函数调用
- ✅ ReAct 推理模式
- ✅ 记忆与状态管理
- ✅ 多智能体系统

接下来，建议尝试运行 [examples/](../examples/) 目录中的实际代码示例！
