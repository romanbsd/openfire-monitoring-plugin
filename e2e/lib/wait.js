export async function waitFor(predicate, { timeoutMs = 60_000, intervalMs = 2_000, label = 'condition' } = {}) {
  const deadline = Date.now() + timeoutMs;
  let lastLog = 0;
  while (Date.now() < deadline) {
    if (await predicate()) {
      return;
    }
    const now = Date.now();
    if (lastLog === 0 || now - lastLog >= 15_000) {
      const remainingSec = Math.ceil((deadline - now) / 1000);
      console.log(`  waiting for ${label} (${remainingSec}s left)`);
      lastLog = now;
    }
    await sleep(intervalMs);
  }
  throw new Error(`Timed out waiting for ${label} after ${timeoutMs}ms`);
}

export async function waitForHttp(url, { timeoutMs = 300_000, label = url } = {}) {
  await waitFor(
    async () => {
      try {
        const res = await fetch(url);
        return res.ok;
      } catch {
        return false;
      }
    },
    { timeoutMs, intervalMs: 3_000, label },
  );
}

export function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
