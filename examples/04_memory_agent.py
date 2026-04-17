"""
示例 04 - 带记忆的 Agent / Memory Agent
=========================================
演示两种记忆机制：
1. 短期记忆（对话历史）+ 滑动窗口
2. 文件持久化的长期记忆（无需向量数据库）

运行方式:
    export OPENAI_API_KEY="your-api-key"
    python examples/04_memory_agent.py
"""

import os
import json
from datetime import datetime
from pathlib import Path
from openai import OpenAI

client = OpenAI(api_key=os.environ.get("OPENAI_API_KEY"))


# ─── 长期记忆存储（文件持久化版）────────────────────────────────────────────────

class SimpleMemoryStore:
    """
    简单的文件持久化记忆存储

    不依赖向量数据库，使用 JSON 文件存储，
    通过关键词匹配进行记忆检索。
    适合学习目的，生产环境建议使用向量数据库。
    """

    def __init__(self, storage_path: str = "/tmp/agent_memory.json"):
        self.storage_path = Path(storage_path)
        self.memories: list[dict] = []
        self._load()

    def _load(self):
        """从文件加载记忆"""
        if self.storage_path.exists():
            with open(self.storage_path, "r", encoding="utf-8") as f:
                self.memories = json.load(f)

    def _save(self):
        """保存记忆到文件"""
        with open(self.storage_path, "w", encoding="utf-8") as f:
            json.dump(self.memories, f, ensure_ascii=False, indent=2)

    def store(self, content: str, category: str = "general", tags: list[str] | None = None):
        """
        存储一条记忆

        Args:
            content: 记忆内容
            category: 分类（如 "preference"、"fact"、"task"）
            tags: 关键标签列表
        """
        memory = {
            "id": len(self.memories) + 1,
            "content": content,
            "category": category,
            "tags": tags or [],
            "timestamp": datetime.now().isoformat(),
            "access_count": 0,
        }
        self.memories.append(memory)
        self._save()
        return memory["id"]

    def search(self, query: str, top_k: int = 3) -> list[dict]:
        """
        基于关键词搜索相关记忆

        Args:
            query: 搜索查询
            top_k: 返回结果数量

        Returns:
            相关记忆列表
        """
        query_words = set(query.lower().split())
        scored_memories = []

        for memory in self.memories:
            content_words = set(memory["content"].lower().split())
            tag_words = set(tag.lower() for tag in memory["tags"])
            all_words = content_words | tag_words

            # 计算词语重叠分数
            overlap = len(query_words & all_words)
            if overlap > 0:
                scored_memories.append((overlap, memory))

        # 按分数降序排序
        scored_memories.sort(key=lambda x: x[0], reverse=True)

        results = [m for _, m in scored_memories[:top_k]]

        # 更新访问计数
        for memory in results:
            memory["access_count"] += 1
        if results:
            self._save()

        return results

    def get_by_category(self, category: str) -> list[dict]:
        """按分类获取记忆"""
        return [m for m in self.memories if m["category"] == category]

    def clear(self):
        """清空所有记忆"""
        self.memories = []
        self._save()

    def __len__(self):
        return len(self.memories)


# ─── 带记忆的 Agent ─────────────────────────────────────────────────────────

