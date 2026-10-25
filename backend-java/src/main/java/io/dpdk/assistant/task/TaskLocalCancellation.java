package io.dpdk.assistant.task;

import io.dpdk.assistant.admin.LocalTaskCancellationPort;
import java.util.logging.Logger;

/**
 * LocalTaskCancellationPort 的真实执行体（关闭 integration_pending IP-1 的
 * 本进程段；对齐 user_service.set_enabled 的提交后分支
 * task_service.cancel_local_for_user）。
 *
 * 语义：
 * - 库行（pending→cancelled/account_disabled + 输入清理 + 关联成绩删除）已在
 *   停号事务内终态化，本实现只取消本进程残留执行线程并等收口；
 * - 必须自吞异常（最多留日志），不得向停号调用方传播失败——已提交的停号终态
 *   不受本段影响；
 * - 装配：以本实现替换 admin.NoOpLocalTaskCancellation 占位（Spring 接线由
 *   集成者完成，见交接的装配变更申请）。
 */
public final class TaskLocalCancellation implements LocalTaskCancellationPort {

    private static final Logger LOG = Logger.getLogger(TaskLocalCancellation.class.getName());

    private final RagTaskService service;

    public TaskLocalCancellation(RagTaskService service) {
        this.service = service;
    }

    @Override
    public void cancelLocalForUser(String userId) {
        try {
            service.cancelLocalForUser(userId);
        } catch (RuntimeException exc) {
            // 自吞：停号终态已提交，本地取消失败只留警告日志。
            LOG.warning("[task_cancel_on_disable_failed] user_id=" + userId
                    + " error_type=" + exc.getClass().getSimpleName());
        }
    }
}
