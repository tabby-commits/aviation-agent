"""
示例 05 - 多智能体系统 / Multi-Agent System
=============================================
实现一个主管-工作者模式的多智能体系统。
包含三个专业 Agent 协作完成复杂任务：
- ResearchAgent：负责信息收集和研究
- AnalystAgent：负责数据分析和洞察
- WriterAgent：负责内容撰写和总结

运行方式:
    export OPENAI_API_KEY="your-api-key"
    python examples/05_multi_agent.py
"""

import os
import json
from typing import Callable
from openai import OpenAI

client = OpenAI(api_key=os.environ.get("OPENAI_API_KEY"))


# ─── 基础 Agent 类 ───────────────────────────────────────────────────────────

class BaseAgent:
    """所有 Agent 的基类"""

    def __init__(self, name: str, role: str, system_prompt: str):
        self.name = name
        self.role = role
        self.system_prompt = system_prompt
        self.message_log: list[dict] = []  # 记录此 Agent 的所有消息

    def run(self, task: str, context: str = "") -> str:
        """
        执行任务

        Args:
            task: 任务描述
            context: 来自其他 Agent 的上下文信息

        Returns:
            执行结果
        """
        full_prompt = task
        if context:
            full_prompt = f"背景信息:\n{context}\n\n任务:\n{task}"

        messages = [
            {"role": "system", "content": self.system_prompt},
            {"role": "user", "content": full_prompt}
        ]

        response = client.chat.completions.create(
            model="gpt-4o-mini",
            messages=messages,
            temperature=0.3,
            max_tokens=1000
        )

        result = response.choices[0].message.content

        # 记录日志
        self.message_log.append({
            "task": task,
            "context_length": len(context),
            "result_length": len(result)
        })

        return result

    def __repr__(self):
        return f"{self.__class__.__name__}(name={self.name})"


# ─── 专业化 Agent ─────────────────────────────────────────────────────────────

class ResearchAgent(BaseAgent):
    """研究员 Agent：负责信息收集、事实核查、背景调研"""

    def __init__(self):
        super().__init__(
            name="ResearchAgent",
            role="研究员",
            system_prompt="""你是一位专业的研究员，擅长：
- 系统性地收集和整理信息
- 识别关键事实和数据
- 发现不同信息来源之间的联系
- 以结构化方式呈现研究发现

请提供详细、准确的研究结果，包括关键概念、重要数据和主要观点。"""
        )


class AnalystAgent(BaseAgent):
    """分析师 Agent：负责深度分析、模式识别、洞察提炼"""

    def __init__(self):
        super().__init__(
            name="AnalystAgent",
            role="分析师",
            system_prompt="""你是一位专业的数据分析师，擅长：
- 深入分析复杂信息
- 识别规律、趋势和异常
- 评估利弊与风险
- 提炼可操作的洞察

请基于提供的研究信息，给出深度分析和有价值的洞察。"""
        )


class WriterAgent(BaseAgent):
    """写作 Agent：负责内容撰写、结构组织、表达优化"""

    def __init__(self):
        super().__init__(
            name="WriterAgent",
            role="技术作家",
            system_prompt="""你是一位专业的技术写作者，擅长：
- 将复杂信息转化为清晰易懂的内容
- 构建逻辑清晰的文章结构
- 使用准确、简洁的语言表达
- 根据目标受众调整写作风格

请基于提供的研究和分析内容，撰写高质量的最终输出。"""
        )


class CriticAgent(BaseAgent):
    """评审 Agent：负责质量把控、反馈提供、改进建议"""

    def __init__(self):
        super().__init__(
            name="CriticAgent",
            role="评审专家",
            system_prompt="""你是一位严格的质量评审专家，擅长：
- 评估内容的准确性和完整性
- 识别逻辑漏洞和改进空间
- 提供具体、可操作的改进建议
- 确保最终输出达到高质量标准

请对提供的内容进行严格评审，指出优点和需要改进的地方。评审时请使用以下格式：
优点: [列出 2-3 个主要优点]
不足: [列出需要改进的地方]
建议: [具体的改进建议]
综合评分: [1-10分，并给出理由]"""
        )


# ─── 主管 Agent ──────────────────────────────────────────────────────────────

