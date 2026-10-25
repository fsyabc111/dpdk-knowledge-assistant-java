import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * P2 回归专用：为合成账号生成 bcrypt 哈希（cost 10，纯本地，无网络）。
 *
 * 交付位置：docs/java-migration/evidence/P2-regression/tools/BcryptGen.java（ST-03 修复：
 * 不再依赖未交付的 /tmp/p2reg/tools/）。
 *
 * 运行方式（不硬编码任何绝对路径）：
 *   java -cp "<spring-security-crypto-*.jar>:<commons-logging-*.jar>" \
 *        docs/java-migration/evidence/P2-regression/tools/BcryptGen.java <口令...>
 *
 * 依赖定位约定（由调用方 common.sh / run_08_golden.py 解析）：
 * - java 可执行文件：JAVA_HOME/bin/java，否则 PATH 上的 java；
 * - jar：M2_REPO 环境变量（默认 ~/.m2/repository）下通配查找
 *   org/springframework/security/spring-security-crypto/*/spring-security-crypto-*.jar
 *   与 commons-logging/commons-logging/*/commons-logging-*.jar（取版本最高者）。
 *
 * 输出：每行 "<口令>\t<哈希>\t<自校验 matches 结果>"；调用方负责对哈希脱敏。
 */
public class BcryptGen {
    public static void main(String[] args) {
        BCryptPasswordEncoder enc = new BCryptPasswordEncoder(10);
        for (String pw : args) {
            String h = enc.encode(pw);
            System.out.println(pw + "\t" + h + "\t" + enc.matches(pw, h));
        }
    }
}
