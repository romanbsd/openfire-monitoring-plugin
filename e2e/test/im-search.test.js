import assert from 'node:assert/strict';
import { describe, it, before, after } from 'node:test';
import { config, jid, users } from '../lib/config.js';
import { getSharedContext } from '../lib/setup.js';
import {
  executeSearch,
  getSearchCapabilities,
  itemsWithSnippetToken,
} from '../lib/search.js';
import { connectXmpp, disconnectXmpp, discoverServerFeatures, joinMuc } from '../lib/xmpp.js';

/** @type {Awaited<ReturnType<typeof getSharedContext>>} */
let ctx;
/** @type {import('@xmpp/client').default} */
let aliceXmpp;
/** @type {import('@xmpp/client').default} */
let bobXmpp;
/** @type {import('@xmpp/client').default} */
let charlieXmpp;

before(async () => {
  try {
    ctx = await getSharedContext();
    aliceXmpp = await connectXmpp(users.alice.username, users.alice.password);
    bobXmpp = await connectXmpp(users.bob.username, users.bob.password);
    charlieXmpp = await connectXmpp(users.charlie.username, users.charlie.password);
    // Keep the seeded active MUC room online so generic room search can resolve it.
    await joinMuc(aliceXmpp, ctx.roomBare, 'alice');
    await joinMuc(bobXmpp, ctx.roomBare, 'bob');
  } catch (err) {
    console.error('E2E setup failed:', err);
    throw err;
  }
});

after(async () => {
  await disconnectXmpp(aliceXmpp);
  await disconnectXmpp(bobXmpp);
  await disconnectXmpp(charlieXmpp);
});

