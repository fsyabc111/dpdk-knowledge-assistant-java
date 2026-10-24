"""任务级可重驱动异常：输入与 job 账本保留，等待孤儿恢复。"""


class RetryableTaskFailure(Exception):
    """数据库暂不可用导致步骤未提交；不得把请求收成正常终态。"""
