// 任务仍为 pending 时重订阅同一 task_id；maxRetries 是首次订阅之后的重试次数。
export async function retryPendingTask({ streamOnce, onRetry, wait = abortableDelay, signal, retryMs, maxRetries }) {
  let pending = false;
  for (let attempt = 0; attempt <= maxRetries; attempt += 1) {
    if (signal?.aborted) return pending;
    if (attempt > 0) onRetry?.();
    pending = await streamOnce();
    if (!pending || attempt === maxRetries || signal?.aborted) return pending;
    await wait(retryMs, signal);
  }
  return pending;
}

export function abortableDelay(ms, signal) {
  if (signal?.aborted) return Promise.reject(new DOMException("已中止", "AbortError"));
  return new Promise((resolve, reject) => {
    let timer;
    const onAbort = () => {
      window.clearTimeout(timer);
      reject(new DOMException("已中止", "AbortError"));
    };
    timer = window.setTimeout(() => {
      signal?.removeEventListener("abort", onAbort);
      resolve();
    }, ms);
    signal?.addEventListener("abort", onAbort, { once: true });
  });
}
