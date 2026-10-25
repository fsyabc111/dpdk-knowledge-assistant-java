package io.dpdk.assistant.chat;

import java.time.Duration;

/** 产品问答设置，默认值对齐冻结 Python config.py；装配不隐式访问上游。 */
public record ChatSettings(
        Duration requestTimeout,
        Duration prepareTimeout,
        Duration validationTimeout,
        int evidenceMaxChars,
        int requestTokenBudget,
        int ratePerMinute,
        int recentTurns,
        int summaryMaxChars,
        int memoryMaxChars,
        int summaryMaxTokens,
        boolean onlineJudgeEnabled) {
    public static ChatSettings defaults() {
        return new ChatSettings(
                Duration.ofSeconds(120),
                Duration.ofSeconds(15),
                Duration.ofSeconds(60),
                24000,
                48000,
                20,
                6,
                4800,
                24000,
                800,
                false);
    }

    public ChatSettings {
        if (requestTimeout.isNegative()
                || requestTimeout.isZero()
                || prepareTimeout.isNegative()
                || prepareTimeout.isZero()
                || validationTimeout.isNegative()
                || validationTimeout.isZero()
                || evidenceMaxChars < 1
                || requestTokenBudget < 1
                || ratePerMinute < 1
                || recentTurns < 0
                || summaryMaxChars < 0
                || memoryMaxChars < 0
                || summaryMaxTokens < 0) {
            throw new IllegalArgumentException("产品问答配置超出冻结合法范围");
        }
    }
}
