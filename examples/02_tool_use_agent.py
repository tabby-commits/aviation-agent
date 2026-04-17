"""
示例 02 - 工具调用 Agent / Tool Use Agent
==========================================
演示如何构建一个能够调用工具的 Agent。
包含以下工具：
  - 数学计算器
  - 文本分析（字数统计）
  - 当前时间查询
  - 单位换算

运行方式:
    export OPENAI_API_KEY="your-api-key"
    python examples/02_tool_use_agent.py
"""

import os
import json
import math
from datetime import datetime
from openai import OpenAI

client = OpenAI(api_key=os.environ.get("OPENAI_API_KEY"))


# ─── 工具函数实现 ─────────────────────────────────────────────────────────────

def calculator(expression: str) -> str:
    """
    安全的数学计算器

    Args:
        expression: 数学表达式，如 "2 + 2"、"sqrt(16)"、"pi * 5**2"

    Returns:
        计算结果字符串
    """
    allowed_names = {
        "abs": abs, "round": round, "min": min, "max": max,
        "sum": sum, "pow": pow,
        "sqrt": math.sqrt, "pi": math.pi, "e": math.e,
        "sin": math.sin, "cos": math.cos, "tan": math.tan,
        "log": math.log, "log2": math.log2, "log10": math.log10,
        "ceil": math.ceil, "floor": math.floor,
    }
    try:
        result = eval(expression, {"__builtins__": {}}, allowed_names)
        return f"计算结果: {result}"
    except ZeroDivisionError:
        return "错误: 除以零"
    except Exception as e:
        return f"计算错误: {str(e)}"


def analyze_text(text: str) -> str:
    """
    分析文本基本信息

    Args:
        text: 要分析的文本

    Returns:
        文本统计信息 JSON 字符串
    """
    # 按空格和中文字符分词（简化版）
    import re
    chinese_chars = len(re.findall(r'[\u4e00-\u9fff]', text))
    english_words = len(re.findall(r'\b[a-zA-Z]+\b', text))
    sentences = len(re.split(r'[。！？.!?]+', text.strip()))
    sentences = max(1, sentences)

    stats = {
        "总字符数": len(text),
        "中文字符数": chinese_chars,
        "英文单词数": english_words,
        "句子数": sentences,
        "平均句子长度": round(len(text) / sentences, 1),
    }
    return json.dumps(stats, ensure_ascii=False)


def get_current_time(timezone: str = "Asia/Shanghai") -> str:
    """
    获取当前时间

    Args:
        timezone: 时区名称（简化版，仅支持几个常用时区）

    Returns:
        当前时间字符串
    """
    now = datetime.now()
    offsets = {
        "Asia/Shanghai": 8,
        "Asia/Tokyo": 9,
        "Europe/London": 0,
        "America/New_York": -5,
        "America/Los_Angeles": -8,
        "UTC": 0,
    }
    offset = offsets.get(timezone, 8)
    from datetime import timezone as tz, timedelta
    aware_time = datetime.now(tz.utc) + timedelta(hours=offset)
    return f"{timezone}: {aware_time.strftime('%Y年%m月%d日 %H:%M:%S')}"


def convert_units(value: float, from_unit: str, to_unit: str) -> str:
    """
    单位换算

    Args:
        value: 数值
        from_unit: 源单位（如 "km", "kg", "celsius"）
        to_unit: 目标单位（如 "miles", "pounds", "fahrenheit"）

    Returns:
        换算结果字符串
    """
    conversions: dict[tuple[str, str], float] = {
        # 长度
        ("km", "miles"): 0.621371,
        ("miles", "km"): 1.60934,
        ("m", "feet"): 3.28084,
        ("feet", "m"): 0.3048,
        ("cm", "inches"): 0.393701,
        ("inches", "cm"): 2.54,
        # 重量
        ("kg", "pounds"): 2.20462,
        ("pounds", "kg"): 0.453592,
        ("g", "oz"): 0.035274,
        ("oz", "g"): 28.3495,
    }

    # 温度换算需要特殊处理
    if from_unit == "celsius" and to_unit == "fahrenheit":
        result = value * 9 / 5 + 32
        return f"{value}°C = {result:.2f}°F"
    elif from_unit == "fahrenheit" and to_unit == "celsius":
        result = (value - 32) * 5 / 9
        return f"{value}°F = {result:.2f}°C"
    elif from_unit == "celsius" and to_unit == "kelvin":
        result = value + 273.15
        return f"{value}°C = {result:.2f}K"

    key = (from_unit.lower(), to_unit.lower())
    if key in conversions:
        result = value * conversions[key]
        return f"{value} {from_unit} = {result:.4f} {to_unit}"
    else:
        return f"不支持 {from_unit} 到 {to_unit} 的换算"


# ─── OpenAI 工具定义 ──────────────────────────────────────────────────────────

