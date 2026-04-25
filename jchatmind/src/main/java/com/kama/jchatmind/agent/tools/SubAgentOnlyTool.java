package com.kama.jchatmind.agent.tools;

/**
 * Marker for tools that are only available to delegated sub-agents.
 * ToolFacadeService excludes these from the main agent tool list.
 */
public interface SubAgentOnlyTool extends Tool {
}
