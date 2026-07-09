import { config } from './config.js';
import { sleep, waitFor } from './wait.js';

async function osSearch(index, body) {
  const res = await fetch(`${config.opensearchUrl}/${index}/_search`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  return { res, data: res.ok ? await res.json() : null };
}

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
  const { data } = await osSearch(index, { query: { match_phrase: { [field]: token } } });
  return data?.hits?.total?.value ?? 0;
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

export async function searchBodies(index, token, { size = 20 } = {}) {
  const { res, data } = await osSearch(index, {
    size,
    query: { match_phrase: { body: token } },
    _source: ['messageID', 'body', 'owner', 'room', 'sentDate'],
  });
  if (!res.ok) {
    throw new Error(`OpenSearch search failed (${res.status}): ${await res.text()}`);
  }
  return (data.hits?.hits ?? []).map((hit) => ({
    id: hit._id,
    messageID: hit._source?.messageID,
    body: hit._source?.body ?? '',
    owner: hit._source?.owner,
    room: hit._source?.room,
  }));
}

export async function waitForBodyTokenGone(index, token) {
  await waitFor(
    async () => (await countHits(index, 'body', token)) === 0,
    {
      timeoutMs: config.indexTimeoutMs,
      intervalMs: 2_000,
      label: `OpenSearch ${index} token ${token} gone`,
    },
  );
  // Guard against a concurrent indexer briefly recreating the old body.
  await sleep(1_500);
  const rebound = await countHits(index, 'body', token);
  if (rebound !== 0) {
    throw new Error(`OpenSearch ${index} token ${token} reappeared after delete (${rebound} hits)`);
  }
}

export async function waitForBodyContains(index, token, substring) {
  await waitFor(
    async () => (await searchBodies(index, token)).some((hit) => hit.body.includes(substring)),
    {
      timeoutMs: config.indexTimeoutMs,
      intervalMs: 2_000,
      label: `OpenSearch ${index} body containing ${substring}`,
    },
  );
}

export function messagesIndexName() {
  return `${config.indexPrefix}-messages`;
}
