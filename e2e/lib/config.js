import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Compose reads repo-root `.env` for host ports; bare `npm test` must too or it hangs on 5222/9090.
const envPath = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../.env');
try {
  for (const line of fs.readFileSync(envPath, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/);
    if (!m || process.env[m[1]] !== undefined) continue;
    let v = m[2];
    if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) {
      v = v.slice(1, -1);
    }
    process.env[m[1]] = v;
  }
} catch {
  // no .env
}

export const config = {
  adminUrl: process.env.ADMIN_URL ?? `http://127.0.0.1:${process.env.OPENFIRE_ADMIN_PORT ?? '9090'}`,
  opensearchUrl: process.env.OPENSEARCH_URL ?? `http://127.0.0.1:${process.env.OPENSEARCH_PORT ?? '9200'}`,
  xmppHost: process.env.XMPP_HOST ?? '127.0.0.1',
  xmppPort: Number(process.env.XMPP_PORT ?? process.env.OPENFIRE_XMPP_PORT ?? '5222'),
  xmppDomain: process.env.XMPP_DOMAIN ?? 'localhost',
  adminUser: process.env.ADMIN_USER ?? 'admin',
  adminPass: process.env.ADMIN_PASS ?? 'admin',
  indexPrefix: process.env.INDEX_PREFIX ?? 'monitoring',
  archiveSettleMs: Number(process.env.ARCHIVE_SETTLE_MS ?? '10000'),
  startupTimeoutMs: Number(process.env.STARTUP_TIMEOUT_MS ?? '300000'),
  indexTimeoutMs: Number(process.env.INDEX_TIMEOUT_MS ?? '180000'),
  skipDockerUp: process.env.SKIP_DOCKER_UP === '1',
  searchNamespace: 'urn:xmpp:im:search:0',
  rsmNamespace: 'http://jabber.org/protocol/rsm',
  mucService: (domain) => `conference.${domain}`,
};

export const users = {
  alice: { username: 'e2e-alice', password: 'e2e-alice' },
  bob: { username: 'e2e-bob', password: 'e2e-bob' },
  charlie: { username: 'e2e-charlie', password: 'e2e-charlie' },
};

export function jid(username, domain = config.xmppDomain) {
  return `${username}@${domain}`;
}

export function bareJid(username, domain = config.xmppDomain) {
  return jid(username, domain);
}
