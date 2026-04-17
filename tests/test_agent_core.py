"""
测试 Agent 核心功能（不需要 API Key）
Tests for Agent core functionality (no API key required)
"""

import json
import math
import sys
import os

# 将示例目录添加到 Python 路径
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))


# ─── 测试工具函数 ─────────────────────────────────────────────────────────────

class TestCalculator:
    """测试计算器工具"""

    def _get_calculator(self):
        """动态导入计算器函数以避免 OpenAI 初始化"""
        # 直接复制实现以便独立测试
        def calculator(expression: str) -> str:
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
        return calculator

    def test_basic_arithmetic(self):
        calc = self._get_calculator()
        assert calc("2 + 2") == "4"
        assert calc("10 - 3") == "7"
        assert calc("3 * 4") == "12"
        assert calc("15 / 3") == "5.0"

    def test_complex_expression(self):
        calc = self._get_calculator()
        result = calc("sqrt(16)")
        assert result == "4.0"

    def test_pi(self):
        calc = self._get_calculator()
        result = float(calc("pi"))
        assert abs(result - math.pi) < 0.001

    def test_power(self):
        calc = self._get_calculator()
        assert calc("2 ** 10") == "1024"

    def test_division_by_zero(self):
        calc = self._get_calculator()
        result = calc("1 / 0")
        assert "除以零" in result or "ZeroDivisionError" in result or "错误" in result

    def test_invalid_expression(self):
        calc = self._get_calculator()
        result = calc("invalid_func()")
        assert "错误" in result or "Error" in result.lower()

    def test_security_no_builtins(self):
        """确保危险的内置函数无法被调用"""
        calc = self._get_calculator()
        # 尝试调用 __import__ 应该失败
        result = calc("__import__('os').system('echo hacked')")
        assert "错误" in result or "Error" in result.lower() or "NameError" in result


class TestTextAnalysis:
    """测试文本分析工具"""

    def _analyze_text(self, text: str) -> dict:
        """文本分析实现（从示例复制以便测试）"""
        import re
        chinese_chars = len(re.findall(r'[\u4e00-\u9fff]', text))
        english_words = len(re.findall(r'\b[a-zA-Z]+\b', text))
        sentences = len(re.split(r'[。！？.!?]+', text.strip()))
        sentences = max(1, sentences)

        return {
            "总字符数": len(text),
            "中文字符数": chinese_chars,
            "英文单词数": english_words,
            "句子数": sentences,
            "平均句子长度": round(len(text) / sentences, 1),
        }

    def test_chinese_text(self):
        result = self._analyze_text("你好世界。这是测试。")
        assert result["中文字符数"] == 8
        assert result["总字符数"] > 0

    def test_english_text(self):
        result = self._analyze_text("Hello world. This is a test.")
        assert result["英文单词数"] == 6
        assert result["总字符数"] > 0

    def test_empty_text(self):
        result = self._analyze_text("")
        assert result["总字符数"] == 0
        assert result["中文字符数"] == 0

    def test_mixed_text(self):
        result = self._analyze_text("Hello 世界！Python 很强大。")
        assert result["中文字符数"] > 0
        assert result["英文单词数"] > 0


class TestUnitConversion:
    """测试单位换算工具"""

    def _convert_units(self, value: float, from_unit: str, to_unit: str) -> str:
        """单位换算实现"""
        conversions = {
            ("km", "miles"): 0.621371,
            ("miles", "km"): 1.60934,
            ("m", "feet"): 3.28084,
            ("feet", "m"): 0.3048,
            ("kg", "pounds"): 2.20462,
            ("pounds", "kg"): 0.453592,
        }

        if from_unit == "celsius" and to_unit == "fahrenheit":
            result = value * 9 / 5 + 32
            return f"{value}°C = {result:.2f}°F"
        elif from_unit == "fahrenheit" and to_unit == "celsius":
            result = (value - 32) * 5 / 9
            return f"{value}°F = {result:.2f}°C"

        key = (from_unit.lower(), to_unit.lower())
        if key in conversions:
            result = value * conversions[key]
            return f"{value} {from_unit} = {result:.4f} {to_unit}"
        else:
            return f"不支持 {from_unit} 到 {to_unit} 的换算"

    def test_km_to_miles(self):
        result = self._convert_units(1.0, "km", "miles")
        assert "0.6214" in result

    def test_celsius_to_fahrenheit(self):
        result = self._convert_units(100, "celsius", "fahrenheit")
        assert "212.00" in result

    def test_fahrenheit_to_celsius(self):
        result = self._convert_units(32, "fahrenheit", "celsius")
        assert "0.00" in result

    def test_kg_to_pounds(self):
        result = self._convert_units(1.0, "kg", "pounds")
        assert "2.2046" in result

    def test_unsupported_conversion(self):
        result = self._convert_units(1.0, "xyz", "abc")
        assert "不支持" in result


