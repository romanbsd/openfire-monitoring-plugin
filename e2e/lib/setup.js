import { execFile } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { promisify } from 'node:util';
import { AdminClient } from './admin.js';
import { config, jid, users } from './config.js';
import { waitForClusterHealth, waitForIndexedToken } from './opensearch.js';
import {
  connectXmpp,
  disconnectXmpp,
  configurePersistentMucRoom,
  joinMuc,
  leaveMuc,
  sendChatMessage,
  sendMucMessage,
} from './xmpp.js';
import { sleep, waitFor, waitForHttp } from './wait.js';

const execFileAsync = promisify(execFile);
const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

export async function maybeStartDockerStack() {
  if (config.skipDockerUp) {
    return;
  }
  const env = { ...process.env };
  if (process.env.OPENFIRE_XMPP_PORT) {
    env.OPENFIRE_XMPP_PORT = process.env.OPENFIRE_XMPP_PORT;
  }
  if (process.env.OPENFIRE_ADMIN_PORT) {
    env.OPENFIRE_ADMIN_PORT = process.env.OPENFIRE_ADMIN_PORT;
  }
  if (process.env.OPENSEARCH_PORT) {
    env.OPENSEARCH_PORT = process.env.OPENSEARCH_PORT;
  }
  await execFileAsync('docker', ['compose', 'up', '-d', '--build'], { cwd: repoRoot, env });
}

export async function waitForStack() {
  await waitForHttp(config.opensearchUrl);
  await waitForClusterHealth();
  await waitForHttp(config.adminUrl);

  const admin = new AdminClient();
  await waitFor(
    async () => {
      try {
        await admin.login();
        return true;
      } catch {
        return false;
      }
    },
    { timeoutMs: config.startupTimeoutMs, intervalMs: 5_000, label: 'OpenFire admin login' },
  );

  await admin.ensureLoggedIn();
  return admin;
}

export async function ensureUsers(admin) {
  for (const user of Object.values(users)) {
    await admin.createUser(user.username, user.password);
  }
}

export async function seedArchiveFixture(admin) {
  const runId = Date.now().toString(36);
  const tokens = {
    dmPrimary: `e2e-dm-primary-${runId}`,
    dmSecondary: `e2e-dm-secondary-${runId}`,
    dmPhrase: `e2e-phrase-needle-${runId}`,
    mucPrimary: `e2e-muc-primary-${runId}`,
    mucInactive: `e2e-muc-inactive-${runId}`,
    mucNoise: `e2e-muc-noise-${runId}`,
    paginationPrefix: `e2e-page-${runId}`,
    charlieNoise: `e2e-charlie-only-${runId}`,
  };

  const roomLocal = `e2e-room-${runId}`;
  const roomBare = `${roomLocal}@${config.mucService(config.xmppDomain)}`;
  const inactiveRoomLocal = `e2e-inactive-${runId}`;
  const inactiveRoomBare = `${inactiveRoomLocal}@${config.mucService(config.xmppDomain)}`;

  const alice = await connectXmpp(users.alice.username, users.alice.password);
  const bob = await connectXmpp(users.bob.username, users.bob.password);
  const charlie = await connectXmpp(users.charlie.username, users.charlie.password);

  try {
    // DM: alice -> bob
    await sendChatMessage(
      alice,
      jid(users.bob.username),
      `e2e direct hello needle=${tokens.dmPrimary} budget discussion`,
    );
    await sendChatMessage(
      bob,
      jid(users.alice.username),
      `e2e reply needle=${tokens.dmSecondary} follow-up`,
    );
    await sendChatMessage(
      alice,
      jid(users.bob.username),
      `e2e exact "release blocker" phrase needle=${tokens.dmPhrase}`,
    );

    // DM: charlie isolated traffic (authorization negative control)
    await sendChatMessage(
      charlie,
      jid(users.bob.username),
      `charlie private needle=${tokens.charlieNoise}`,
    );

    // MUC: alice joins and posts; bob joins for visibility
    await joinMuc(alice, roomBare, 'alice');
    await joinMuc(bob, roomBare, 'bob');
    await sendMucMessage(
      alice,
      roomBare,
      `e2e muc hello needle=${tokens.mucPrimary} deployment status`,
    );
    await sendMucMessage(
      bob,
      roomBare,
      `e2e muc noise needle=${tokens.mucNoise} unrelated`,
    );

    // Inactive room: persist, post, then leave so the room is not in getActiveChatRooms().
    await joinMuc(alice, inactiveRoomBare, 'alice');
    await configurePersistentMucRoom(alice, inactiveRoomBare);
    await sendMucMessage(
      alice,
      inactiveRoomBare,
      `e2e inactive muc needle=${tokens.mucInactive} archived`,
    );
    await leaveMuc(alice, inactiveRoomBare, 'alice');

    // Pagination seed: several messages from alice in the same DM thread
    for (let i = 1; i <= 5; i += 1) {
      await sendChatMessage(
        alice,
        jid(users.bob.username),
        `${tokens.paginationPrefix}-msg-${i} paging test body`,
      );
    }
  } finally {
    await disconnectXmpp(alice);
    await disconnectXmpp(bob);
    await disconnectXmpp(charlie);
  }

  await sleep(config.archiveSettleMs);
  await admin.rebuildIndexes();
  await admin.waitForIndexIdle(60_000);

  const messagesIndex = `${config.indexPrefix}-messages`;
  const mucMessagesIndex = `${config.indexPrefix}-muc-messages`;
  await waitForIndexedToken(messagesIndex, 'body', tokens.dmPrimary);
  await waitForIndexedToken(messagesIndex, 'body', tokens.mucPrimary);
  await waitForIndexedToken(messagesIndex, 'body', tokens.paginationPrefix, { minCount: 5 });
  await waitForIndexedToken(mucMessagesIndex, 'body', tokens.mucPrimary);
  await waitForIndexedToken(mucMessagesIndex, 'body', tokens.mucInactive);

  return {
    tokens,
    roomBare,
    roomLocal,
    inactiveRoomBare,
    inactiveRoomLocal,
    users: {
      alice: users.alice.username,
      bob: users.bob.username,
      charlie: users.charlie.username,
    },
  };
}

let sharedContext;
let stackReady;

/**
 * Bring up docker (unless skipped), wait for admin/OpenSearch/XMPP, ensure users.
 * Does not seed the IM-search archive fixture.
 */
export async function ensureStackReady() {
  if (stackReady) {
    return stackReady;
  }
  console.log(
    `E2E setup: admin=${config.adminUrl} xmpp=${config.xmppHost}:${config.xmppPort} opensearch=${config.opensearchUrl}`,
  );
  await maybeStartDockerStack();
  const admin = await waitForStack();
  await ensureUsers(admin);
  await waitFor(
    async () => {
      try {
        const probe = await connectXmpp(users.alice.username, users.alice.password);
        await disconnectXmpp(probe);
        return true;
      } catch (err) {
        console.warn('XMPP probe failed:', err?.message ?? err);
        return false;
      }
    },
    { timeoutMs: config.startupTimeoutMs, intervalMs: 5_000, label: `OpenFire XMPP c2s on ${config.xmppHost}:${config.xmppPort}` },
  );
  stackReady = { admin };
  return stackReady;
}

export async function getSharedContext() {
  if (sharedContext) {
    return sharedContext;
  }
  const { admin } = await ensureStackReady();
  const fixture = await seedArchiveFixture(admin);
  sharedContext = { admin, ...fixture };
  return sharedContext;
}
