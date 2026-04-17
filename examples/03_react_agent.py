"""
示例 03 - ReAct Agent
======================
实现 ReAct（Reasoning + Acting）模式的 Agent。
模型交替进行思考（Thought）和行动（Action），
并观察（Observation）结果，直到得出最终答案。

运行方式:
    export OPENAI_API_KEY="your-api-key"
    python examples/03_react_agent.py
"""

import os
import re
import math
import json
from openai import OpenAI

client = OpenAI(api_key=os.environ.get("OPENAI_API_KEY"))


# ─── 工具实现 ─────────────────────────────────────────────────────────────────

def search(query: str) -> str:
    """
    模拟搜索工具（演示用）
    实际使用中替换为 Tavily、SerpAPI 等真实搜索 API
    """
    knowledge_base = {
        "python": (
            "Python 是由 Guido van Rossum 于 1991 年创建的高级编程语言。"
            "它以简洁的语法、强大的标准库和活跃的社区著称。"
            "广泛用于 Web 开发、数据科学、AI/ML、自动化等领域。"
        ),
        "agent": (
            "AI Agent 是能够感知环境、自主决策并采取行动以完成目标的智能系统。"
            "关键特性包括：工具调用、多步推理、记忆管理和自主决策。"
            "常见框架有 LangChain、AutoGen、CrewAI 等。"
        ),
        "langchain": (
            "LangChain 是一个用于构建 LLM 应用的开源框架，由 Harrison Chase 于 2022 年创立。"
            "提供 Chains、Agents、Memory 等核心抽象，支持 OpenAI、Anthropic 等多种模型提供商。"
        ),
        "react": (
            "ReAct 是 2022 年由 Yao 等人提出的 Agent 框架（arxiv.org/abs/2210.03629）。"
            "通过交替进行推理（Reasoning）和行动（Acting）来解决复杂任务，"
            "每次行动后观察结果并据此调整推理方向。"
        ),
        "gpt": (
            "GPT（Generative Pre-trained Transformer）是 OpenAI 开发的大语言模型系列。"
            "GPT-4o 是当前最新版本，支持文本、图像多模态输入，拥有 128K token 上下文。"
        ),
        "openai": (
            "OpenAI 成立于 2015 年，是一家 AI 研究公司，开发了 GPT 系列和 DALL-E 等模型。"
            "2022 年推出 ChatGPT，引发了全球 AI 应用热潮。"
        ),
    }

    query_lower = query.lower()
    for keyword, info in knowledge_base.items():
        if keyword in query_lower:
            return info

    return f"关于 '{query}' 的搜索结果：该查询在知识库中没有具体结果，但这是一个重要话题。"


def calculator(expression: str) -> str:
    """安全的数学计算器"""
    allowed_names = {
        "abs": abs, "round": round, "min": min, "max": max,
        "sqrt": math.sqrt, "pi": math.pi, "e": math.e,
        "sin": math.sin, "cos": math.cos, "tan": math.tan,
        "log": math.log, "log10": math.log10, "log2": math.log2,
        "floor": math.floor, "ceil": math.ceil,
    }
    try:
        result = eval(expression, {"__builtins__": {}}, allowed_names)
        return str(result)
    except ZeroDivisionError:
        return "错误：除以零"
    except Exception as e:
        return f"计算错误: {e}"


def get_wikipedia_summary(topic: str) -> str:
    """
    获取维基百科摘要（模拟版）
    """
    summaries = {
        "图灵奖": "图灵奖（Turing Award）是计算机领域最高奖项，由 ACM 颁发，被称为'计算机界的诺贝尔奖'。",
        "机器学习": "机器学习是 AI 的子领域，让系统从数据中自动学习和改进，无需显式编程。主要方法包括监督学习、无监督学习和强化学习。",
        "深度学习": "深度学习使用多层神经网络处理数据，在图像识别、自然语言处理等领域取得突破性进展。",
    }
    for key, summary in summaries.items():
        if key in topic:
            return summary
    return f"维基百科摘要：{topic} 是计算机科学和人工智能领域的重要概念。"


AVAILABLE_TOOLS = {
    "search": search,
    "calculator": calculator,
    "wikipedia": get_wikipedia_summary,
}

TOOL_DESCRIPTIONS = """
可用工具:
- search(query): 搜索信息。用于查找事实、概念解释等
- calculator(expression): 计算数学表达式。如 "2 + 2"、"sqrt(16)"、"3.14 * 5**2"
- wikipedia(topic): 获取维基百科摘要
"""


# ─── ReAct Prompt 模板 ────────────────────────────────────────────────────────

REACT_SYSTEM_PROMPT = f"""你是一个使用 ReAct 框架的智能助手。

{TOOL_DESCRIPTIONS}

请严格按照以下格式回答，每次只执行一个行动：

思考: [分析当前情况，决定下一步行动]
行动: 工具名称("参数")

当你已经收集到足够的信息，可以给出最终答案时：
思考: [总结已获取的信息]
最终答案: [基于推理和工具结果给出完整回答]

规则：
1. 必须先思考，再行动
2. 每次只能调用一个工具
3. 等收到观察结果后再进行下一步思考
4. 当有足够信息时，输出最终答案而不是继续调用工具
5. 参数必须用引号括起来
"""


