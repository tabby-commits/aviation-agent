# 03 - ReAct 推理模式 / ReAct Pattern

## 什么是 ReAct？

**ReAct** = **Re**asoning + **Act**ing（推理 + 行动）

ReAct 是由 Yao 等人在 2022 年提出的 Agent 框架（[论文链接](https://arxiv.org/abs/2210.03629)）。它让模型交替进行：
1. **思考（Thought）**：推理当前情况和下一步计划
2. **行动（Action）**：调用工具执行操作
3. **观察（Observation）**：查看行动结果

这个循环持续进行，直到任务完成。

---

## ReAct 工作流程

```
问题 (Question)
    ↓
思考 (Thought): 我需要先搜索一下...
    ↓
行动 (Action): search("最新 AI 进展")
    ↓
观察 (Observation): 搜索结果显示...
    ↓
思考 (Thought): 基于结果，我还需要...
    ↓
行动 (Action): calculator("1024 * 365")
    ↓
观察 (Observation): 结果是 373760
    ↓
思考 (Thought): 现在我有足够信息了...
    ↓
最终答案 (Final Answer): ...
```

---

## 手动实现 ReAct Agent

以下是一个不依赖任何框架的原生 ReAct 实现：

```python
from openai import OpenAI
import json
import re

client = OpenAI()

# ─── 工具定义 ───────────────────────────────────────────────────────────────

def search(query: str) -> str:
    """模拟搜索工具（实际使用时替换为真实 API）"""
    mock_results = {
        "python": "Python 是一种高级编程语言，以简洁语法著称。",
        "agent": "AI Agent 是能够自主完成任务的智能系统。",
    }
    for key, value in mock_results.items():
        if key in query.lower():
            return value
    return f"关于 '{query}' 的搜索结果：找到相关信息..."

def calculator(expression: str) -> str:
    """安全的数学计算器"""
    import math
    allowed_names = {
        "abs": abs, "round": round, "min": min, "max": max,
        "sqrt": math.sqrt, "pi": math.pi, "e": math.e,
        "sin": math.sin, "cos": math.cos, "log": math.log,
    }
    try:
        result = eval(expression, {"__builtins__": {}}, allowed_names)
        return str(result)
    except Exception as ex:
        return f"计算错误: {ex}"

TOOLS = {
    "search": search,
    "calculator": calculator,
}

# ─── ReAct Prompt ────────────────────────────────────────────────────────────

REACT_SYSTEM_PROMPT = """你是一个使用 ReAct 框架的智能助手。

对于每个任务，你需要按照以下格式交替进行思考和行动：

思考: [分析当前情况，决定下一步行动]
行动: [工具名称]([参数])
观察: [工具返回的结果]
... (重复思考/行动/观察，直到有足够信息)
最终答案: [基于以上信息给出的最终回答]

可用工具:
- search(query: str): 搜索互联网获取信息
- calculator(expression: str): 执行数学计算，如 "2 + 2" 或 "sqrt(16)"

重要规则：
1. 每次只执行一个行动
2. 行动格式必须严格为：行动: 工具名称(参数)
3. 等待观察结果后再进行下一步思考
4. 当你确定有了最终答案时，使用"最终答案:"前缀
"""

# ─── ReAct 执行引擎 ──────────────────────────────────────────────────────────

def parse_action(text: str) -> tuple[str, str] | None:
    """从文本中解析行动调用"""
    pattern = r'行动:\s*(\w+)\(([^)]*)\)'
    match = re.search(pattern, text)
    if match:
        tool_name = match.group(1)
        tool_args = match.group(2).strip().strip('"\'')
        return tool_name, tool_args
    return None

def react_agent(question: str, max_steps: int = 10) -> str:
    """
    ReAct Agent 主循环
    
    Args:
        question: 用户问题
        max_steps: 最大执行步骤数
    
    Returns:
        最终答案
    """
    messages = [
        {"role": "system", "content": REACT_SYSTEM_PROMPT},
        {"role": "user", "content": question}
    ]
    
    print(f"\n{'='*60}")
    print(f"问题: {question}")
    print(f"{'='*60}\n")
    
    for step in range(max_steps):
        # 调用 LLM
        response = client.chat.completions.create(
            model="gpt-4o-mini",
            messages=messages,
            stop=["观察:"],  # 在模型生成观察之前停止
            temperature=0
        )
        
        assistant_message = response.choices[0].message.content
        print(f"[步骤 {step + 1}]\n{assistant_message}")
        
        # 检查是否有最终答案
        if "最终答案:" in assistant_message:
            final_answer = assistant_message.split("最终答案:")[-1].strip()
            print(f"\n{'='*60}")
            print(f"✅ 最终答案: {final_answer}")
            print(f"{'='*60}\n")
            return final_answer
        
        # 解析行动
        action = parse_action(assistant_message)
        if not action:
            print("⚠️  未检测到行动，结束执行")
            break
        
        tool_name, tool_args = action
        
        # 执行工具
        if tool_name in TOOLS:
            observation = TOOLS[tool_name](tool_args)
        else:
            observation = f"错误：工具 '{tool_name}' 不存在"
        
        print(f"观察: {observation}\n")
        
        # 将结果添加到对话
        messages.append({"role": "assistant", "content": assistant_message})
        messages.append({
            "role": "user",
            "content": f"观察: {observation}\n\n继续推理："
        })
    
    return "未能在最大步骤数内完成任务"


# ─── 使用示例 ─────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    # 示例 1：需要搜索的问题
    react_agent("什么是 AI Agent？请给我一个简单的解释。")
    
    # 示例 2：需要计算的问题
    react_agent("如果一个正方形的边长是 sqrt(50)，它的面积是多少？")
    
    # 示例 3：多步骤问题
    react_agent("搜索 Python 的信息，然后计算 Python 发布年份（1991年）到现在（2024年）经过了多少年。")
```

---

## 使用 LangChain 实现 ReAct

LangChain 提供了现成的 ReAct 实现：

```python
from langchain import hub
from langchain.agents import AgentExecutor, create_react_agent
from langchain_openai import ChatOpenAI
from langchain_community.tools.tavily_search import TavilySearchResults
from langchain_core.tools import tool

# 定义工具
@tool
def calculator(expression: str) -> str:
    """执行数学计算。输入一个数学表达式，返回计算结果。"""
    import math
    allowed_names = {"sqrt": math.sqrt, "pi": math.pi}
    try:
        return str(eval(expression, {"__builtins__": {}}, allowed_names))
    except Exception as e:
        return f"计算错误: {e}"

# 初始化 LLM 和工具
llm = ChatOpenAI(model="gpt-4o-mini", temperature=0)
tools = [TavilySearchResults(max_results=3), calculator]

# 使用 hub 中的 ReAct prompt
prompt = hub.pull("hwchase17/react")

# 创建 Agent
agent = create_react_agent(llm, tools, prompt)
agent_executor = AgentExecutor(agent=agent, tools=tools, verbose=True)

# 运行
result = agent_executor.invoke({
    "input": "2024年诺贝尔物理学奖得主是谁？他们的主要贡献是什么？"
})
print(result["output"])
```

---

## ReAct 的优缺点

### 优点 ✅
- **可解释性强**：每个推理步骤都清晰可见
- **灵活性高**：可以动态调整策略
- **错误恢复**：可以从错误中学习并调整
- **通用性好**：适用于各类任务

### 缺点 ❌
- **速度较慢**：多轮 LLM 调用增加延迟
- **成本较高**：每个步骤都消耗 Token
- **可能循环**：需要设置最大步骤数防止无限循环
- **格式依赖**：需要模型严格遵守输出格式

---

## 进阶技巧

### 1. 添加反思机制

```python
REFLECTION_PROMPT = """
回顾你的行动历史，评估：
1. 是否在正确的方向上前进？
2. 是否有更高效的方法？
3. 是否遗漏了重要信息？

如果需要调整策略，请说明原因。
"""
```

### 2. 并行工具调用

当多个工具调用相互独立时，可以并行执行：

```python
import asyncio

async def parallel_tool_calls(tool_calls: list) -> list:
    tasks = [execute_tool_async(name, args) for name, args in tool_calls]
    return await asyncio.gather(*tasks)
```

---

## 下一步

👉 [04 - 记忆与状态管理](04-memory-and-state.md)
