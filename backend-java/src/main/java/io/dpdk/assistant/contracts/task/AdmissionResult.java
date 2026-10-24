package io.dpdk.assistant.contracts.task;

/** 受理结果（attach/replay/created 分类由 J3-01 实现）；本类型为端口占位，字段后续登记。 */
public record AdmissionResult(String outcome) {
}
