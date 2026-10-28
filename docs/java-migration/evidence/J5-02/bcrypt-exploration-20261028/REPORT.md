# bcrypt实现与JIT探索（不作为正式门禁）

真实HTTP诊断已经确认Java并发登录红；本片仅在本地ignore scratch探索安全实现/部署参数，不接PG/真实上游、不改pom/生产/阈值。三个纯Java实现Spring7.0.8、Password4j1.8.4、Favre0.10.2，cost均12；实际artifact SHA见manifest。初次Password4j下载TLS失败、重试成功，未改网络配置。

三provider r2串行p95分别214.899/220.457/222.838ms，60×25ms理想到达时含调度等待p95为533.528/570.797/523.943ms。独立JVM每次随机盐，不能称为跨JVM完全相同hash或严格同物理正式容量。首次探索没计VT排队且与Maven下载重叠，明确排除性能门禁，仅保留原始探索结果，不取其较绿值。

之后同Spring/30次warm、原5JVM参数，对default、只强制inline encipher、强制inline整个BCrypt类分别独立JVM顺序比较：串行p95约249/229/234ms；含VT等待p95约706/698/706ms，无足够收益支持新部署参数。实际参数和原始结果逐文件hash保留。

裁定：不替换密码库、不添加JIT编译指令，避免无证据的兼容/维护成本。未降低cost12、未缓存成功口令、未变更dummy等价工作；也不宣称候选库完整历史hash/长口令兼容已验。后续仍用真实HTTP诊断验证部署调度变量，然后完整正式双端门禁独立决定。

最后补官方当前成熟实现Bouncy Castle1.86同参数微基准：串行p95223.281ms，含调度等待597.103ms，无足够收益支持替换；仅用实际artifact API，未采用或完成全部历史hash兼容验证。官方版本出处https://www.bouncycastle.org/download/bouncy-castle-java/，artifact SHA见summary。
