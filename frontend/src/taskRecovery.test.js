import { describe, expect, it, vi } from "vitest";

import { retryPendingTask } from "./taskRecovery.js";

describe("retryPendingTask", () => {
  it("reconnects after pending and stops on a terminal frame", async () => {
    const streamOnce = vi.fn()
      .mockResolvedValueOnce(true)
      .mockResolvedValueOnce(false);
    const onRetry = vi.fn();
    const wait = vi.fn().mockResolvedValue(undefined);

    const pending = await retryPendingTask({ streamOnce, onRetry, wait, retryMs: 3000, maxRetries: 10 });
    expect(pending).toBe(false);
    expect(streamOnce).toHaveBeenCalledTimes(2);
    expect(onRetry).toHaveBeenCalledTimes(1);
    expect(wait).toHaveBeenCalledExactlyOnceWith(3000, undefined);
  });

  it("stops after the configured retries and reports pending", async () => {
    const streamOnce = vi.fn().mockResolvedValue(true);
    const wait = vi.fn().mockResolvedValue(undefined);

    const pending = await retryPendingTask({ streamOnce, wait, retryMs: 3, maxRetries: 2 });
    expect(pending).toBe(true);
    expect(streamOnce).toHaveBeenCalledTimes(3);
    expect(wait).toHaveBeenCalledTimes(2);
  });

  it("does not open another stream after local cancellation", async () => {
    const controller = new AbortController();
    const streamOnce = vi.fn().mockResolvedValue(true);
    const wait = vi.fn().mockImplementation(async () => controller.abort());

    const pending = await retryPendingTask({
      streamOnce, wait, signal: controller.signal, retryMs: 3, maxRetries: 10
    });
    expect(pending).toBe(true);
    expect(streamOnce).toHaveBeenCalledTimes(1);
  });
});
