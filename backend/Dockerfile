# syntax=docker/dockerfile:1
# Build context: repository root (set in docker-compose.yml).

FROM python:3.11-slim AS builder
WORKDIR /app
COPY backend/requirements.lock backend/requirements.lock
# 部署网络官方 PyPI 极慢，默认走阿里云镜像；可用 --build-arg PIP_INDEX_URL= 覆盖回官方源。
ARG PIP_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/
RUN python -m venv /opt/venv \
    && /opt/venv/bin/pip install --no-cache-dir --index-url "$PIP_INDEX_URL" -r backend/requirements.lock

FROM python:3.11-slim
RUN groupadd -r app && useradd -r -g app -d /app app
COPY --from=builder /opt/venv /opt/venv
COPY backend/ /app/backend/
COPY knowledge/ /app/knowledge/
RUN chown -R app:app /app
WORKDIR /app/backend
ENV PATH="/opt/venv/bin:$PATH" \
    PYTHONUNBUFFERED=1 \
    DPDK_REPO_PATH=/data/dpdk
USER app
EXPOSE 8090
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8090/api/health', timeout=5)"
CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090"]
