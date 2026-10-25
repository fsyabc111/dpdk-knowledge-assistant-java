package io.dpdk.assistant.assignment.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Python 计分语义（assignment_service.calc_earned / round）。
 *
 * round(x, 1) 按二进制 double 的精确十进制展开做 HALF_EVEN 再回到最近 double；
 * 与 chat.poc.OrderedCitationBinder.pythonRound4 同一实现方式（BigDecimal(double)，
 * 参见 interface-changes.md J1-02 之外的既有先例）。分值域：score∈[0,100]、
 * points∈[1,1000]，乘积与一位小数折算在 double 精确域内，逐字节与 Python 一致。
 */
public final class PythonScore {

    private PythonScore() {
    }

    /** earned = round(best * points / 100, 1)；bestScore=null（未提交）按 Python 返回 0.0。 */
    public static double calcEarned(Integer bestScore, int points) {
        if (bestScore == null) {
            return 0.0;
        }
        return round1((double) bestScore * points / 100.0);
    }

    /** Python round(value, 1)（HALF_EVEN、二进制浮点现状）。 */
    public static double round1(double value) {
        return new BigDecimal(value).setScale(1, RoundingMode.HALF_EVEN).doubleValue();
    }

    /** Python round(float) → int（HALF_EVEN）；J2-04 提交响应用，J2-03 仓储写入侧共用。 */
    public static int roundToInt(double value) {
        return new BigDecimal(value).setScale(0, RoundingMode.HALF_EVEN).intValueExact();
    }
}
