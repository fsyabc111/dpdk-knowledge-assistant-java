/**
 * 持久任务生命周期与恢复（J3-01）。
 *
 * 分层：内存运行句柄/订阅日志（ManagedTask/ManagedTaskRegistry/TaskRunner/
 * AdmissionGate/TaskEventStreams）与数据库 pending/终态（TaskStore）分开；
 * RagTaskService 是冻结端口（AdmissionPort/LifecyclePort/AnswerCompletion）的
 * 实现与任务状态 API 承担方；TaskGradeLedger 接线 contracts.grading.
 * TaskGradeParticipant；TaskLocalCancellation 接线停号本进程取消。
 *
 * Python 行为基线：backend/app/services/task_service.py、rag_issue_service.py、
 * rag_product_service.py（Admission/prepare）、api/tasks.py。
 */
package io.dpdk.assistant.task;
