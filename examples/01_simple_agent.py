"""
示例 01 - 简单 Agent / Simple Agent
=====================================
这是最基础的 Agent 实现，展示如何使用 OpenAI API 构建一个能够
记住对话历史的简单聊天 Agent。

运行方式:
    export OPENAI_API_KEY="your-api-key"
    python examples/01_simple_agent.py
"""

import os
from openai import OpenAI

client = OpenAI(api_key=os.environ.get("OPENAI_API_KEY"))


class SimpleAgent:
    """
    最简单的 Agent 实现：
    - 维护对话历史（短期记忆）
    - 单次 LLM 调用（无工具使用）
    - 支持自定义系统提示
    """

    def __init__(self, system_prompt: str = "你是一个有帮助的 AI 助手。请用中文回答。"):
        self.system_prompt = system_prompt
        self.conversation_history: list[dict] = []

    def chat(self, user_message: str) -> str:
        """
        发送消息并获取回复

        Args:
            user_message: 用户输入的消息

        Returns:
            Agent 的回复
        """
        # 将用户消息添加到历史
        self.conversation_history.append({
            "role": "user",
            "content": user_message
        })

        # 构建完整的消息列表（系统提示 + 历史记录）
        messages = [
            {"role": "system", "content": self.system_prompt},
            *self.conversation_history
        ]

        # 调用 LLM
        response = client.chat.completions.create(
            model="gpt-4o-mini",
            messages=messages,
            temperature=0.7,
            max_tokens=1000
        )

        assistant_reply = response.choices[0].message.content

        # 将 Agent 回复添加到历史
        self.conversation_history.append({
            "role": "assistant",
            "content": assistant_reply
        })

        return assistant_reply

    def reset(self):
        """清空对话历史"""
        self.conversation_history = []

    @property
    def turn_count(self) -> int:
        """当前对话轮数"""
        return len(self.conversation_history) // 2


def run_interactive_demo():
    """运行交互式演示"""
    print("=" * 60)
    print("简单 Agent 演示 / Simple Agent Demo")
    print("=" * 60)
    print("输入 'quit' 退出，输入 'reset' 清空对话历史\n")

    agent = SimpleAgent(
        system_prompt=(
            "你是一个友好的 AI 学习助手，专门帮助用户学习 AI Agent 开发。"
            "回答要简洁清晰，并适当提供代码示例。"
        )
    )

    while True:
        try:
            user_input = input(f"[第 {agent.turn_count + 1} 轮] 你: ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\n再见！")
            break

        if not user_input:
            continue
        if user_input.lower() == "quit":
            print("再见！")
            break
        if user_input.lower() == "reset":
            agent.reset()
            print("✅ 对话历史已清空\n")
            continue

        print("\nAgent: ", end="", flush=True)
        reply = agent.chat(user_input)
        print(reply)
        print()


def run_automated_demo():
    """运行自动演示（无需用户输入）"""
    print("=" * 60)
    print("简单 Agent 自动演示")
    print("=" * 60)

    agent = SimpleAgent()

    conversations = [
        "什么是 AI Agent？",
        "它和普通的 LLM 调用有什么区别？",
        "能列举几个实际应用场景吗？",
    ]

    for question in conversations:
        print(f"\n用户: {question}")
        reply = agent.chat(question)
        print(f"Agent: {reply}")
        print("-" * 40)


if __name__ == "__main__":
    # 检查 API Key
    if not os.environ.get("OPENAI_API_KEY"):
        print("⚠️  请设置 OPENAI_API_KEY 环境变量")
        print("   export OPENAI_API_KEY='your-api-key-here'")
        exit(1)

    # 运行演示
    import sys
    if len(sys.argv) > 1 and sys.argv[1] == "--auto":
        run_automated_demo()
    else:
        run_interactive_demo()
