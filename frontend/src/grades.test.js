// B1 回归防线：锁「前端读的字段」与后端契约（backend/app/schemas/assignments.py）。
// fixture 形状由后端真实 pydantic 响应模型序列化得到（见 docs/baseline_review_20260918.md B1）。
import { describe, expect, it } from "vitest";
import {
  classOf, formatScore, gradeItemsOf, isUnsubmitted, itemBestLabel, itemEarnedLabel,
  protocolScoreOf, studentItemsOf, studentTotalEarned, studentTotalPoints, totalPointsOf
} from "./grades";

const teacherGrades = {
  assignment: {
    id: "asg_1", title: "第一次插件作业", description: "", created_by: "T001",
    scope: "class", deadline: "2026-10-01T23:59:00+08:00", status: "active",
    created_at: "2026-09-20T10:00:00+08:00", updated_at: "2026-09-20T10:00:00+08:00",
    items: [{ protocol: "dns", points: 60 }, { protocol: "http", points: 40 }]
  },
  rows: [{
    user_id: "U20260000001", class_teacher: "T001", scores: { dns: 48.0, http: 30.0 },
    total_earned: 78.0, submission_count: 3, last_submitted_at: "2026-09-21T09:00:00+08:00"
  }]
};

const studentAssignment = {
  id: "asg_1", title: "第一次插件作业", description: "",
  created_by: "T001", deadline: "2026-10-01T23:59:00+08:00", status: "active",
  items: [
    { protocol: "dns", points: 60, best_score: 80, earned: 48.0, submission_count: 2 },
    { protocol: "http", points: 40, best_score: 75, earned: 30.0, submission_count: 1 }
  ],
  total_points: 100, total_earned: 78.0, submission_count: 3
};

describe("B1 契约用例：字段名与后端模型对齐", () => {
  it("教师表协议列来自 assignment.items", () => {
    expect(gradeItemsOf(teacherGrades).map((item) => item.protocol)).toEqual(["dns", "http"]);
  });

  it("班级列读 class_teacher", () => {
    expect(classOf(teacherGrades.rows[0])).toBe("T001");
  });

  it("协议得分读 row.scores[protocol]", () => {
    const row = teacherGrades.rows[0];
    expect(formatScore(protocolScoreOf(row, "dns"))).toBe("48.0");
    expect(formatScore(protocolScoreOf(row, "http"))).toBe("30.0");
  });

  it("总分 = total_earned / 协议项分值之和（行内无 total_points）", () => {
    const row = teacherGrades.rows[0];
    expect(`${formatScore(row.total_earned)} / ${formatScore(totalPointsOf(gradeItemsOf(teacherGrades)))}`)
      .toBe("78.0 / 100.0");
  });

  it("学生卡片总分读顶层 total_earned / total_points", () => {
    expect(`我的得分 ${studentTotalEarned(studentAssignment)} / ${studentTotalPoints(studentAssignment) ?? "—"}`)
      .toBe("我的得分 78 / 100");
  });

  it("学生详情行直接读 items[] 的 best_score / earned / submission_count", () => {
    const dns = studentItemsOf(studentAssignment)[0];
    expect([dns.best_score ?? "—", dns.earned ?? "—", dns.submission_count ?? 0]).toEqual([80, 48.0, 2]);
  });
});

describe("宽松降级：缺字段/空对象不抛错", () => {
  it("formatScore 对空值与非数值返回 —", () => {
    expect(formatScore(undefined)).toBe("—");
    expect(formatScore(null)).toBe("—");
    expect(formatScore("abc")).toBe("—");
    expect(formatScore(0)).toBe("0.0");
  });

  it("gradeItemsOf 对空对象/undefined 返回 []", () => {
    expect(gradeItemsOf({})).toEqual([]);
    expect(gradeItemsOf(undefined)).toEqual([]);
    expect(gradeItemsOf({ rows: [] })).toEqual([]);
  });

  it("classOf / protocolScoreOf 缺字段降级", () => {
    expect(classOf({})).toBe("—");
    expect(classOf(undefined)).toBe("—");
    expect(protocolScoreOf({}, "dns")).toBeUndefined();
    expect(protocolScoreOf(undefined, "dns")).toBeUndefined();
  });

  it("totalPointsOf 空列表为 0，非数值 points 计 0", () => {
    expect(totalPointsOf([])).toBe(0);
    expect(totalPointsOf(undefined)).toBe(0);
    expect(totalPointsOf([{ protocol: "dns" }, { protocol: "http", points: 40 }])).toBe(40);
  });

  it("学生侧函数缺字段降级", () => {
    expect(studentItemsOf({})).toEqual([]);
    expect(studentItemsOf(undefined)).toEqual([]);
    expect(studentTotalEarned({})).toBe(0);
    expect(studentTotalEarned(undefined)).toBe(0);
    expect(studentTotalPoints({})).toBeUndefined();
  });
});

describe("S4-b W4：未提交协议统一显示「未提交」", () => {
  // 新契约（StudentItemGrade.earned 可为 null）：无提交记录 earned=null。
  const partialAssignment = {
    items: [
      { protocol: "dns", points: 60, best_score: 80, earned: 48.0, submission_count: 2 },
      { protocol: "http", points: 40, best_score: null, earned: null, submission_count: 0 }
    ]
  };

  it("earned=null 的协议判未提交，最高分/折算两列都显示「未提交」", () => {
    const http = partialAssignment.items[1];
    expect(isUnsubmitted(http)).toBe(true);
    expect(itemBestLabel(http)).toBe("未提交");
    expect(itemEarnedLabel(http)).toBe("未提交");
  });

  it("submission_count===0（旧形状：earned=0.0）同样判未提交", () => {
    const legacyShape = { protocol: "ssh", points: 20, best_score: null, earned: 0.0, submission_count: 0 };
    expect(isUnsubmitted(legacyShape)).toBe(true);
    expect(itemEarnedLabel(legacyShape)).toBe("未提交");
  });

  it("提交了但 0 分不算未提交（earned=0.0 与 null 语义不同）", () => {
    const zeroScore = { protocol: "dns", points: 60, best_score: 0, earned: 0.0, submission_count: 1 };
    expect(isUnsubmitted(zeroScore)).toBe(false);
    expect(itemBestLabel(zeroScore)).toBe(0);
    expect(itemEarnedLabel(zeroScore)).toBe(0.0);
  });

  it("已提交协议保持原取值不变", () => {
    const dns = partialAssignment.items[0];
    expect(isUnsubmitted(dns)).toBe(false);
    expect(itemBestLabel(dns)).toBe(80);
    expect(itemEarnedLabel(dns)).toBe(48.0);
  });

  it("空对象/缺字段按未提交降级，不抛错", () => {
    expect(itemBestLabel(undefined)).toBe("未提交");
    expect(itemEarnedLabel({})).toBe("未提交");
  });
});

describe("B1 回归绊线：fixture 中不存在旧的错误键", () => {
  it("这些键若重新出现在契约里，本测试必须同步重写", () => {
    expect(teacherGrades.items).toBeUndefined();
    expect(teacherGrades.rows[0].earned_by_protocol).toBeUndefined();
    expect(teacherGrades.rows[0].class_owner).toBeUndefined();
    expect(teacherGrades.rows[0].total_points).toBeUndefined();
    expect(studentAssignment.my_grades).toBeUndefined();
  });
});
