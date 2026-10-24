# Windows + WSL2 部署文档（本机实操版）

文档入口见[导航](README.md)，当前试用与验收安排见[统一路线](roadmap.md)。部署说明不代表已完成上线验收；本次文档整理未执行部署。

> 本文档记录本项目在**这台电脑**（Windows 11 + WSL2 Ubuntu-24.04）上的完整部署流程与更新路线，
> 全部为 2026-08-24/25 实际验证过的步骤。通用 Linux 部署见 README「Production Deployment」一节。

## 1. 架构总览

```
内网/浏览器
   │  https://localhost/（默认仅本机；内网开放见 §7）
   ▼
Windows 主机（WSL2 的 localhostForwarding 转发到 WSL）
   ▼
WSL2 Ubuntu-24.04 ── Docker Engine 29.7.2 + Compose v5.5.0
   ├─ frontend 容器：nginx，静态产物 + 反代 /api，对外 443/80（自签名证书）
   ├─ backend  容器：FastAPI/uvicorn :8090（compose 内网；另有 override 发布到 127.0.0.1:8090 供 dev 预览）
   └─ postgres 容器：pgvector/pgvector:pg17，数据在命名卷 pgdata，仅 compose 内网
   （harness 服务本机无 teaching_harness 仓库，始终跳过）
```

**两个代码副本，职责分明**：

| 副本 | 位置 | 用途 |
| --- | --- | --- |
| 开发副本 | `D:\dpdk\ai_chat_assistant`（Windows） | 日常开发、git 工作区、对接 gitee 远程 |
| 部署副本 | WSL 内 `~/ai_chat_assistant`（root） | 从开发副本 clone，部署只认这里 |

DPDK 知识源不复制，直接以只读方式挂载 Windows 侧目录：`/mnt/d/dpdk` → 容器内 `/data/dpdk`。

## 2. 前置条件（本机已就绪，重装系统时参考）

- WSL2 + Ubuntu-24.04，默认用户 root、sudo 免密、systemd 已启用
- Windows 侧有 Python 3.13 / Node 24（仅 dev 预览用）
- DPDK 主项目仓库在 `D:\dpdk`
- DashScope API key（LLM + embedding 共用一个 `sk-...`）

### 2.1 本机 WSL 环境修复记录（一次性，勿重复执行）

- **apt arm64 源 404**：该 WSL 装了 arm64 外来架构，清华主镜像无 arm64 包。
  已在 `/etc/apt/sources.list.d/ubuntu.sources` 两个 tuna 源段加 `Architectures: amd64`，
  并新增 `ubuntu-ports.sources`（`https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports`，`Architectures: arm64`）。
- **download.docker.com 被墙**：Docker 官方 apt 源换成阿里云
  （`DOCKER_APT_MIRROR=https://mirrors.aliyun.com/docker-ce/linux/ubuntu`）。
- **Docker Hub 拉取超时**：`/etc/docker/daemon.json` 已配置 registry mirrors
  （`docker.m.daocloud.io` / `docker.1ms.run` / `docker.xuanyuan.me`）。

## 3. 首次部署流程（已全部执行过，重建时按此复现）

以下命令如无说明均在 Windows Git Bash 中执行；`MSYS_NO_PATHCONV=1` 用于防止路径被 MSYS2 转换。
多行脚本先写入 `\\wsl$\Ubuntu-24.04\root\*.sh` 再用 `wsl.exe -d Ubuntu-24.04 -- bash /root/xxx.sh` 执行
（直接传多行内联脚本会被 wsl.exe 截断）。

