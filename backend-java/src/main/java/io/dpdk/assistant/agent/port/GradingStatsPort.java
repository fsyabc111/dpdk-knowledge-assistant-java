package io.dpdk.assistant.agent.port;

/**
 * 成绩统计读取端口（提示词升级与学习者画像用；对位 GradingService 的两个读方法）。
 *
 * <ul>
 *   <li>recentFailCount：最近 sample 条（默认 10）提交中 passed&lt;total 的条数，
 *       驱动 §4.3 反馈升级条件（达 feedback_escalation_threshold 升定位档）。</li>
 *   <li>learnerProfile：各协议尝试次数/最高分/高频失败用例名（每协议至多 2 个），
 *       总预算 300 字符；无提交记录返回空串（调用方据此跳过注入）。</li>
 * </ul>
 * 统计失败不阻断主流程：实现方失败时返回 0/空串或抛异常由调用方兜底。
 */
public interface GradingStatsPort {

    int recentFailCount(String userId, String protocol, int sample);

    String learnerProfile(String userId);
}
