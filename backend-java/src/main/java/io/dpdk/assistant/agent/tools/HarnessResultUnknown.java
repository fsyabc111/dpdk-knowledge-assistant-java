package io.dpdk.assistant.agent.tools;

/** COMP-003：已有提交结果不可确认，停止自动重评，由产品层保存受控终态。 */
public final class HarnessResultUnknown extends RuntimeException {
    public static final String CODE = "harness_result_unknown";
    public HarnessResultUnknown() { super(CODE); }
}