```bash
# 1) 项目克隆进 WSL 文件系统（/mnt/d 上跑容器 IO 太慢）
MSYS_NO_PATHCONV=1 wsl.exe -d Ubuntu-24.04 -- bash -c \
  'git clone /mnt/d/dpdk/ai_chat_assistant ~/ai_chat_assistant'

# 2) 安装 Docker（项目自带脚本，需指定阿里云 apt 镜像）
MSYS_NO_PATHCONV=1 wsl.exe -d Ubuntu-24.04 -- bash -c \
  'DOCKER_APT_MIRROR=https://mirrors.aliyun.com/docker-ce/linux/ubuntu bash ~/ai_chat_assistant/deploy/wsl2/install_docker.sh'

# 3) 生成自签名证书
MSYS_NO_PATHCONV=1 wsl.exe -d Ubuntu-24.04 -- bash -c \
  'cd ~/ai_chat_assistant && bash deploy/certs/gen_self_signed_cert.sh'

# 4) 创建 deploy/.env（模板复制后填值，见 §4）
#    POSTGRES_PASSWORD / API_TOKENS_JSON 用 openssl rand 生成；
#    LLM_API_KEY / EMBEDDING_API_KEY 与 backend/.env 同源（DashScope）

# 5) 构建并启动（跳过 harness）
MSYS_NO_PATHCONV=1 wsl.exe -d Ubuntu-24.04 -- bash -c \
  'cd ~/ai_chat_assistant && docker compose --env-file deploy/.env build backend frontend \
   && docker compose --env-file deploy/.env up -d postgres backend frontend'

# 6) schema 迁移（users 等账号表在 alembic 里，init_db 的基线 DDL 不含）
MSYS_NO_PATHCONV=1 wsl.exe -d Ubuntu-24.04 -- bash -c \
  'cd ~/ai_chat_assistant && docker compose --env-file deploy/.env exec -T backend alembic upgrade head'

# 7) 重建知识索引（3583 节点；读 /mnt/d 较慢属正常）
MSYS_NO_PATHCONV=1 wsl.exe -d Ubuntu-24.04 -- bash -c \
  'cd ~/ai_chat_assistant && docker compose --env-file deploy/.env exec -T backend python scripts/ingest_llama_index.py'

# 8) 引导管理员账号（初始密码 = 学号）
MSYS_NO_PATHCONV=1 wsl.exe -d Ubuntu-24.04 -- bash -c \
  'cd ~/ai_chat_assistant && docker compose --env-file deploy/.env exec -T backend python scripts/create_user.py --id U20260000001 --roles admin'

# 9) 验证
curl -sk https://localhost/api/health   # {"ok":true,"app":"knowledge-assistant"}
```

访问 `https://localhost/`，自签名证书浏览器警告属预期。管理员登录后到"用户管理"面板批量导入学生账号。

## 4. 配置文件 `deploy/.env`（WSL 部署副本内，gitignored）

| 项 | 当前值/说明 |
| --- | --- |
| `POSTGRES_PASSWORD` | 随机生成，与 `DATABASE_URL` 中一致 |
| `DATABASE_BACKEND` / `DATABASE_URL` | `postgres` / `postgresql://postgres:<密码>@postgres:5432/assistant` |
| `DPDK_REPO_PATH` | `/data/dpdk`（容器内挂载点） |
| `DPDK_REPO_HOST_PATH` | `/mnt/d/dpdk`（compose 变量插值用，**启动命令必须带 `--env-file deploy/.env`**） |
| `LLM_API_KEY` / `EMBEDDING_API_KEY` | DashScope `sk-...` |
| `AUTH_MODE` | `account` |
| `API_TOKENS_JSON` | 应急 admin token（≥32 字符随机串） |
| `FRONTEND_ORIGINS` | `http://127.0.0.1:5174`（放行 dev 预览跨域） |

另有一个**不在仓库里**的 `docker-compose.override.yml`（WSL 部署副本根目录）：
把 backend 发布到 `127.0.0.1:8090`，供 Windows 侧 dev 预览直连。正式上线如想收紧可删除后 `up -d`。

## 5. 更新部署路线（标准升级流程）

```
开发副本（D:\）改代码 → git commit → git push origin main
   → WSL 部署副本 git pull
   → 重建变更的镜像 → 替换容器 → alembic upgrade head → 验证
```

WSL 里已有现成脚本 `/root/redeploy.sh`（内容如下，可复用）：

```bash
#!/usr/bin/env bash
set -e
cd ~/ai_chat_assistant
git pull --ff-only
docker compose --env-file deploy/.env build backend frontend
docker compose --env-file deploy/.env up -d backend frontend
docker compose --env-file deploy/.env exec -T backend alembic upgrade head
docker compose --env-file deploy/.env ps
```

执行（Windows Git Bash）：

```bash
MSYS_NO_PATHCONV=1 wsl.exe -d Ubuntu-24.04 -- bash /root/redeploy.sh
```

**注意事项**：

- `git pull --ff-only` 若失败说明部署副本被改乱，先 `git status` 检查（`deploy/.env`、
  `docker-compose.override.yml` 属正常未跟踪/忽略文件）
