# 原前端真实浏览器验收

只运行显式专属PG和固定外部上游；身份、权限、业务、事务、检索、任务、引用和历史都走Java。这里不替换产品路由，不加载旧.env，不操作原部署。

## 准备与运行

先在独立Python环境安装 `acceptance/contract/requirements.txt`，前端执行 `npm ci`；JDK21和四项 `DPDK_TEST_PG_URL/USER/PASSWORD/DATA_DIR` 显式设置。URL指向专属实例的postgres维护库，不能指向旧业务库；密码只在环境中提供。

```sh
acceptance/contract/build_server.sh
python -B -m acceptance.ui.session --out "$PWD/.migration-artifacts/ui-new-round"
```

输出目录必须全新。session.py先核验PG身份，再创建本轮 `p5_ui_` 库、启动真实Java、loopback固定模型/向量/MCP和原Vite前端15174。ready信息和合成账号位于本轮600权限session.json；用学号本身作合成初始密码。Ctrl-C/SIGTERM只停本轮进程、核验owner后DROP本轮库；不删除证据、镜像、归档或其他库。测试中需要保留业务状态时，不要提前停止此父进程。

浏览器使用browser-harness和独立Chrome profile/CDP端口，禁止复用个人浏览器。macOS示例（先确认19222空闲）：

```sh
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
  --remote-debugging-address=127.0.0.1 --remote-debugging-port=19222 \
  --user-data-dir="$PWD/.migration-artifacts/ui-chrome-profile" \
  --headless=new --no-first-run --no-default-browser-check about:blank
```

```sh
BU_NAME=java-ui BU_CDP_URL=http://127.0.0.1:19222 BH_RECORD=0 browser-harness <<'PY'
new_tab('http://127.0.0.1:15174')
wait_for_load()
print(page_info())
PY
```

`browser_helpers.py` 通过当前AX树定位后坐标点击、CDP键入和截图。每次重新连接先选择15174标签并设置1440×1000。macOS headless原生select的option没有CDP box，坐标/键盘不可用时验证可见option后用标准value/change自动化；这是表单控制器自动化，不替换服务端结果。文件选择使用真实input[type=file]的CDP上传。

## 必须观察的场景

1. leader登录、两教师及学生树、创建确认；负责人直属学生的冻结源UI缺陷另列，不冒充可见性通过。
2. teacher_a只见本班学生/作业；teacher_b和student_b看不到A班作业；operator可看成绩/CSV，不能发布或管理用户/知识。
3. A班教师发布带截止时间的dns作业；student_a上传本轮合成.c输入，真实提交→固定外部MCP结果→80分/折算16分；不执行源码。
4. 讲解该成绩，前后固定上游grade_plugin调用数不变；引用真实回读，不以HTTP200代替讲解。
5. `fixtures.append_six_release`仅在owned库追加6节点新release；新聊天显示6卡，第6片段可打开，同文件2片段不去重；旧会话仍回读旧来源。
6. 固定上游延迟时刷新断开，任务继续，历史入口找回完成答案和全部来源；完成重放不重扣/不重调上游另由HTTP扩展证明。
7. 首帧必须在延迟模型返回前到达；停止生成后显示已取消并恢复发送。检查网络响应头时序及最终持久状态，不能只看按钮退出loading。
8. 学生反馈→教师工作台查看6条证据/认领备注→重开读回事件；共享发布明确显示“索引更新后生效”，不把发布行当已摄取。
9. operator实际点击下载CSV，验证UTF8 BOM、账号/分数/列；原始CSV仅在ignore目录。
10. `fixtures.append_untrusted_markdown`经真实历史API提供合成恶意Markdown；粗体/安全链接保留，onerror/javascript URL/script不能执行。此项只证明客户端历史渲染，不代表模型答案校验。

本轮去敏断言/源码/截图hash见 `docs/java-migration/evidence/J5-01/browser-progress.json`。原PNG、网络诊断、CSV和上游原始账本均gitignored。自动/浏览器工程验收与用户/live验收分开。正式容量期间必须停止所有本轮浏览器和UI服务。
