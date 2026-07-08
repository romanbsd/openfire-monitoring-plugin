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

export async function sendChatMessage(xmpp, toBareJid, body, type = 'chat') {
  await xmpp.send(
    xml('message', { to: toBareJid, type }, xml('body', {}, body)),
  );
  await sleep(300);
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
