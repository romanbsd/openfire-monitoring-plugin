import assert from 'node:assert/strict';
import { describe, it, before, after } from 'node:test';
import { config, jid, users } from '../lib/config.js';
import { ensureStackReady } from '../lib/setup.js';
import { executeSearch, itemsWithSnippetToken } from '../lib/search.js';
import {
  countHits,
  messagesIndexName,
  searchBodies,
  waitForBodyContains,
  waitForBodyTokenGone,
  waitForIndexedToken,
} from '../lib/opensearch.js';
import {
  connectXmpp,
  disconnectXmpp,
  sendChatCorrection,
  sendChatMessage,
  sendChatRetraction,
} from '../lib/xmpp.js';
import { sleep } from '../lib/wait.js';

/** @type {Awaited<ReturnType<typeof ensureStackReady>>} */
let ctx;
/** @type {import('@xmpp/client').default} */
let aliceXmpp;
/** @type {import('@xmpp/client').default} */
let bobXmpp;

before(async () => {
  ctx = await ensureStackReady();
  aliceXmpp = await connectXmpp(users.alice.username, users.alice.password);
  bobXmpp = await connectXmpp(users.bob.username, users.bob.password);
});

after(async () => {
  await disconnectXmpp(aliceXmpp);
  await disconnectXmpp(bobXmpp);
});

async function seedIndexedMessage(body, originalId) {
  const index = messagesIndexName();
  const bobBare = jid(users.bob.username);
  await sendChatMessage(aliceXmpp, bobBare, body, 'chat', { id: originalId });
  await sleep(config.archiveSettleMs);
  await ctx.admin.rebuildIndexes();
  await ctx.admin.waitForIndexIdle(60_000);
  const tokenMatch = /needle=(\S+)/.exec(body);
  if (tokenMatch) {
    await waitForIndexedToken(index, 'body', tokenMatch[1]);
  }
  return { index, bobBare };
}

describe('XEP-0308 / XEP-0424 OpenSearch index e2e', { concurrency: 1 }, () => {
  it('updates the OpenSearch body when a message is corrected (XEP-0308)', async () => {
    const runId = Date.now().toString(36);
    const originalToken = `e2e-correct-orig-${runId}`;
    const correctedToken = `e2e-correct-new-${runId}`;
    const originalId = `orig-correct-${runId}`;
    const { index, bobBare } = await seedIndexedMessage(
      `correction fixture needle=${originalToken} before-edit`,
      originalId,
    );

    const beforeHits = await searchBodies(index, originalToken);
    assert.ok(beforeHits.length >= 1, 'expected original message indexed');
    assert.ok(beforeHits.every((hit) => hit.body.includes(originalToken)));
    assert.ok(beforeHits.every((hit) => !hit.body.includes(correctedToken)));

    await sendChatCorrection(
      aliceXmpp,
      bobBare,
      originalId,
      `correction fixture needle=${correctedToken} after-edit`,
    );
    await sleep(2_000);
    await waitForBodyContains(index, correctedToken, correctedToken);
    await waitForBodyTokenGone(index, originalToken);

    const afterHits = await searchBodies(index, correctedToken);
    assert.ok(afterHits.length >= 1, 'expected corrected body in OpenSearch');
    for (const hit of afterHits) {
      assert.ok(hit.body.includes(correctedToken));
      assert.ok(!hit.body.includes(originalToken));
    }
    assert.equal(new Set(afterHits.map((hit) => hit.messageID)).size, 1);

    assert.equal(await countHits(index, 'body', originalToken), 0);
    assert.ok((await countHits(index, 'body', correctedToken)) >= 1);

    const searchOld = await executeSearch(aliceXmpp, { q: `"${originalToken}"` });
    assert.equal(searchOld.ok, true);
    assert.equal(itemsWithSnippetToken(searchOld.items, originalToken).length, 0);

    const searchNew = await executeSearch(aliceXmpp, { q: `"${correctedToken}"` });
    assert.equal(searchNew.ok, true);
    assert.ok(itemsWithSnippetToken(searchNew.items, correctedToken).length >= 1);
  });

  it('deletes the OpenSearch document when a message is retracted (XEP-0424)', async () => {
    const runId = Date.now().toString(36);
    const retractToken = `e2e-retract-${runId}`;
    const originalId = `orig-retract-${runId}`;
    const { index, bobBare } = await seedIndexedMessage(
      `retraction fixture needle=${retractToken} secret-payload`,
      originalId,
    );
    assert.ok((await countHits(index, 'body', retractToken)) >= 1);

    await sendChatRetraction(aliceXmpp, bobBare, originalId, 'chat', {
      body: '/me retracted a previous message, but it is unsupported by your client.',
    });
    await waitForBodyTokenGone(index, retractToken);
    assert.equal(await countHits(index, 'body', retractToken), 0);

    const search = await executeSearch(aliceXmpp, { q: `"${retractToken}"` });
    assert.equal(search.ok, true);
    assert.equal(itemsWithSnippetToken(search.items, retractToken).length, 0);
  });

  it('deletes OpenSearch docs for a body-less retraction', async () => {
    const runId = Date.now().toString(36);
    const retractToken = `e2e-retract-nobody-${runId}`;
    const originalId = `orig-retract-nobody-${runId}`;
    const { index, bobBare } = await seedIndexedMessage(
      `bodyless retract fixture needle=${retractToken}`,
      originalId,
    );

    await sendChatRetraction(aliceXmpp, bobBare, originalId, 'chat');
    await waitForBodyTokenGone(index, retractToken);
    assert.equal(await countHits(index, 'body', retractToken), 0);
  });
});
