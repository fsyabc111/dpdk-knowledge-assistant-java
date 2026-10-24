package io.dpdk.assistant.retrieval;

/**
 * 向量工具（对齐 services/vector_math.py 与 li_retrieval_store 的向量字面量）。
 */
public final class VectorMath {

    private VectorMath() {
    }

    /** cosine_similarity：与 Python 逐算子一致（长度不等/空 → 0）。 */
    public static double cosineSimilarity(double[] left, double[] right) {
        if (left == null || right == null || left.length == 0 || right.length == 0
                || left.length != right.length) {
            return 0.0;
        }
        double dot = 0.0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
        }
        double leftNorm = 0.0;
        for (double v : left) {
            leftNorm += v * v;
        }
        leftNorm = Math.sqrt(leftNorm);
        double rightNorm = 0.0;
        for (double v : right) {
            rightNorm += v * v;
        }
        rightNorm = Math.sqrt(rightNorm);
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }
        return dot / (leftNorm * rightNorm);
    }

    /**
     * PG vector 字面量："[" + str(float(x)) + ...（fetch_semantic_candidate_ids）。
     * 分量取 float4 精确值时与 Python str(float) 逐字符一致（1.0/0.5 等）；
     * 非二进制精确值存在指数格式差异（Java 1.0E-5 vs Python 1e-05），不影响
     * PG 解析后的排序（见证据差异表）。
     */
    public static String pgVectorLiteral(double[] values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(PythonJson.pythonFloat(values[i]));
        }
        return sb.append(']').toString();
    }

    /** float[] → double[]（Vector1024 存 float4）。 */
    public static double[] toDoubles(float[] values) {
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i];
        }
        return out;
    }
}
