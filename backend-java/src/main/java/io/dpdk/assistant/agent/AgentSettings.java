package io.dpdk.assistant.agent;

/**
 * Agent 编排所需的设置快照（默认值对齐 backend/app/config.py，2026-10 冻结基线）。
 *
 * <p>Java 侧不做 Spring 绑定（装配归集成者）；测试直接构造。字段与 Python
 * settings 一一对应，缺省值即 Python 默认值：
 * <ul>
 *   <li>agentMaxToolRounds=5、agentRequestTokenBudget=48000（0=关闭）</li>
 *   <li>agentContextTrimEnabled=true、ragContextMaxChars=24000（0=关闭）</li>
 *   <li>gradingTimeoutSec=120、gradingMaxCodeBytes=262144、gradingDailyQuota=20（0=不限）</li>
 *   <li>feedbackEscalationThreshold=3、feedbackAllowExplicitAsk=true</li>
 *   <li>agentProfileInjectEnabled=true、referenceAnswerMaxChars=20000</li>
 *   <li>modelName 透传给用量记账（只作观测键）</li>
 * </ul>
 */
public record AgentSettings(
        int agentMaxToolRounds,
        int agentRequestTokenBudget,
        boolean agentContextTrimEnabled,
        int ragContextMaxChars,
        int gradingTimeoutSec,
        int gradingMaxCodeBytes,
        int gradingDailyQuota,
        int feedbackEscalationThreshold,
        boolean feedbackAllowExplicitAsk,
        boolean agentProfileInjectEnabled,
        int referenceAnswerMaxChars,
        String modelName) {

    public static AgentSettings defaults() {
        return new AgentSettings(5, 48000, true, 24000, 120, 262144, 20,
                3, true, true, 20000, "scripted-test-model");
    }

    /** 图内上下文预算口径：修剪开关关闭时为 0（对位 service._build_run）。 */
    public int contextMaxChars() {
        return agentContextTrimEnabled ? ragContextMaxChars : 0;
    }

    public AgentSettings {
        if (agentMaxToolRounds < 1) {
            throw new IllegalArgumentException("agentMaxToolRounds 必须 >= 1");
        }
        if (agentRequestTokenBudget < 0 || ragContextMaxChars < 0 || gradingDailyQuota < 0) {
            throw new IllegalArgumentException("预算/配额不允许负值（0 表示关闭/不限）");
        }
        if (gradingTimeoutSec < 1 || gradingMaxCodeBytes < 1) {
            throw new IllegalArgumentException("gradingTimeoutSec/gradingMaxCodeBytes 必须 >= 1");
        }
        if (feedbackEscalationThreshold < 1) {
            throw new IllegalArgumentException("feedbackEscalationThreshold 必须 >= 1");
        }
        if (referenceAnswerMaxChars < 1000) {
            throw new IllegalArgumentException("referenceAnswerMaxChars 必须 >= 1000");
        }
    }
}
