// 成绩派生纯函数（B1 修复）。
//
// 前端没有 DOM 测试基建：package.json 只有 vitest，无 jsdom / testing-library，
// 加依赖属于夹带。把「前端读哪个字段」抽成纯函数，是为了能用现有 vitest 锁住
// 取值表达式与后端契约一致——B1 正是这类缺陷（六处字段名错位，页面静默渲染
// 为 —/0，静态审查长期未发现）。
//
// 后端契约来源 backend/app/schemas/assignments.py：
//   AssignmentGradesResponse = { assignment: AssignmentInfo, rows: AssignmentGradeRow[] }
//   AssignmentInfo.items    = [{ protocol, points }]
//   AssignmentGradeRow      = { user_id, class_teacher, scores: {协议: 折算得分},
//                               total_earned, submission_count, last_submitted_at }
//   StudentAssignment       = { items: StudentItemGrade[], total_points, total_earned,
//                               submission_count, ... }
//   StudentItemGrade        = { protocol, points, best_score, earned, submission_count }
//                             （S4-b W4 起 earned 可为 null：null=未提交，
//                             0.0=提交了但 0 分，二者语义不同）
//
// 全部宽松降级、不抛错：缺字段时回退到 "—" / 0 / []，与原渲染风格一致。

export function formatScore(value) {
  return value === null || value === undefined || Number.isNaN(Number(value))
    ? "—" : Number(value).toFixed(1);
}

// 教师成绩总览：协议列定义在 assignment.items，不在响应顶层。
export function gradeItemsOf(grades) {
  return grades?.assignment?.items || [];
}

// 班级归属字段名是 class_teacher（users.created_by，即老师学号）。
export function classOf(row) {
  return row?.class_teacher || "—";
}

// 行内协议折算得分在 row.scores 字典里，不是 earned_by_protocol。
export function protocolScoreOf(row, protocol) {
  return row?.scores?.[protocol];
}

// 行模型没有 total_points：满分由协议项分值求和得到；非数值按 0 计。
export function totalPointsOf(items) {
  return (items || []).reduce((total, item) => {
    const points = Number(item?.points);
    return total + (Number.isNaN(points) ? 0 : points);
  }, 0);
}

// 学生侧：协议项（含个人成绩）在 assignment.items 顶层，不存在 my_grades。
export function studentItemsOf(assignment) {
  return assignment?.items || [];
}

// S4-b W4（decisions §9 冻结口径）：未提交协议的判定。
// 后端新契约 earned=null 表示无提交记录；submission_count===0 是同一语义的
// 旧形状/冗余信号，两者任一命中即视为未提交。注意 earned===0.0 不算未提交
// （提交了但最高 0 分），必须靠 null/计数区分。
export function isUnsubmitted(item) {
  if (!item) return true;
  if (item.earned === null || item.earned === undefined) return true;
  return Number(item.submission_count ?? 0) === 0;
}

// 学生详情表「我的最高分」「折算得分」两列的统一口径：未提交协议两列都显示
// 「未提交」（取代原先「— / 0」的不齐两列）；已提交保持原样取值。
export function itemBestLabel(item) {
  return isUnsubmitted(item) ? "未提交" : (item?.best_score ?? "—");
}

export function itemEarnedLabel(item) {
  return isUnsubmitted(item) ? "未提交" : (item?.earned ?? "—");
}

export function studentTotalEarned(assignment) {
  return assignment?.total_earned ?? 0;
}

export function studentTotalPoints(assignment) {
  return assignment?.total_points;
}
