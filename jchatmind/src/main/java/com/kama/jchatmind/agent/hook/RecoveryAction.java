package com.kama.jchatmind.agent.hook;

public enum RecoveryAction {
    CONTINUE,
    RETRY_TOOL,
    REDELEGATE_SUBTASK,
    SYNTHESIZE_FINAL_ANSWER,
    STOP_WITH_PARTIAL_RESULT
}
