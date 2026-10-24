-- V0014：聊天评测成绩与任务作业账本唯一关联（等价 alembic 0014_grading_task_job_id）。
-- 幂等形式与源 revision 一致；仅合成0013→升级路径执行，空库 B0015 已含此变更。
ALTER TABLE grading_submissions ADD COLUMN IF NOT EXISTS task_job_id TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS idx_grading_task_job_id
    ON grading_submissions(task_job_id) WHERE task_job_id IS NOT NULL;