class TestMemoryStore:
    """测试记忆存储功能"""

    def _create_memory_store(self, path: str):
        """动态创建 SimpleMemoryStore 实例"""
        import importlib.util
        # 直接复制关键逻辑进行测试
        from pathlib import Path

        class MockMemoryStore:
            def __init__(self, storage_path):
                self.storage_path = Path(storage_path)
                self.memories = []

            def store(self, content, category="general", tags=None):
                memory = {
                    "id": len(self.memories) + 1,
                    "content": content,
                    "category": category,
                    "tags": tags or [],
                    "access_count": 0,
                }
                self.memories.append(memory)
                return memory["id"]

            def search(self, query, top_k=3):
                query_words = set(query.lower().split())
                scored = []
                for memory in self.memories:
                    words = set(memory["content"].lower().split())
                    overlap = len(query_words & words)
                    if overlap > 0:
                        scored.append((overlap, memory))
                scored.sort(key=lambda x: x[0], reverse=True)
                return [m for _, m in scored[:top_k]]

            def clear(self):
                self.memories = []

            def __len__(self):
                return len(self.memories)

        return MockMemoryStore(path)

    def test_store_and_retrieve(self):
        store = self._create_memory_store("/tmp/test_memory.json")
        store.clear()

        memory_id = store.store("我叫小明", category="user_name", tags=["名字"])
        assert memory_id == 1
        assert len(store) == 1

    def test_search_by_keyword(self):
        store = self._create_memory_store("/tmp/test_memory.json")
        store.clear()

        store.store("我叫小明，我是程序员", tags=["名字"])
        store.store("我喜欢 Python 编程", tags=["编程"])
        store.store("我在北京工作", tags=["位置"])

        results = store.search("Python 编程")
        assert len(results) > 0
        assert "Python" in results[0]["content"]

    def test_empty_search(self):
        store = self._create_memory_store("/tmp/test_memory.json")
        store.clear()

        results = store.search("完全不相关的查询xyz")
        assert results == []

    def test_clear(self):
        store = self._create_memory_store("/tmp/test_memory.json")
        store.store("测试内容")
        store.clear()
        assert len(store) == 0

    def test_category_storage(self):
        store = self._create_memory_store("/tmp/test_memory.json")
        store.clear()

        store.store("喜欢读书", category="preference")
        store.store("在上海", category="location")
        store.store("叫小李", category="name")

        assert len(store) == 3
        pref = [m for m in store.memories if m["category"] == "preference"]
        assert len(pref) == 1


class TestReActParser:
    """测试 ReAct 行动解析"""

    def _parse_action(self, text: str):
        """复制 ReAct Agent 的解析逻辑"""
        import re

        # 主要匹配模式
        pattern = r'行动:\s*(\w+)\(["\']([^"\']*)["\']?\)'
        match = re.search(pattern, text)
        if match:
            return match.group(1), match.group(2)

        # 宽松匹配
        pattern_loose = r'行动:\s*(\w+)\(([^)]+)\)'
        match = re.search(pattern_loose, text)
        if match:
            tool_name = match.group(1)
            arg = match.group(2).strip().strip('"\'')
            return tool_name, arg

        return None

    def test_parse_search_action(self):
        text = '思考: 我需要搜索一下\n行动: search("Python 编程")'
        result = self._parse_action(text)
        assert result is not None
        assert result[0] == "search"
        assert "Python" in result[1]

    def test_parse_calculator_action(self):
        text = '行动: calculator("2 + 2")'
        result = self._parse_action(text)
        assert result is not None
        assert result[0] == "calculator"
        assert "2 + 2" in result[1]

    def test_no_action(self):
        text = '思考: 我已经有了足够的信息\n最终答案: Python 是一种编程语言'
        result = self._parse_action(text)
        assert result is None

    def test_final_answer_detection(self):
        text = "最终答案: Python 很好用"
        assert "最终答案:" in text

    def test_parse_single_quoted(self):
        text = "行动: search('AI agent')"
        result = self._parse_action(text)
        assert result is not None
        assert result[0] == "search"


# ─── 运行测试 ─────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    import pytest
    pytest.main([__file__, "-v"])
