package io.dpdk.assistant.contracts.task;

/** 受理探查结果（miss/in_progress/terminal/replay）；字段由 J3-01 登记补全。 */
public record Probe(String state) {
}
