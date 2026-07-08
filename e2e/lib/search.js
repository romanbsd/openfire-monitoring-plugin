import { xml } from '@xmpp/client';
import { config } from './config.js';
import { nextIqId, sendIq } from './xmpp.js';

function searchElement(children = []) {
  return xml('search', { xmlns: config.searchNamespace }, ...children);
}

function rsmElement(children = []) {
  return xml('set', { xmlns: config.rsmNamespace }, ...children);
}

export async function getSearchCapabilities(xmpp) {
  const id = nextIqId('search-cap');
  const response = await sendIq(
    xmpp,
    xml('iq', { type: 'get', to: config.xmppDomain, id }, searchElement()),
  );
  return parseSearchIq(response);
}

export async function executeSearch(xmpp, { q, types, sort, max, after, before, pagingBackwards = false } = {}) {
  const children = [xml('q', {}, q ?? '')];
  if (types) {
    children.push(xml('types', {}, types));
  }
  if (sort) {
    children.push(xml('sort', {}, sort));
  }
  const rsmChildren = [];
  if (max != null) {
    rsmChildren.push(xml('max', {}, String(max)));
  }
  if (after) {
    rsmChildren.push(xml('after', {}, after));
  }
  if (before !== undefined) {
    if (before === null || before === '') {
      rsmChildren.push(xml('before'));
    } else {
      rsmChildren.push(xml('before', {}, before));
    }
  }
  if (pagingBackwards) {
    if (!rsmChildren.some((el) => el.name === 'before')) {
      rsmChildren.push(xml('before'));
    }
  }
  if (rsmChildren.length > 0) {
    children.push(rsmElement(rsmChildren));
  }

  const id = nextIqId('search');
  const response = await sendIq(
    xmpp,
    xml('iq', { type: 'set', to: config.xmppDomain, id }, searchElement(children)),
  );
  return parseSearchIq(response);
}

const STANZAS_NS = 'urn:ietf:params:xml:ns:xmpp-stanzas';
const ERROR_CONDITIONS = [
  'bad-request',
  'feature-not-implemented',
  'service-unavailable',
  'internal-server-error',
  'forbidden',
  'item-not-found',
];

function errorCondition(errorEl) {
  if (!errorEl) return null;
  for (const name of ERROR_CONDITIONS) {
    if (errorEl.getChild(name, STANZAS_NS)) {
      return name;
    }
  }
  for (const child of errorEl.children ?? []) {
    const xmlns = child.attrs?.xmlns;
    if (xmlns === STANZAS_NS || xmlns === undefined) {
      return child.name;
    }
  }
  return errorEl.attrs?.type ?? null;
}

export function parseSearchIq(stanza) {
  if (stanza.attrs.type === 'error') {
    const error = stanza.getChild('error');
    return {
      ok: false,
      type: 'error',
      condition: errorCondition(error),
      text: error?.getChildText('text'),
      stanza,
    };
  }

  const search = stanza.getChild('search', config.searchNamespace);
  if (!search) {
    return { ok: false, type: 'missing-search', stanza };
  }

  const enabledEl = search.getChildText('enabled');
  const modifiers = search
    .getChild('modifiers')
    ?.getChildren('modifier')
    ?.map((m) => m.getText())
    ?.filter(Boolean) ?? [];

  const items = search.getChildren('item').map((item) => ({
    id: item.attrs.id,
    type: item.attrs.type,
    archive: item.attrs.archive,
    from: item.attrs.from,
    stamp: item.attrs.stamp,
    snippet: item.getChildText('snippet'),
  }));

  const set = search.getChild('set', config.rsmNamespace);
  const rsm = set
    ? {
        first: set.getChildText('first'),
        last: set.getChildText('last'),
        count: Number(set.getChildText('count') ?? '0'),
      }
    : null;

  return {
    ok: true,
    type: 'result',
    enabled: enabledEl === 'true',
    types: search.getChildText('types'),
    sort: search.getChildText('sort'),
    modifiers,
    complete: search.attrs.complete === 'true',
    items,
    rsm,
    stanza,
  };
}

export function itemsContaining(items, needle) {
  return items.filter(
    (item) =>
      (item.snippet && item.snippet.includes(needle)) ||
      (item.id && String(item.id).includes(needle)),
  );
}

export function itemsWithSnippetToken(items, token) {
  return items.filter((item) => item.snippet?.includes(token));
}
