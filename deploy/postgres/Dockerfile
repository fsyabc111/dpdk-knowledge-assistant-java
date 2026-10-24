# syntax=docker/dockerfile:1
# pgvector/pgvector:pg17 + zhparser（scws 驱动）中文分词扩展（roadmap B2）。
# 构建链：scws-1.2.3（xunsearch 源码包，sha256 校验）→ zhparser v2.3（pin tag）。
# 多阶段：运行镜像只带 scws 共享库与 zhparser 扩展文件，不带编译链。
FROM pgvector/pgvector:pg17 AS build

ARG SCWS_VERSION=1.2.3
ARG SCWS_SHA256=60d50ac3dc42cff3c0b16cb1cfee47d8cb8c8baa142a58bc62854477b81f1af5
# github.com 在本部署网络不可达，但 codeload.github.com 可达；用 tarball + sha256
# 校验代替 git clone（v2.3 = commit dd292fd）。
ARG ZHPARSER_REF=v2.3
ARG ZHPARSER_SHA256=96fe19f1b72c922eab19ecfc305384381562ff840da4d3b77452e21fefe8f0d2
# scws 简体中文字典（xunsearch 官方包）：无词典时 zhparser 退化为单字切分；
# 放默认路径 tsearch_data/dict.utf8.xdb 即自动加载，无需额外配置。
ARG SCWS_DICT_SHA256=0f43956721561b1f5f0102b529dbe24a1aa15b9fb6c05446367db2717f462af3

RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential ca-certificates wget bzip2 \
        postgresql-server-dev-17 \
    && rm -rf /var/lib/apt/lists/*

# scws：zhparser 的分词驱动库（Simple Chinese Word Segmentation）
RUN wget -q -O /tmp/scws.tar.bz2 "http://www.xunsearch.com/scws/down/scws-${SCWS_VERSION}.tar.bz2" \
    && echo "${SCWS_SHA256}  /tmp/scws.tar.bz2" | sha256sum -c - \
    && tar -xjf /tmp/scws.tar.bz2 -C /tmp \
    && cd /tmp/scws-${SCWS_VERSION} \
    && ./configure --prefix=/usr/local \
    && make -j"$(nproc)" && make install

# zhparser 本体（PG 扩展，PG_CONFIG 取自基础镜像的 PG17）
RUN wget -q -O /tmp/zhparser.tar.gz "https://codeload.github.com/amutu/zhparser/tar.gz/refs/tags/${ZHPARSER_REF}" \
    && echo "${ZHPARSER_SHA256}  /tmp/zhparser.tar.gz" | sha256sum -c - \
    && mkdir -p /tmp/zhparser \
    && tar -xzf /tmp/zhparser.tar.gz -C /tmp/zhparser --strip-components=1 \
    && cd /tmp/zhparser \
    && make -j"$(nproc)" && make install

# scws 简体中文字典（仅需解包，运行阶段 COPY 到 tsearch_data）
RUN wget -q -O /tmp/scws-dict.tar.bz2 "http://www.xunsearch.com/scws/down/scws-dict-chs-utf8.tar.bz2" \
    && echo "${SCWS_DICT_SHA256}  /tmp/scws-dict.tar.bz2" | sha256sum -c - \
    && tar -xjf /tmp/scws-dict.tar.bz2 -C /tmp

FROM pgvector/pgvector:pg17

# 运行期只需要 scws 共享库、词典与 zhparser 扩展文件
COPY --from=build /usr/local/lib/libscws* /usr/local/lib/
COPY --from=build /usr/lib/postgresql/17/lib/zhparser.so /usr/lib/postgresql/17/lib/
COPY --from=build /usr/share/postgresql/17/extension/zhparser* /usr/share/postgresql/17/extension/
COPY --from=build /tmp/dict.utf8.xdb /usr/share/postgresql/17/tsearch_data/dict.utf8.xdb
RUN ldconfig

# 仅对新建空库生效（官方 entrypoint 语义）；存量库的 zhparser 迁移由
# alembic 0010（backend/alembic/versions/0010_li_fts_zhparser.py）负责。
COPY initdb/00-zhparser.sql /docker-entrypoint-initdb.d/00-zhparser.sql
