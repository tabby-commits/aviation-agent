# 02 - 工具与函数调用 / Tools and Function Calling

## 概述

工具调用（Tool Use / Function Calling）是 Agent 能力的核心。通过工具，Agent 可以突破 LLM 的固有限制，与外部世界进行交互。

---

## OpenAI Function Calling

OpenAI 的 Function Calling 允许你定义函数的 JSON Schema，模型会返回结构化的函数调用请求。

### 定义工具

```python
tools = [
    {
        "type": "function",
        "function": {
            "name": "get_weather",
            "description": "获取指定城市的当前天气",
            "parameters": {
                "type": "object",
                "properties": {
                    "city": {
                        "type": "string",
                        "description": "城市名称，如 '北京' 或 'Beijing'"
                    },
                    "unit": {
                        "type": "string",
                        "enum": ["celsius", "fahrenheit"],
                        "description": "温度单位"
                    }
                },
                "required": ["city"]
            }
        }
    }
]
```

### 完整调用流程

```python
from openai import OpenAI
import json

client = OpenAI()

def get_weather(city: str, unit: str = "celsius") -> dict:
    """模拟天气 API"""
    return {"city": city, "temperature": 22, "unit": unit, "condition": "晴天"}

def run_with_tools(user_message: str) -> str:
    messages = [{"role": "user", "content": user_message}]
    
    # 第一次调用：让模型决定是否调用工具
    response = client.chat.completions.create(
        model="gpt-4o-mini",
        messages=messages,
        tools=tools,
        tool_choice="auto"  # 让模型自动决定
    )
    
    message = response.choices[0].message
    
    # 如果模型决定调用工具
    if message.tool_calls:
        messages.append(message)  # 添加助手消息
        
        # 执行每个工具调用
        for tool_call in message.tool_calls:
            func_name = tool_call.function.name
            func_args = json.loads(tool_call.function.arguments)
            
            # 调用实际函数
            if func_name == "get_weather":
                result = get_weather(**func_args)
            
            # 将工具结果添加到消息
            messages.append({
                "role": "tool",
                "tool_call_id": tool_call.id,
                "content": json.dumps(result, ensure_ascii=False)
            })
        
        # 第二次调用：让模型根据工具结果生成最终回答
        final_response = client.chat.completions.create(
            model="gpt-4o-mini",
            messages=messages
        )
        return final_response.choices[0].message.content
    
    return message.content

# 使用示例
result = run_with_tools("北京今天天气怎么样？")
print(result)
```

---

## Anthropic Tool Use

```python
import anthropic
import json

client = anthropic.Anthropic()

tools = [
    {
        "name": "calculator",
        "description": "执行数学计算",
        "input_schema": {
            "type": "object",
            "properties": {
                "expression": {
                    "type": "string",
                    "description": "要计算的数学表达式，如 '2 + 2' 或 'sqrt(16)'"
                }
            },
            "required": ["expression"]
        }
    }
]

def calculator(expression: str) -> float:
    import math
    # 注意：生产环境中需要使用安全的表达式求值
    return eval(expression, {"__builtins__": {}}, {"sqrt": math.sqrt, "pi": math.pi})

def run_claude_with_tools(user_message: str) -> str:
    messages = [{"role": "user", "content": user_message}]
    
    while True:
        response = client.messages.create(
            model="claude-3-5-haiku-20241022",
            max_tokens=1024,
            tools=tools,
            messages=messages
        )
        
        # 停止条件：模型完成或没有工具调用
        if response.stop_reason == "end_turn":
            # 提取文本响应
            for block in response.content:
                if hasattr(block, "text"):
                    return block.text
        
        if response.stop_reason == "tool_use":
            messages.append({"role": "assistant", "content": response.content})
            
            tool_results = []
            for block in response.content:
                if block.type == "tool_use":
                    if block.name == "calculator":
                        result = calculator(block.input["expression"])
                        tool_results.append({
                            "type": "tool_result",
                            "tool_use_id": block.id,
                            "content": str(result)
                        })
            
            messages.append({"role": "user", "content": tool_results})
```

---

## 工具设计最佳实践

### 1. 清晰的工具描述

```python
# ❌ 差的描述
{"name": "search", "description": "搜索"}

# ✅ 好的描述
{
    "name": "web_search",
    "description": "在互联网上搜索实时信息。当需要最新事件、当前数据或需要验证事实时使用此工具。",
    "parameters": {
        "query": {
            "type": "string",
            "description": "搜索查询词，使用关键词而不是完整句子效果更好"
        }
    }
}
```

### 2. 参数验证

```python
from pydantic import BaseModel, Field
from typing import Optional

class SearchParams(BaseModel):
    query: str = Field(description="搜索查询词")
    max_results: int = Field(default=5, ge=1, le=20, description="返回结果数量")
    language: Optional[str] = Field(default="zh", description="结果语言")

def web_search(params: SearchParams) -> list[dict]:
    """经过验证的搜索函数"""
    validated = SearchParams(**params)
    # 执行搜索...
```

### 3. 错误处理

```python
def safe_tool_call(tool_name: str, tool_args: dict) -> str:
    try:
        result = execute_tool(tool_name, tool_args)
        return json.dumps(result, ensure_ascii=False)
    except ValueError as e:
        return f"参数错误: {str(e)}"
    except ConnectionError as e:
        return f"网络错误，请稍后重试: {str(e)}"
    except Exception as e:
        return f"工具执行失败: {str(e)}"
```

---

## 常用工具实现

### 网络搜索工具

```python
def web_search(query: str, max_results: int = 5) -> list[dict]:
    """使用 Tavily API 进行网络搜索"""
    from tavily import TavilyClient
    client = TavilyClient(api_key="your-tavily-api-key")
    response = client.search(query=query, max_results=max_results)
    return response["results"]
```

### Python 代码执行工具

```python
import subprocess
import tempfile
import os

def execute_python(code: str, timeout: int = 30) -> dict:
    """在隔离环境中执行 Python 代码"""
    with tempfile.NamedTemporaryFile(mode='w', suffix='.py', delete=False) as f:
        f.write(code)
        temp_file = f.name
    
    try:
        result = subprocess.run(
            ["python", temp_file],
            capture_output=True,
            text=True,
            timeout=timeout
        )
        return {
            "stdout": result.stdout,
            "stderr": result.stderr,
            "returncode": result.returncode
        }
    except subprocess.TimeoutExpired:
        return {"error": "代码执行超时"}
    finally:
        os.unlink(temp_file)
```

### 文件读写工具

```python
import os
from pathlib import Path

def read_file(filepath: str) -> str:
    """读取文件内容"""
    path = Path(filepath)
    if not path.exists():
        return f"错误：文件 {filepath} 不存在"
    if not path.is_file():
        return f"错误：{filepath} 不是文件"
    return path.read_text(encoding="utf-8")

def write_file(filepath: str, content: str) -> str:
    """写入文件内容"""
    path = Path(filepath)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return f"成功写入 {len(content)} 个字符到 {filepath}"
```

---

## 下一步

👉 [03 - ReAct 推理模式](03-react-pattern.md)
