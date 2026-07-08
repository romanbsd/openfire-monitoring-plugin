import { config } from './config.js';
import { waitFor } from './wait.js';

export async function waitForClusterHealth() {
  const url = `${config.opensearchUrl}/_cluster/health?wait_for_status=yellow&timeout=60s`;
  const res = await fetch(url);
  if (!res.ok) {
    throw new Error(`OpenSearch health check failed: ${res.status}`);
  }
  const health = await res.json();
  if (!['yellow', 'green'].includes(health.status)) {
    throw new Error(`OpenSearch cluster status: ${health.status}`);
  }
}

export async function countHits(index, field, token) {
  const res = await fetch(`${config.opensearchUrl}/${index}/_search`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      query: { match: { [field]: token } },
    }),
  });
  if (!res.ok) {
    return 0;
  }
  const data = await res.json();
  return data.hits?.total?.value ?? 0;
}

export async function waitForIndexedToken(index, field, token, { minCount = 1 } = {}) {
  await waitFor(
    async () => (await countHits(index, field, token)) >= minCount,
    {
      timeoutMs: config.indexTimeoutMs,
      intervalMs: 3_000,
      label: `OpenSearch ${index} token ${token} (>= ${minCount})`,
    },
  );
}