class MemoryAgent:
    """
    带长期记忆能力的 Agent

    功能：
    - 短期记忆：维护当前会话对话历史
    - 长期记忆：跨会话持久化存储用户信息
    - 自动提取：对话中自动识别并存储重要信息
    - 记忆检索：回答时自动检索相关历史记忆
    """

    # 触发长期记忆存储的关键词
    MEMORY_TRIGGER_PATTERNS = [
        ("我叫", "user_name"),
        ("我的名字是", "user_name"),
        ("我喜欢", "preference"),
        ("我讨厌", "preference"),
        ("我不喜欢", "preference"),
        ("我在", "location"),
        ("我是", "identity"),
        ("我需要", "need"),
        ("请记住", "important"),
        ("记住我", "important"),
        ("我的目标", "goal"),
        ("我正在学习", "learning"),
        ("我会", "skill"),
        ("我擅长", "skill"),
    ]

    def __init__(self, memory_path: str = "/tmp/agent_memory.json", verbose: bool = True):
        self.short_term: list[dict] = []
        self.long_term = SimpleMemoryStore(storage_path=memory_path)
        self.verbose = verbose
        self.session_start = datetime.now()

    def _should_store_memory(self, text: str) -> tuple[bool, str]:
        """
        判断是否需要将消息存储到长期记忆

        Returns:
            (should_store, category)
        """
        for pattern, category in self.MEMORY_TRIGGER_PATTERNS:
            if pattern in text:
                return True, category
        return False, ""

    def _extract_facts(self, user_input: str, assistant_reply: str) -> list[tuple[str, str]]:
        """从对话中提取需要记忆的事实"""
        facts = []

        # 检查用户输入是否包含个人信息
        should_store, category = self._should_store_memory(user_input)
        if should_store:
            facts.append((user_input, category))

        return facts

    def _build_system_prompt(self, query: str) -> str:
        """构建包含相关记忆的系统提示"""
        base_prompt = "你是一个有记忆能力的个人助手。你能记住用户的偏好、习惯和重要信息，并在对话中灵活运用。"

        # 检索相关长期记忆
        relevant_memories = self.long_term.search(query, top_k=3)

        if relevant_memories:
            memory_text = "\n".join(
                f"- [{m['category']}] {m['content']}" for m in relevant_memories
            )
            base_prompt += f"\n\n你记得以下关于用户的信息：\n{memory_text}"
            if self.verbose:
                print(f"  💭 检索到 {len(relevant_memories)} 条相关记忆")

        return base_prompt

    def chat(self, user_input: str) -> str:
        """
        与用户对话

        Args:
            user_input: 用户输入

        Returns:
            Agent 回复
        """
        system_prompt = self._build_system_prompt(user_input)

        # 构建消息（保留最近 10 条历史）
        messages = [
            {"role": "system", "content": system_prompt},
            *self.short_term[-10:],
            {"role": "user", "content": user_input}
        ]

        response = client.chat.completions.create(
            model="gpt-4o-mini",
            messages=messages,
            temperature=0.7
        )

        reply = response.choices[0].message.content

        # 更新短期记忆
        self.short_term.append({"role": "user", "content": user_input})
        self.short_term.append({"role": "assistant", "content": reply})

        # 提取并存储重要信息到长期记忆
        facts = self._extract_facts(user_input, reply)
        for fact_content, category in facts:
            # 提取关键词作为标签
            tags = [word for word in user_input.split() if len(word) > 1][:5]
            memory_id = self.long_term.store(fact_content, category=category, tags=tags)
            if self.verbose:
                print(f"  💾 存储记忆 #{memory_id}: [{category}] {fact_content[:50]}")

        return reply

    def show_memories(self):
        """显示所有存储的记忆"""
        if not self.long_term.memories:
            print("📭 记忆库为空")
            return

        print(f"\n📚 记忆库（共 {len(self.long_term)} 条）:")
        for memory in self.long_term.memories:
            print(f"  #{memory['id']} [{memory['category']}] {memory['content'][:60]}")
            print(f"       标签: {', '.join(memory['tags'])} | 访问: {memory['access_count']}次")

    def clear_memories(self):
        """清空长期记忆"""
        self.long_term.clear()
        self.short_term = []
        print("✅ 记忆已清空")


# ─── 演示 ─────────────────────────────────────────────────────────────────────

def run_memory_demo():
    """运行记忆 Agent 演示"""
    print("=" * 60)
    print("记忆 Agent 演示 / Memory Agent Demo")
    print("=" * 60)

    # 使用固定文件路径演示跨会话记忆
    agent = MemoryAgent(memory_path="/tmp/demo_memory.json", verbose=True)

    # 清空之前的演示数据，开始新演示
    agent.clear_memories()

    conversations = [
        ("我叫小明，我是一名 Python 开发者", "第一轮：自我介绍"),
        ("我喜欢函数式编程，不喜欢写文档", "第二轮：偏好"),
        ("我正在学习 AI Agent 开发", "第三轮：学习目标"),
        ("你还记得我叫什么名字吗？我在学什么？", "第四轮：测试记忆"),
        ("根据我的偏好，你觉得 LangChain 还是直接用 OpenAI API 更适合我？", "第五轮：个性化建议"),
    ]

    for user_input, description in conversations:
        print(f"\n{'─'*40}")
        print(f"[{description}]")
        print(f"用户: {user_input}")
        reply = agent.chat(user_input)
        print(f"Agent: {reply}")

    # 显示记忆库
    print(f"\n{'─'*40}")
    agent.show_memories()


if __name__ == "__main__":
    if not os.environ.get("OPENAI_API_KEY"):
        print("⚠️  请设置 OPENAI_API_KEY 环境变量")
        exit(1)

    run_memory_demo()