TOOLS_SCHEMA = [
    {
        "type": "function",
        "function": {
            "name": "calculator",
            "description": "执行数学计算。支持基本运算、三角函数、对数等。示例：'2 + 2'、'sqrt(16)'、'pi * r**2'",
            "parameters": {
                "type": "object",
                "properties": {
                    "expression": {
                        "type": "string",
                        "description": "要计算的数学表达式"
                    }
                },
                "required": ["expression"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "analyze_text",
            "description": "分析文本统计信息，包括字符数、词数、句子数等",
            "parameters": {
                "type": "object",
                "properties": {
                    "text": {
                        "type": "string",
                        "description": "要分析的文本内容"
                    }
                },
                "required": ["text"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "get_current_time",
            "description": "获取指定时区的当前日期和时间",
            "parameters": {
                "type": "object",
                "properties": {
                    "timezone": {
                        "type": "string",
                        "description": "时区名称，如 'Asia/Shanghai'、'America/New_York'、'UTC'",
                        "enum": ["Asia/Shanghai", "Asia/Tokyo", "Europe/London",
                                 "America/New_York", "America/Los_Angeles", "UTC"]
                    }
                },
                "required": []
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "convert_units",
            "description": "进行单位换算，支持长度、重量、温度等",
            "parameters": {
                "type": "object",
                "properties": {
                    "value": {
                        "type": "number",
                        "description": "要换算的数值"
                    },
                    "from_unit": {
                        "type": "string",
                        "description": "源单位，如 'km'、'kg'、'celsius'、'fahrenheit'"
                    },
                    "to_unit": {
                        "type": "string",
                        "description": "目标单位，如 'miles'、'pounds'、'fahrenheit'"
                    }
                },
                "required": ["value", "from_unit", "to_unit"]
            }
        }
    }
]

# 工具函数映射
TOOL_FUNCTIONS = {
    "calculator": calculator,
    "analyze_text": analyze_text,
    "get_current_time": get_current_time,
    "convert_units": convert_units,
}


# ─── Tool Use Agent ──────────────────────────────────────────────────────────

class ToolUseAgent:
    """
    带工具使用能力的 Agent
    - 支持多轮工具调用
    - 自动解析和执行工具
    - 维护完整对话历史
    """

    def __init__(self, verbose: bool = True):
        self.messages: list[dict] = [
            {
                "role": "system",
                "content": (
                    "你是一个能够使用工具的智能助手。"
                    "当用户提出需要计算、时间查询或单位换算的问题时，请使用相应工具。"
                    "在使用工具后，基于工具结果给出清晰的回答。"
                )
            }
        ]
        self.verbose = verbose

    def _execute_tool(self, tool_name: str, tool_args: dict) -> str:
        """执行工具并返回结果"""
        if tool_name not in TOOL_FUNCTIONS:
            return f"错误：未知工具 '{tool_name}'"
        try:
            return TOOL_FUNCTIONS[tool_name](**tool_args)
        except TypeError as e:
            return f"工具参数错误: {e}"
        except Exception as e:
            return f"工具执行错误: {e}"

    def chat(self, user_message: str) -> str:
        """
        处理用户消息，可能涉及多轮工具调用

        Args:
            user_message: 用户输入

        Returns:
            最终回答
        """
        self.messages.append({"role": "user", "content": user_message})

        # Agent 循环
        max_tool_calls = 10
        tool_call_count = 0

        while tool_call_count < max_tool_calls:
            response = client.chat.completions.create(
                model="gpt-4o-mini",
                messages=self.messages,
                tools=TOOLS_SCHEMA,
                tool_choice="auto"
            )

            message = response.choices[0].message
            finish_reason = response.choices[0].finish_reason

            # 将助手消息添加到历史
            self.messages.append(message.model_dump())

            # 如果没有工具调用，直接返回
            if finish_reason == "stop" or not message.tool_calls:
                return message.content

            # 处理工具调用
            tool_results = []
            for tool_call in message.tool_calls:
                func_name = tool_call.function.name
                func_args = json.loads(tool_call.function.arguments)

                if self.verbose:
                    print(f"  🔧 调用工具: {func_name}({func_args})")

                result = self._execute_tool(func_name, func_args)

                if self.verbose:
                    print(f"  📊 工具结果: {result}")

                tool_results.append({
                    "role": "tool",
                    "tool_call_id": tool_call.id,
                    "content": result
                })
                tool_call_count += 1

            # 将所有工具结果添加到消息
            self.messages.extend(tool_results)

        return "达到最大工具调用次数限制"


# ─── 演示 ─────────────────────────────────────────────────────────────────────

def run_demo():
    """运行工具调用演示"""
    print("=" * 60)
    print("工具调用 Agent 演示 / Tool Use Agent Demo")
    print("=" * 60)

    agent = ToolUseAgent(verbose=True)

    test_questions = [
        "现在北京时间是几点？",
        "地球赤道周长约 40,075 公里，换算成英里是多少？",
        "如果一个圆的半径是 7，它的面积是多少？（使用 pi * r^2 公式）",
        "100 华氏度等于多少摄氏度？",
        "分析这段文字有多少个字：人工智能是计算机科学的一个分支，它企图了解智能的实质，并生产出一种新的能以人类智能相似的方式做出反应的智能机器。",
    ]

    for question in test_questions:
        print(f"\n{'─'*40}")
        print(f"用户: {question}")
        answer = agent.chat(question)
        print(f"Agent: {answer}")

    print("\n" + "=" * 60)
    print("✅ 演示完成！")


if __name__ == "__main__":
    if not os.environ.get("OPENAI_API_KEY"):
        print("⚠️  请设置 OPENAI_API_KEY 环境变量")
        exit(1)

    run_demo()