describe('IM search e2e', { concurrency: 1 }, () => {
  it('advertises urn:xmpp:im:search:0 in server disco', async () => {
    const features = await discoverServerFeatures(aliceXmpp);
    assert.ok(features.includes(config.searchNamespace), `features: ${features.join(', ')}`);
  });

  it('returns capabilities via IQ get', async () => {
    const cap = await getSearchCapabilities(aliceXmpp);
    assert.equal(cap.ok, true);
    assert.equal(cap.enabled, true);
    assert.equal(cap.types, 'messages');
    assert.equal(cap.sort, 'time');
    assert.deepEqual(cap.modifiers.sort(), ['after', 'before', 'from', 'in']);
  });

  it('finds a DM message by free-text token', async () => {
    const result = await executeSearch(aliceXmpp, { q: ctx.tokens.dmPrimary });
    assert.equal(result.ok, true);
    const hits = itemsWithSnippetToken(result.items, ctx.tokens.dmPrimary);
    assert.ok(hits.length >= 1, 'expected at least one DM hit');
    assert.equal(hits[0].archive, jid(users.alice.username));
  });

  it('finds a MUC message by free-text token', async () => {
    const result = await executeSearch(aliceXmpp, { q: ctx.tokens.mucPrimary });
    assert.equal(result.ok, true);
    const hits = itemsWithSnippetToken(result.items, ctx.tokens.mucPrimary);
    assert.ok(hits.length >= 1, 'expected at least one MUC hit');
    assert.equal(hits[0].archive, ctx.roomBare);
  });

  it('does not surface groupchat hits under a personal archive on free-text MUC search', async () => {
    const result = await executeSearch(aliceXmpp, { q: ctx.tokens.mucPrimary });
    assert.equal(result.ok, true);
    const hits = itemsWithSnippetToken(result.items, ctx.tokens.mucPrimary);
    assert.ok(hits.length >= 1);
    const aliceArchive = jid(users.alice.username);
    for (const hit of hits) {
      assert.notEqual(
        hit.archive,
        aliceArchive,
        `groupchat token must not appear under personal archive ${aliceArchive}`,
      );
      assert.equal(hit.archive, ctx.roomBare);
    }
  });

  it('finds messages in an inactive room when scoped with in: room JID', async () => {
    const q = `in:${ctx.inactiveRoomBare} ${ctx.tokens.mucInactive}`;
    const result = await executeSearch(aliceXmpp, { q });
    assert.equal(result.ok, true);
    const hits = itemsWithSnippetToken(result.items, ctx.tokens.mucInactive);
    assert.ok(hits.length >= 1, 'expected inactive-room hit');
    for (const hit of hits) {
      assert.equal(hit.archive, ctx.inactiveRoomBare);
    }
  });

  it('filters DM hits with in: peer modifier', async () => {
    const q = `in:${users.bob.username} ${ctx.tokens.dmPrimary}`;
    const result = await executeSearch(aliceXmpp, { q });
    assert.equal(result.ok, true);
    const hits = itemsWithSnippetToken(result.items, ctx.tokens.dmPrimary);
    assert.ok(hits.length >= 1);
    for (const hit of hits) {
      assert.equal(hit.archive, jid(users.alice.username));
    }
  });

  it('scopes MUC search with in: room modifier', async () => {
    const q = `in:${ctx.roomBare} ${ctx.tokens.mucPrimary}`;
    const result = await executeSearch(aliceXmpp, { q });
    assert.equal(result.ok, true);
    const hits = itemsWithSnippetToken(result.items, ctx.tokens.mucPrimary);
    assert.ok(hits.length >= 1);
    for (const hit of hits) {
      assert.equal(hit.archive, ctx.roomBare);
    }
  });

  it('filters by from: modifier for MUC author', async () => {
    const q = `from:${users.alice.username} in:${ctx.roomBare} ${ctx.tokens.mucPrimary}`;
    const result = await executeSearch(aliceXmpp, { q });
    assert.equal(result.ok, true);
    const hits = itemsWithSnippetToken(result.items, ctx.tokens.mucPrimary);
    assert.ok(hits.length >= 1);
    for (const hit of hits) {
      assert.equal(hit.from, jid(users.alice.username));
    }
  });

  it('supports quoted phrase search', async () => {
    const result = await executeSearch(aliceXmpp, { q: `"release blocker" ${ctx.tokens.dmPhrase}` });
    assert.equal(result.ok, true);
    const hits = itemsWithSnippetToken(result.items, ctx.tokens.dmPhrase);
    assert.ok(hits.length >= 1);
  });

  it('supports modifier-only query (from + free token)', async () => {
    const result = await executeSearch(aliceXmpp, {
      q: `from:${users.bob.username} ${ctx.tokens.dmSecondary}`,
    });
    assert.equal(result.ok, true);
    const hits = itemsWithSnippetToken(result.items, ctx.tokens.dmSecondary);
    assert.ok(hits.length >= 1);
    assert.equal(hits[0].from, jid(users.bob.username));
  });

  it('returns RSM metadata and paginates with max + after', async () => {
    const q = `in:${users.bob.username} ${ctx.tokens.paginationPrefix}`;
    const page1 = await executeSearch(aliceXmpp, {
      q,
      max: 2,
    });
    assert.equal(page1.ok, true);
    assert.ok(page1.items.length >= 2, `expected at least 2 pagination hits, got ${page1.items.length}`);
    assert.ok(page1.rsm?.last, 'expected last cursor on page 1');

    const page2 = await executeSearch(aliceXmpp, {
      q,
      max: 2,
      after: page1.rsm.last,
    });
    assert.equal(page2.ok, true);
    const page1Ids = new Set(page1.items.map((item) => item.id));
    for (const item of page2.items) {
      assert.ok(!page1Ids.has(item.id), `duplicate id on page 2: ${item.id}`);
    }
    assert.ok(page2.rsm?.last, 'expected last cursor on page 2');

    const allIds = new Set([...page1.items, ...page2.items].map((item) => item.id));
    const page3 = await executeSearch(aliceXmpp, {
      q,
      max: 2,
      after: page2.rsm.last,
    });
    assert.equal(page3.ok, true);
    for (const item of page3.items) {
      assert.ok(!allIds.has(item.id), `duplicate id on page 3: ${item.id}`);
    }
    assert.ok(allIds.size + page3.items.length >= 5, 'expected full pagination set across pages');
  });

  it('rejects empty query with bad-request', async () => {
    const result = await executeSearch(aliceXmpp, { q: '   ' });
    assert.equal(result.ok, false);
    assert.equal(result.condition, 'bad-request');
  });

  it('rejects unknown modifier with bad-request', async () => {
    const result = await executeSearch(aliceXmpp, { q: 'has:link budget' });
    assert.equal(result.ok, false);
    assert.equal(result.condition, 'bad-request');
  });

  it('rejects unsupported sort with feature-not-implemented', async () => {
    const result = await executeSearch(aliceXmpp, { q: ctx.tokens.dmPrimary, sort: 'relevance' });
    assert.equal(result.ok, false);
    assert.equal(result.condition, 'feature-not-implemented');
  });

  it('rejects unsupported result type with feature-not-implemented', async () => {
    const result = await executeSearch(aliceXmpp, { q: ctx.tokens.dmPrimary, types: 'channels' });
    assert.equal(result.ok, false);
    assert.equal(result.condition, 'feature-not-implemented');
  });

  it('does not leak another user DM archive to unauthorized requestor', async () => {
    const result = await executeSearch(charlieXmpp, { q: ctx.tokens.dmPrimary });
    assert.equal(result.ok, true);
    const leaks = itemsWithSnippetToken(result.items, ctx.tokens.dmPrimary);
    assert.equal(leaks.length, 0, 'charlie must not see alice-bob DM hits');
  });

  it('returns charlie-only DM to charlie but not to alice', async () => {
    const charlieResult = await executeSearch(charlieXmpp, { q: ctx.tokens.charlieNoise });
    assert.equal(charlieResult.ok, true);
    assert.ok(itemsWithSnippetToken(charlieResult.items, ctx.tokens.charlieNoise).length >= 1);

    const aliceResult = await executeSearch(aliceXmpp, { q: ctx.tokens.charlieNoise });
    assert.equal(aliceResult.ok, true);
    assert.equal(itemsWithSnippetToken(aliceResult.items, ctx.tokens.charlieNoise).length, 0);
  });

  it('bob can search shared DM thread from his archive perspective', async () => {
    const result = await executeSearch(bobXmpp, { q: `in:${users.alice.username} ${ctx.tokens.dmPrimary}` });
    assert.equal(result.ok, true);
    const hits = itemsWithSnippetToken(result.items, ctx.tokens.dmPrimary);
    assert.ok(hits.length >= 1);
    assert.equal(hits[0].archive, jid(users.bob.username));
  });
});
