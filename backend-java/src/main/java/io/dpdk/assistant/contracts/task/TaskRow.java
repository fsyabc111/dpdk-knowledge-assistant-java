package io.dpdk.assistant.contracts.task;

/** 任务行（status/终态字段由 J3-01 登记补全）。 */
public record TaskRow(String taskId, String status) {
}