class SupervisorAgent:
    """
    主管 Agent：协调其他 Agent 完成复杂任务

    工作流程：
    1. 接收用户任务
    2. 制定执行计划
    3. 按顺序调度专业 Agent
    4. 汇总结果并生成最终输出
    """

    def __init__(self, verbose: bool = True):
        self.research = ResearchAgent()
        self.analyst = AnalystAgent()
        self.writer = WriterAgent()
        self.critic = CriticAgent()
        self.verbose = verbose

        # 定义标准工作流
        self.workflow: list[dict] = [
            {
                "agent": self.research,
                "task_template": "请对以下主题进行全面研究：{topic}",
                "output_key": "research"
            },
            {
                "agent": self.analyst,
                "task_template": "基于以下研究结果，进行深度分析：{topic}",
                "output_key": "analysis"
            },
            {
                "agent": self.writer,
                "task_template": "请根据研究和分析内容，为以下主题撰写一篇结构清晰的综合报告：{topic}",
                "output_key": "draft"
            },
        ]

    def _log(self, message: str):
        if self.verbose:
            print(message)

    def execute(self, topic: str, with_review: bool = True) -> dict:
        """
        执行完整的多智能体工作流

        Args:
            topic: 研究主题
            with_review: 是否进行评审环节

        Returns:
            包含各阶段结果的字典
        """
        self._log(f"\n{'='*60}")
        self._log(f"🚀 任务开始: {topic}")
        self._log(f"{'='*60}\n")

        outputs = {}
        cumulative_context = ""

        # 执行工作流
        for i, step in enumerate(self.workflow):
            agent = step["agent"]
            task = step["task_template"].format(topic=topic)
            output_key = step["output_key"]

            self._log(f"📌 阶段 {i+1}/{len(self.workflow)}: {agent.role}")
            self._log(f"   执行中...")

            result = agent.run(task, context=cumulative_context)
            outputs[output_key] = result

            # 累积上下文（供后续 Agent 使用）
            cumulative_context += f"\n\n{agent.role}的结果:\n{result}"

            self._log(f"   ✅ 完成 ({len(result)} 字符)\n")

        # 可选：评审环节
        if with_review:
            self._log(f"🔍 阶段 {len(self.workflow)+1}: 评审")
            review = self.critic.run(
                task=f"请评审以下关于'{topic}'的报告",
                context=outputs.get("draft", "")
            )
            outputs["review"] = review
            self._log(f"   ✅ 评审完成\n")

        # 最终汇总
        self._log(f"📝 生成最终报告...")
        final_report = self._generate_final_report(topic, outputs)
        outputs["final_report"] = final_report

        self._log(f"\n{'='*60}")
        self._log("✅ 任务完成！")
        self._log(f"{'='*60}\n")

        return outputs

    def _generate_final_report(self, topic: str, outputs: dict) -> str:
        """整合各阶段结果生成最终报告"""
        context_parts = []
        if "research" in outputs:
            context_parts.append(f"研究结果:\n{outputs['research']}")
        if "analysis" in outputs:
            context_parts.append(f"分析洞察:\n{outputs['analysis']}")
        if "draft" in outputs:
            context_parts.append(f"初稿内容:\n{outputs['draft']}")
        if "review" in outputs:
            context_parts.append(f"评审意见:\n{outputs['review']}")

        response = client.chat.completions.create(
            model="gpt-4o-mini",
            messages=[
                {
                    "role": "system",
                    "content": "你是一个内容整合专家。请将各阶段的工作成果整合成一份完整、高质量的最终报告。"
                },
                {
                    "role": "user",
                    "content": (
                        f"主题: {topic}\n\n"
                        + "\n\n---\n\n".join(context_parts)
                        + "\n\n请基于以上内容，生成一份结构完整、逻辑清晰的最终报告。"
                    )
                }
            ],
            max_tokens=1500
        )

        return response.choices[0].message.content


# ─── 两个 Agent 对话协作 ──────────────────────────────────────────────────────

def collaborative_dialogue(topic: str, rounds: int = 3):
    """
    两个 Agent 通过对话协作改进内容

    Args:
        topic: 讨论主题
        rounds: 对话轮数
    """
    print(f"\n{'='*60}")
    print(f"🤝 协作对话模式: {topic}")
    print(f"{'='*60}\n")

    proposer = BaseAgent(
        name="Proposer",
        role="提案者",
        system_prompt="你是一个积极的提案者，负责提出创意方案和解决思路。回答要具体、有建设性。"
    )

    critic = BaseAgent(
        name="Critic",
        role="批评者",
        system_prompt="你是一个建设性的批评者，负责指出方案的不足并提出改进建议。既要找问题，也要提供解决方向。"
    )

    # 初始提案
    proposal = proposer.run(f"请为'{topic}'提出一个初步方案或解决思路")
    print(f"提案者: {proposal}\n")

    # 迭代改进
    for i in range(rounds):
        print(f"{'─'*40}")
        print(f"[第 {i+1} 轮迭代]")

        # 批评
        feedback = critic.run(f"请评价以下方案并提出改进建议", context=proposal)
        print(f"批评者: {feedback}\n")

        # 改进
        proposal = proposer.run(
            f"请根据批评意见改进'{topic}'的方案",
            context=f"当前方案:\n{proposal}\n\n收到的反馈:\n{feedback}"
        )
        print(f"提案者（改进后）: {proposal}\n")

    print(f"\n✅ 经过 {rounds} 轮迭代的最终方案已完成")
    return proposal


# ─── 演示 ─────────────────────────────────────────────────────────────────────

def run_supervisor_demo():
    """主管-工作者模式演示"""
    supervisor = SupervisorAgent(verbose=True)
    outputs = supervisor.execute(
        topic="AI Agent 技术在企业中的应用前景与挑战",
        with_review=True
    )

    print("\n" + "=" * 60)
    print("📄 最终报告")
    print("=" * 60)
    print(outputs["final_report"])


def run_dialogue_demo():
    """协作对话模式演示"""
    collaborative_dialogue(
        topic="如何设计一个高效的 AI Agent 记忆系统",
        rounds=2
    )


if __name__ == "__main__":
    if not os.environ.get("OPENAI_API_KEY"):
        print("⚠️  请设置 OPENAI_API_KEY 环境变量")
        exit(1)

    import sys
    if len(sys.argv) > 1 and sys.argv[1] == "--dialogue":
        run_dialogue_demo()
    else:
        run_supervisor_demo()
