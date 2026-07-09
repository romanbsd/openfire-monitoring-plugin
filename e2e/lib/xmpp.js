import { client, xml } from '@xmpp/client';
import { config } from './config.js';
import { sleep } from './wait.js';

let iqCounter = 0;

export function createXmpp(username, password) {
  const domain = config.xmppDomain;
  const xmpp = client({
    service: `xmpp://${config.xmppHost}:${config.xmppPort}`,
    domain,
    username,
    password,
    resource: `e2e-${Date.now()}`,
  });

  xmpp.on('error', (err) => {
    if (err?.condition !== 'not-authorized') {
      console.warn(`XMPP error (${username}):`, err?.message ?? err);
    }
  });

  return xmpp;
}

export async function connectXmpp(username, password) {
  const xmpp = createXmpp(username, password);
  await xmpp.start();
  await xmpp.send(xml('presence'));
  await sleep(500);
  return xmpp;
}

export async function disconnectXmpp(xmpp) {
  if (!xmpp) return;
  try {
    await xmpp.stop();
  } catch {
    // ignore teardown errors
  }
}

export async function sendChatMessage(xmpp, toBareJid, body, type = 'chat', { id } = {}) {
  const attrs = { to: toBareJid, type };
  if (id) {
    attrs.id = id;
  }
  await xmpp.send(
    xml('message', attrs, xml('body', {}, body)),
  );
  await sleep(300);
}

/**
 * XEP-0308 last message correction.
 * @returns {string} the correction stanza id
 */
export async function sendChatCorrection(xmpp, toBareJid, replaceId, body, type = 'chat') {
  const id = `correct-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  await xmpp.send(
    xml(
      'message',
      { to: toBareJid, type, id },
      xml('body', {}, body),
      xml('replace', { xmlns: 'urn:xmpp:message-correct:0', id: replaceId }),
    ),
  );
  await sleep(300);
  return id;
}

/**
 * XEP-0424 message retraction.
 * @param {object} [opts]
 * @param {string} [opts.body] optional fallback body (archived by SQL if present)
 * @param {boolean} [opts.withFallbackHint] include XEP-0428 fallback marker
 */
export async function sendChatRetraction(xmpp, toBareJid, retractId, type = 'chat', opts = {}) {
  const id = `retract-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  const children = [
    xml('retract', { xmlns: 'urn:xmpp:message-retract:1', id: retractId }),
  ];
  if (opts.body) {
    if (opts.withFallbackHint !== false) {
      children.push(xml('fallback', { xmlns: 'urn:xmpp:fallback:0', for: 'urn:xmpp:message-retract:1' }));
    }
    children.push(xml('body', {}, opts.body));
  }
  children.push(xml('store', { xmlns: 'urn:xmpp:hints' }));
  await xmpp.send(
    xml('message', { to: toBareJid, type, id }, ...children),
  );
  await sleep(300);
  return id;
}

export async function joinMuc(xmpp, roomBareJid, nick) {
  await xmpp.send(xml('presence', { to: `${roomBareJid}/${nick}` }));
  await sleep(1_000);
}

export async function leaveMuc(xmpp, roomBareJid, nick) {
  await xmpp.send(xml('presence', { to: `${roomBareJid}/${nick}`, type: 'unavailable' }));
  await sleep(500);
}

export async function sendMucMessage(xmpp, roomBareJid, body) {
  await xmpp.send(
    xml('message', { to: roomBareJid, type: 'groupchat' }, xml('body', {}, body)),
  );
  await sleep(300);
}

/**
 * Mark a newly created MUC room persistent so it survives after the last occupant leaves.
 * Required for inactive-room search tests (OpenFire destroys ephemeral rooms on empty).
 */
export async function configurePersistentMucRoom(xmpp, roomBareJid) {
  const id = nextIqId('muc-persist');
  const xData = xml(
    'x',
    { xmlns: 'jabber:x:data', type: 'submit' },
    xml('field', { var: 'FORM_TYPE' }, xml('value', {}, 'http://jabber.org/protocol/muc#roomconfig')),
    xml('field', { var: 'muc#roomconfig_persistentroom' }, xml('value', {}, '1')),
  );
  const response = await sendIq(
    xmpp,
    xml(
      'iq',
      { type: 'set', to: roomBareJid, id },
      xml('query', { xmlns: 'http://jabber.org/protocol/muc#owner' }, xData),
    ),
  );
  if (response.attrs.type === 'error') {
    throw new Error(`MUC persistent room config failed for ${roomBareJid}`);
  }
}

export async function discoverServerFeatures(xmpp) {
  const id = `disco-${++iqCounter}`;
  const response = await sendIq(
    xmpp,
    xml('iq', { type: 'get', to: config.xmppDomain, id }, xml('query', { xmlns: 'http://jabber.org/protocol/disco#info' })),
  );
  const query = response.getChild('query', 'http://jabber.org/protocol/disco#info');
  if (!query) return [];
  return query
    .getChildren('feature')
    .map((feature) => feature.attrs.var)
    .filter(Boolean);
}

const iqLocks = new WeakMap();
const IQ_TIMEOUT_MS = 30_000;

export async function sendIq(xmpp, element) {
  const id = element.attrs.id;
  if (!id) {
    throw new Error('IQ stanza must have an id attribute');
  }
  const prior = iqLocks.get(xmpp) ?? Promise.resolve();
  let releaseLock;
  const gate = new Promise((resolve) => {
    releaseLock = resolve;
  });
  iqLocks.set(xmpp, prior.then(() => gate));
  await prior;
  try {
    return await new Promise((resolve, reject) => {
      let settled = false;

      const finish = (fn, value) => {
        if (settled) return;
        settled = true;
        clearTimeout(timeout);
        xmpp.removeListener('stanza', onStanza);
        xmpp.removeListener('error', onError);
        fn(value);
      };

      const onStanza = (stanza) => {
        if (stanza.name !== 'iq' || stanza.attrs.id !== id) {
          return;
        }
        finish(resolve, stanza);
      };

      const onError = (err) => {
        finish(reject, err);
      };

      const timeout = setTimeout(() => {
        finish(reject, new Error(`Timed out waiting for IQ response id=${id}`));
      }, IQ_TIMEOUT_MS);

      xmpp.on('stanza', onStanza);
      xmpp.on('error', onError);
      xmpp.send(element).catch((err) => finish(reject, err));
    });
  } finally {
    releaseLock();
  }
}

export function nextIqId(prefix = 'iq') {
  return `${prefix}-${++iqCounter}`;
}
