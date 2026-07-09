# Message correction and retraction (search index)

The Monitoring plugin applies **[XEP-0308](https://xmpp.org/extensions/xep-0308.html)** (Last Message Correction) and **[XEP-0424](https://xmpp.org/extensions/xep-0424.html)** (Message Retraction) to the **OpenSearch message index** used by IM search (`urn:xmpp:im:search:0`) and XEP-0431 full-text MAM queries.

This is **search-index support only**. The SQL archive (`ofMessageArchive` / MAM) remains append-only: corrections and retractions are stored as additional rows when they have a body, and original rows are not rewritten or tombstoned.

## Behavior

| Event | SQL / MAM | OpenSearch |
|-------|-----------|------------|
| Correction (`<replace xmlns='urn:xmpp:message-correct:0'/>`) | New row archived (unchanged) | Documents for the **original** `messageID` get `body` updated; the correction stanza is **not** indexed as its own hit |
| Retraction (`<retract xmlns='urn:xmpp:message-retract:0\|1'/>`) | Archived only if a body is present | Documents for the original `messageID` are **deleted** |
| Target not found | No change | No change (debug log) |

Search hit `id` values still refer to the original archive `messageID`. Clients can MAM-fetch that id; the MAM body may still show the pre-edit text until SQL/MAM mutation is implemented.

## Target ID resolution

`<replace id='…'/>` and `<retract id='…'/>` are resolved against stored stanza XML by matching either:

1. The message `@id` attribute, or
2. An archive-owner [XEP-0359](https://xmpp.org/extensions/xep-0359.html) `<stanza-id/>`

**Prerequisite:** the original message row must have a non-empty `stanza` column. One-to-one chats store stanzas by default. MUC room archives only store stanzas when room stanza archiving is enabled (`conversation.roomArchivingStanzas`). Without stored stanzas, index updates cannot find the target.

## Rebuilds

Incremental and full message index rebuilds load `stanza` and apply the same replace/retract rules in `sentDate` order, so historical edits stay consistent with live traffic.

## Non-goals (current)

- No disco advertisement of full server support for `urn:xmpp:message-correct:0` / `urn:xmpp:message-retract:*`
- No SQL tombstones or body rewrites for MAM
- No server-side policy enforcement (e.g. “last own message only”)