# ─── ReAct Agent 实现 ────────────────────────────────────────────────────────

class ReActAgent:
    """
    ReAct Agent 实现

    交替进行推理（Thought）和行动（Action），
    观察（Observation）工具结果，直到得出最终答案。
    """

    def __init__(self, max_steps: int = 8, verbose: bool = True):
        self.max_steps = max_steps
        self.verbose = verbose

    def _parse_action(self, text: str) -> tuple[str, str] | None:
        """
        从 LLM 输出中解析工具调用

        Returns:
            (tool_name, argument) 或 None
        """
        # 匹配格式: 行动: tool_name("argument") 或 行动: tool_name('argument')
        pattern = r'行动:\s*(\w+)\(["\']([^"\']*)["\']?\)'
        match = re.search(pattern, text)
        if match:
            return match.group(1), match.group(2)

        # 宽松匹配：行动: tool_name(argument)
        pattern_loose = r'行动:\s*(\w+)\(([^)]+)\)'
        match = re.search(pattern_loose, text)
        if match:
            tool_name = match.group(1)
            arg = match.group(2).strip().strip('"\'')
            return tool_name, arg

        return None

    def _has_final_answer(self, text: str) -> bool:
        """检查输出是否包含最终答案"""
        return "最终答案:" in text

    def _extract_final_answer(self, text: str) -> str:
        """提取最终答案"""
        if "最终答案:" in text:
            return text.split("最终答案:")[-1].strip()
        return text

    def run(self, question: str) -> str:
        """
        运行 ReAct Agent

        Args:
            question: 用户问题

        Returns:
            最终答案
        """
        if self.verbose:
            print(f"\n{'='*60}")
            print(f"❓ 问题: {question}")
            print(f"{'='*60}\n")

        messages = [
            {"role": "system", "content": REACT_SYSTEM_PROMPT},
            {"role": "user", "content": f"请回答以下问题：{question}"}
        ]

        step_log = []

        for step in range(self.max_steps):
            # 调用 LLM（在"观察:"前停止，等待我们填入工具结果）
            response = client.chat.completions.create(
                model="gpt-4o-mini",
                messages=messages,
                stop=["观察:", "Observation:"],
                temperature=0,
                max_tokens=500
            )

            llm_output = response.choices[0].message.content.strip()

            if self.verbose:
                print(f"[步骤 {step + 1}]")
                print(llm_output)

            step_log.append({"step": step + 1, "thought_action": llm_output})

            # 检查是否有最终答案
            if self._has_final_answer(llm_output):
                final_answer = self._extract_final_answer(llm_output)
                if self.verbose:
                    print(f"\n{'='*60}")
                    print(f"✅ 最终答案: {final_answer}")
                    print(f"{'='*60}")
                return final_answer

            # 解析并执行工具调用
            action = self._parse_action(llm_output)
            if not action:
                if self.verbose:
                    print("⚠️  未检测到工具调用，要求 Agent 继续...")
                messages.append({"role": "assistant", "content": llm_output})
                messages.append({
                    "role": "user",
                    "content": "请使用一个工具获取所需信息，或者如果你已有足够信息，请给出最终答案。"
                })
                continue

            tool_name, tool_arg = action
            if self.verbose:
                print(f"\n🔧 执行工具: {tool_name}('{tool_arg}')")

            # 执行工具
            if tool_name in AVAILABLE_TOOLS:
                observation = AVAILABLE_TOOLS[tool_name](tool_arg)
            else:
                observation = f"错误：工具 '{tool_name}' 不存在。可用工具：{list(AVAILABLE_TOOLS.keys())}"

            if self.verbose:
                print(f"📊 观察: {observation}\n")

            step_log[-1]["observation"] = observation

            # 更新消息
            full_step = f"{llm_output}\n观察: {observation}"
            messages.append({"role": "assistant", "content": llm_output})
            messages.append({
                "role": "user",
                "content": f"观察: {observation}\n\n继续推理（如有足够信息请直接给出最终答案）："
            })

        return "已达最大步骤数，无法得出明确答案"


# ─── 演示 ─────────────────────────────────────────────────────────────────────

def run_demo():
    agent = ReActAgent(max_steps=8, verbose=True)

    questions = [
        "Python 是什么语言？它是哪年创建的？距今多少年了（假设现在是2024年）？",
        "什么是 ReAct Agent？它有什么优点？",
        "如果一个正方形边长为 sqrt(50)，它的面积和周长各是多少？",
    ]

    for question in questions:
        agent.run(question)
        print("\n" + "─" * 60 + "\n")


if __name__ == "__main__":
    if not os.environ.get("OPENAI_API_KEY"):
        print("⚠️  请设置 OPENAI_API_KEY 环境变量")
        exit(1)

    run_demo()