- 只改了前端可只重建 frontend：`build frontend && up -d frontend`
- 有新 alembic revision 时 `upgrade head` 会自动应用；schema 基线是幂等 DDL，回滚代码不需要回退数据库
- 知识源（DPDK 仓库或 `knowledge/`）变化后重建索引：
  `docker compose --env-file deploy/.env exec -T backend python scripts/ingest_llama_index.py`（幂等增量）
- 验证三连：`docker compose ps` 全健康 → `curl -sk https://localhost/api/health` → 登录问一个实测问题

## 6. 日常运维

```bash
# 状态 / 日志
docker compose --env-file deploy/.env ps
docker compose --env-file deploy/.env logs -f backend

# 停止 / 恢复（数据在 pgdata 卷，down 不丢）
docker compose --env-file deploy/.env down
docker compose --env-file deploy/.env up -d postgres backend frontend

# 释放资源：整个 WSL 关机（下次 wsl 命令自动拉起，docker 服务已 enable 随 systemd 自启）
wsl.exe --shutdown
```

备份、会话清理、健康检查告警等沿用 README「数据生命周期」「可观测性」一节（`deploy/backup.sh`、
`purge_sessions.py`、`healthcheck.sh`，可挂 WSL cron）。

## 7. 内网开放 / 关闭（默认关闭）

WSL2 是 NAT 网络，容器端口只转发到本机 localhost。开放内网需 Windows 端口转发 + 防火墙（均需管理员）：

```bat
:: WSL IP 会变，先查：wsl.exe -d Ubuntu-24.04 -- hostname -I
netsh interface portproxy add v4tov4 listenaddress=192.168.15.100 listenport=443 connectaddress=<WSL_IP> connectport=443
netsh interface portproxy add v4tov4 listenaddress=192.168.15.100 listenport=80  connectaddress=<WSL_IP> connectport=80
netsh advfirewall firewall add rule name="AI-Assistant-HTTPS" dir=in action=allow protocol=TCP localport=443
netsh advfirewall firewall add rule name="AI-Assistant-HTTP"  dir=in action=allow protocol=TCP localport=80
```

之后内网机器访问 `https://192.168.15.100/`（自签名证书警告属预期）。**WSL 重启后 IP 可能变化，需重建 portproxy**。

关闭（2026-08-25 已执行，当前处于关闭状态）：

```bat
netsh interface portproxy delete v4tov4 listenaddress=192.168.15.100 listenport=443
netsh interface portproxy delete v4tov4 listenaddress=192.168.15.100 listenport=80
netsh advfirewall firewall delete rule name="AI-Assistant-HTTPS"
netsh advfirewall firewall delete rule name="AI-Assistant-HTTP"
```

## 8. dev 预览（改前端时用）

```bash
cd D:\dpdk\ai_chat_assistant\frontend
npm install   # 首次
npm run dev   # http://127.0.0.1:5174
```

前端 `client.js` 默认打 `http://127.0.0.1:8090`（即 override 发布的 WSL backend），CORS 已放行，登录/问答全链路可用。

## 9. 常见问题

| 症状 | 原因与处理 |
| --- | --- |
| `apt` 404 binary-arm64 | 见 §2.1，已修复，勿删 ubuntu-ports 源 |
| docker 拉镜像 i/o timeout | 确认 `/etc/docker/daemon.json` registry mirrors 存在，`systemctl restart docker` |
| 容器内 pip 装测试包 Permission denied | 镜像以非 root 运行，`docker run -u root ...` |
| 新容器起不来报 users 表不存在 | 漏了 `alembic upgrade head`（§3 第 6 步） |
| 容器反复重启、每隔几十秒全体重建 | WSL ≥2.6.1 回归：最后一个 wsl 会话关闭约 20s 后整个 distro 被回收，systemd 服务（含 `wsl-keepalive.service` 这种 sleep 进程）无法阻止（microsoft/WSL#13416）。解法：Windows 侧常驻一个隐藏 `wsl.exe -d Ubuntu-24.04 --exec sleep infinity` 会话（已放启动文件夹 `wsl-keepalive.vbs`，2026-09-07 验证容器持续在线） |
| 改了 `.wslconfig` 的 `vmIdleTimeout` 不生效 | `.wslconfig` 里不能有非 ASCII 字符（中文注释会导致解析失败、整份配置被静默忽略），注释用英文 |
| 内网突然访问不了 | WSL IP 变了，按 §7 重建 portproxy |
| Git Bash 里 wsl 命令行为怪异 | 多行脚本写文件再执行；路径参数加 `MSYS_NO_PATHCONV=1` |
