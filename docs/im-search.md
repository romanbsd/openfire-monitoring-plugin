# ProtoXEP: Cross-Archive Instant Messaging Search

| | |
|---|---|
| **Abstract** | This document defines an XMPP extension for cross-archive message search across personal and multi-user chat archives, using a modifier-based query string and returning compact result items suitable for application clients. |
| **Namespace** | `urn:xmpp:im:search:0` |
| **Type** | Standards Track (provisional; not submitted to the XSF) |
| **Version** | 0.1.0 |
| **Status** | Experimental |
| **Requires** | XEP-0030 (Service Discovery), XEP-0059 (Result Set Management), XEP-0082 (XMPP Date and Time Profiles), XEP-0313 (Message Archive Management) |

---

## Table of Contents

1. [Introduction](#1-introduction)
2. [Terminology](#2-terminology)
3. [Requirements](#3-requirements)
4. [Overview](#4-overview)
5. [Discovering Support](#5-discovering-support)
6. [Capabilities Query](#6-capabilities-query)
7. [Search Request](#7-search-request)
8. [Search Results](#8-search-results)
9. [Query Language](#9-query-language)
10. [Access Control](#10-access-control)
11. [Result Set Management](#11-result-set-management)
12. [Error Handling](#12-error-handling)
13. [Relationship to MAM and Full-Text Search](#13-relationship-to-mam-and-full-text-search)
14. [Security Considerations](#14-security-considerations)
15. [IANA Considerations](#15-iana-considerations)
16. [XMPP Registrar Considerations](#16-xmpp-registrar-considerations)
17. [XML Schema](#17-xml-schema)
18. [Implementation Notes](#18-implementation-notes)
19. [Examples](#19-examples)
20. [Future Work](#20-future-work)

Appendices

- [Appendix A: Document Information](#appendix-a-document-information)
- [Appendix B: RFC 2119 Keywords](#appendix-b-rfc-2119-keywords)
- [Appendix C: Revision History](#appendix-c-revision-history)

---

## 1. Introduction

### 1.1 Motivation

[Message Archive Management (XEP-0313)](https://xmpp.org/extensions/xep-0313.html) defines retrieval of archived messages from a **single** archive (a user archive or a MUC archive). [Full Text Search in MAM (XEP-0431)](https://xmpp.org/extensions/xep-0431.html) adds a free-text field to a MAM query form, but still within one archive at a time.

Application clients (team chat interfaces, unified inboxes, etc.) often need to search **across all archives visible to the user** in one operation, with filters such as sender, conversation, and date range. This specification defines a dedicated IQ-based protocol for that use case.

### 1.2 Scope

This protocol:

- Searches personal and MUC message archives visible to the requestor.
- Accepts a single modifier-based query string in `<q/>`.
- Returns compact `<item/>` elements with optional body snippets.
- Uses [XEP-0059](https://xmpp.org/extensions/xep-0059.html) for paging.

This protocol does **not**:

- Replace MAM for per-archive history or stanza retrieval.
- Expose the underlying search engine query language to clients.
- Define channel or user directory search in version 0 (reserved for future types).

### 1.3 Conformance

The key words "MUST", "MUST NOT", "REQUIRED", "SHALL", "SHALL NOT", "SHOULD", "SHOULD NOT", "RECOMMENDED", "NOT RECOMMENDED", "MAY", and "OPTIONAL" in this document are to be interpreted as described in [BCP 14](https://www.rfc-editor.org/info/bcp14).

---

## 2. Terminology

| Term | Definition |
|------|------------|
| **Archive** | A message archive as defined by XEP-0313: a user bare JID (personal archive) or MUC room bare JID (room archive). |
| **Requestor** | The authenticated entity named in the IQ `from` attribute who initiates the search. |
| **Hit** | One matching archived message returned as an `<item/>`. |
| **Modifier** | A `key:value` token in the query string (e.g. `from:alice`). |
| **Cursor** | An opaque token used with RSM `after` / `before` for paging. |
| **Snippet** | A short plain-text excerpt of a message body returned for UI preview. |

---

## 3. Requirements

An implementation of this specification:

1. **MUST** advertise `urn:xmpp:im:search:0` via service discovery when search is operational.
2. **MUST NOT** advertise the feature when the backing full-text index is unavailable.
3. **MUST** scope all results to archives the requestor is authorized to access.
4. **MUST** accept IQ `get` for capabilities and IQ `set` for search execution.
5. **MUST** support the query modifiers `from`, `in`, `before`, and `after` as defined in [Section 9](#9-query-language).
6. **MUST** support RSM paging without `<index>` (out-of-order pages).
7. **SHOULD** return message snippets when a body is available.
8. **SHOULD** recommend that clients fetch full message stanzas via MAM using the `archive` and `id` attributes from each hit.

---

## 4. Overview

```text
 Client                          Server
   |                               |
   |-- IQ get <search/> ---------->|  Capabilities
   |<-- enabled, modifiers --------|
   |                               |
   |-- IQ set <search><q/>... ---->|  Execute search
   |<-- <item/>* + RSM ------------|
   |                               |
   |-- MAM query (per hit) ------->|  Full stanza (XEP-0313)
   |<-- forwarded message ---------|
```

1. The client discovers `urn:xmpp:im:search:0` on the server entity.
2. The client MAY send IQ `get` to learn supported modifiers and whether search is enabled.
3. The client sends IQ `set` with a `<q/>` query string and optional RSM.
4. The server returns `<item/>` hits and RSM metadata.
5. To display or open a message, the client retrieves the full archived stanza via MAM against the `archive` JID.

---

## 5. Discovering Support

Support for this protocol is advertised by [Service Discovery (XEP-0030)](https://xmpp.org/extensions/xep-0030.html) on the **server** entity (the bare domain JID) using a feature var of:

```text
urn:xmpp:im:search:0
```

```xml
<iq type='result' ...>
  <query xmlns='http://jabber.org/protocol/disco#info'>
    ...
    <feature var='urn:xmpp:im:search:0'/>
    ...
  </query>
</iq>
```

If the server does not advertise this feature, the client MUST NOT send search IQs.

Search IQs are addressed to the server domain JID (e.g. `example.com`), not to a user or room JID.

---

## 6. Capabilities Query

### 6.1 Purpose

An IQ `get` on `<search/>` returns machine-readable capability metadata. Clients SHOULD use this before presenting advanced search UI.

### 6.2 Request

```xml
<iq type='get' id='cap1' to='example.com'>
  <search xmlns='urn:xmpp:im:search:0'/>
</iq>
```

### 6.3 Response

```xml
<iq type='result' id='cap1' from='example.com' to='alice@example.com/resource'>
  <search xmlns='urn:xmpp:im:search:0'>
    <enabled>true</enabled>
    <types>messages</types>
    <sort>time</sort>
    <modifiers>
      <modifier>from</modifier>
      <modifier>in</modifier>
      <modifier>before</modifier>
      <modifier>after</modifier>
    </modifiers>
  </search>
</iq>
```

### 6.4 Child Elements

| Element | Cardinality | Description |
|---------|-------------|-------------|
| `<enabled/>` | 1 | `true` or `false`. If `false`, search IQ `set` will return `service-unavailable`. |
| `<types/>` | 1+ | Whitespace-separated list of supported result types. Version 0: `messages`. |
| `<sort/>` | 1+ | Whitespace-separated list of supported sort modes. Version 0: `time`. |
| `<modifiers/>` | 1 | Container for supported query modifiers. |
| `<modifier/>` | 1+ | Local name of a modifier accepted in `<q/>` (without trailing colon). |

---

## 7. Search Request

### 7.1 IQ Semantics

Search is initiated with an IQ of type `set` containing a `<search/>` qualified by `urn:xmpp:im:search:0`.

The requestor is taken from the IQ `from` attribute. The server MUST reject unauthenticated or forged requests according to normal XMPP routing and access rules.

### 7.2 Request Syntax

```xml
<iq type='set' id='search1' to='example.com'>
  <search xmlns='urn:xmpp:im:search:0'>
    <q>QUERY-STRING</q>
    <types>messages</types>    <!-- OPTIONAL -->
    <sort>time</sort>         <!-- OPTIONAL -->
    <set xmlns='http://jabber.org/protocol/rsm'>
      <max>25</max>           <!-- OPTIONAL -->
      <after>CURSOR</after>   <!-- OPTIONAL -->
      <before>CURSOR</before> <!-- OPTIONAL -->
    </set>
  </search>
</iq>
```

### 7.3 Child Elements and Attributes

| Element | Required | Description |
|---------|----------|-------------|
| `<q/>` | **Yes** | Query string. MUST NOT be empty or whitespace-only. See [Section 9](#9-query-language). |
| `<types/>` | No | Result type filter. Default: `messages`. Unknown value: `bad-request`. Unsupported value (e.g. `channels`): `feature-not-implemented`. |
| `<sort/>` | No | Sort mode. Default: `time`. Unsupported value (e.g. `relevance`): `feature-not-implemented`. |
| `<set/>` (RSM) | No | Paging per [Section 11](#11-result-set-management). |

### 7.4 Server Processing

Upon receiving a valid search request, the server:

1. Parses `<q/>` into structured filters.
2. Resolves the requestor's authorized archive scope ([Section 10](#10-access-control)).
3. Executes full-text search against archived message indexes.
4. Merges, sorts, and pages results.
5. Hydrates hit metadata (and snippets) from persistent storage.
6. Returns an IQ `result` per [Section 8](#8-search-results).

---

## 8. Search Results

### 8.1 Response Syntax

```xml
<iq type='result' id='search1' from='example.com' to='alice@example.com/resource'>
  <search xmlns='urn:xmpp:im:search:0' complete='true'>
    <item id='12345'
          type='messages'
          archive='general@conference.example.com'
          from='bob@example.com'
          stamp='2025-06-15T14:30:00.000Z'>
      <snippet>budget review for Q3…</snippet>
    </item>
    <set xmlns='http://jabber.org/protocol/rsm'>
      <first>1718458200000:12345:room_messages</first>
      <last>1718450000000:12001:muc_log</last>
      <count>42</count>
    </set>
  </search>
</iq>
```

### 8.2 The `<search/>` Element

| Attribute | Description |
|-----------|-------------|
| `complete` | OPTIONAL. If present with value `true`, indicates no further pages are available in the current paging direction. |

### 8.3 The `<item/>` Element

Each `<item/>` represents one matching archived message.

| Attribute | Required | Description |
|-----------|----------|-------------|
| `id` | Yes | Archive message identifier (string). Used with MAM to retrieve the full stanza. |
| `type` | Yes | Result type. Version 0: `messages`. |
| `archive` | Yes | Bare JID of the archive owner (user or room). |
| `from` | No | Bare JID of the message sender, when known. |
| `stamp` | Yes | Origination time in [XEP-0082](https://xmpp.org/extensions/xep-0082.html) format. |

| Child | Description |
|-------|-------------|
| `<snippet/>` | OPTIONAL. Short plain-text body excerpt for display. Clients MUST NOT treat the snippet as the canonical message body. |

**Note:** This protocol intentionally does not return MAM `<message/>` or `<forwarded/>` wrappers. Clients retrieve full stanzas via XEP-0313.

### 8.4 RSM in Results

The `<set/>` element qualified by `http://jabber.org/protocol/rsm` MUST be included.

| Element | Description |
|---------|-------------|
| `<first/>` | Cursor of the first hit in this page, if the page is non-empty. |
| `<last/>` | Cursor of the last hit in this page, if the page is non-empty. |
| `<count/>` | Approximate total number of hits matching the query. |

---

## 9. Query Language

The `<q/>` element contains a single UTF-8 string. The string is tokenized and interpreted as free text, quoted phrases, and modifiers.

### 9.1 Tokenization

Tokens are extracted in order using these rules:

1. **Quoted phrase** — text between double quotes (`"..."`). The quotes are not part of the phrase value.
2. **Modifier** — `name:value` where `name` is letters and `value` contains no whitespace (e.g. `from:bob`, `in:room@conf.example`).
3. **Word** — any other non-whitespace run.

Whitespace separates tokens. Leading and trailing whitespace on the full string is ignored.

### 9.2 Free Text

Bare words (tokens that are not quoted phrases or modifiers) are joined with spaces and matched as full-text terms against the message body.

**Example:** `budget report` → full-text match on `budget report`.

### 9.3 Quoted Phrases

A quoted phrase MUST be matched as an exact phrase against the message body.

**Example:** `"exact phrase" later` → phrase `exact phrase` AND free text `later`.

### 9.4 Modifiers

| Modifier | Semantics |
|----------|-----------|
| `from:` | Restrict to messages sent by the resolved bare JID. |
| `in:` | Restrict scope to a MUC room and/or DM peer (see [9.6](#96-in-modifier)). |
| `before:` | Include only messages at or before the resolved instant. |
| `after:` | Include only messages at or after the resolved instant. |

**Combination rules:**

- Modifiers of **different** kinds are combined with logical **AND**.
- Multiple modifiers of the **same** kind are combined with logical **OR**.

**Example:** `budget from:alice from:bob` → body matches `budget` AND (from alice OR from bob).

**Unknown modifiers** (e.g. `has:link`) MUST produce `bad-request` with descriptive error text.

### 9.5 `from:` Value Resolution

| Value | Resolution |
|-------|------------|
| `localpart` | `localpart@<server-domain>` (bare JID) |
| `localpart@domain` | bare JID as given |

Invalid JIDs SHOULD be ignored for that token (the filter has no effect).

### 9.6 `in:` Modifier

| Value | Resolution |
|-------|------------|
| JID on a MUC service | MUC room bare JID |
| Localpart matching a room name | First matching local room on the server, if any |
| User bare JID | DM peer filter on personal archives (`withBare`) |

**Scope when `in:` is present:**

| `in:` specifies | Personal archives | Room archives |
|-----------------|-------------------|---------------|
| (none) | All authorized | All authorized |
| Room(s) only | Not searched | Named rooms ∩ authorized |
| Peer(s) only | Named peers | Not searched |
| Room(s) and peer(s) | Named peers | Named rooms ∩ authorized |
| Only inaccessible rooms | Not searched | Empty result |

The `in:` modifier MUST NOT expand visibility beyond the requestor's authorized scope.

### 9.7 Date Modifiers

`before:` and `after:` values MUST be either:

1. An ISO-8601 instant (e.g. `2025-06-01T12:00:00Z`), or
2. An ISO-8601 date `YYYY-MM-DD`, interpreted in UTC:
   - `after:YYYY-MM-DD` → start of that UTC day (inclusive).
   - `before:YYYY-MM-DD` → end of that UTC day (inclusive).

Invalid date values MUST produce `bad-request`.

### 9.8 Modifier-Only Queries

A query consisting only of modifiers (no free text or phrases) is valid.

**Example:** `from:bob after:2025-01-01`

---

## 10. Access Control

The server MUST enforce access control independently of client-supplied filters. Clients cannot search data they could not retrieve via MAM.

### 10.1 Personal Archives

- Results MUST be limited to archives where `owner` equals the requestor's bare JID.
- The requestor MUST NOT receive hits from another user's personal archive.

### 10.2 MUC Archives

A room archive is searchable if and only if the requestor could issue a MAM query against that room:

| Condition | Access |
|-----------|--------|
| MUC service administrator | Granted |
| Outcast affiliation | Denied |
| Owner or admin affiliation | Granted |
| Members-only room | Member (or higher) required |
| Public room | Granted for non-outcasts |
| Password-protected room | Granted only while the requestor is a current occupant |

If `in:` names only rooms the requestor cannot access, the server MUST return an empty result set (not an error).

### 10.3 Filter Intersection

Client-supplied `in:` and `from:` values are intersected with the authorized scope. They MUST NOT widen it.

---

## 11. Result Set Management

This specification uses [XEP-0059](https://xmpp.org/extensions/xep-0059.html) for paging.

### 11.1 Defaults

- If `<max/>` is omitted, the server SHOULD return up to **25** hits.
- Default sort in version 0 is by message time, **newest first** (`sort` = `time`).

### 11.2 Forward Paging

To request the next page, the client includes `<after/>` with the `<last/>` cursor from the previous response:

```xml
<set xmlns='http://jabber.org/protocol/rsm'>
  <max>25</max>
  <after>1718450000000:12001:muc_log</after>
</set>
```

### 11.3 Backward Paging

Backward paging follows XEP-0059: presence of `<before/>` (including empty) indicates backward direction.

```xml
<set xmlns='http://jabber.org/protocol/rsm'>
  <max>25</max>
  <before>1718458200000:12345:room_messages</before>
</set>
```

### 11.4 Cursor Format

Cursors are opaque to the client. Servers generate strings of the form:

```text
<timestampMillis>:<messageId>:<sourceTag>
```

**Example:** `1718458200000:12345:room_messages`

Clients MUST treat the entire string as opaque and round-trip it unchanged in `<after/>` or `<before/>`.

### 11.5 Unsupported RSM Features

| Feature | Error |
|---------|-------|
| `<index/>` (out-of-order page access) | `feature-not-implemented` |

---

## 12. Error Handling

| Condition | Error condition |
|-----------|-----------------|
| Full-text index unavailable | `service-unavailable` |
| Empty or whitespace-only `<q/>` | `bad-request` |
| Unknown modifier in `<q/>` | `bad-request` (with text) |
| Invalid date in `<q/>` | `bad-request` |
| Unknown `<types/>` token | `bad-request` |
| Unsupported `<types/>` (e.g. `channels`) | `feature-not-implemented` |
| Unsupported `<sort/>` (e.g. `relevance`) | `feature-not-implemented` |
| RSM `<index/>` present | `feature-not-implemented` |
| IQ type other than `get` or `set` | `bad-request` |
| Unexpected server failure | `internal-server-error` |

---

## 13. Relationship to MAM and Full-Text Search

| Use case | Protocol |
|----------|----------|
| Paginated history of one archive | [XEP-0313](https://xmpp.org/extensions/xep-0313.html) (`urn:xmpp:mam:2`) |
| Free-text filter within one MAM query | [XEP-0431](https://xmpp.org/extensions/xep-0431.html) (`{urn:xmpp:fulltext:0}fulltext`) |
| Cross-archive search with modifiers | **This specification** (`urn:xmpp:im:search:0`) |

This extension does not alter MAM or XEP-0431 behavior. Implementations SHOULD continue to support those protocols for standards-compliant clients.

**Recommended retrieval flow:** use this protocol to discover hits, then XEP-0313 to fetch the full archived stanza for a selected hit using `archive` + `id`.

---

## 14. Security Considerations

### 14.1 Authorization

The primary security requirement is that search MUST NOT leak archived messages across trust boundaries. Servers MUST apply the access rules in [Section 10](#10-access-control) on every query. Client-side filters are hints only.

### 14.2 Query Injection

The query string is parsed server-side into a constrained set of filters. The underlying search engine query language MUST NOT be exposed to clients, to prevent injection and coupling to index internals.

### 14.3 Information Disclosure via Snippets

Snippets may expose message content. They MUST be subject to the same authorization rules as full messages. Clients SHOULD treat snippets as sensitive.

### 14.4 Rate Limiting

Servers SHOULD rate-limit search IQs to mitigate abuse of the full-text backend.

### 14.5 Semi-Anonymous MUC

In semi-anonymous rooms, `from` on hits may reflect occupant or real JID depending on room policy and archiving behavior. This is consistent with MAM exposure rules; servers SHOULD align search results with what MAM would return for the same message.

---

## 15. IANA Considerations

This document requires no interaction with IANA.

---

## 16. XMPP Registrar Considerations

### 16.1 Protocol Namespaces

| Namespace | Definition |
|-----------|------------|
| `urn:xmpp:im:search:0` | This document |

### 16.2 Protocol Versioning

Future incompatible changes SHOULD use a new namespace (e.g. `urn:xmpp:im:search:1`).

---

## 17. XML Schema

A formal XML Schema is left for a future revision of this document. The normative protocol definition is the prose and examples in Sections 6–8.

---

## 18. Implementation Notes

This section is non-normative.

### 18.1 Openfire Monitoring Plugin

The reference implementation in the Openfire Monitoring plugin requires:

- Message archiving enabled.
- OpenSearch configured (`conversation.search.opensearch.*` system properties).
- Indexes built or rebuilt after configuration.

See the plugin [readme](../readme.html) for operator documentation.

### 18.2 Index Fields

Full-text search operates on archived message bodies. Modifier `from:` requires sender fields (`senderBare`) in the message index. Operators MUST rebuild indexes after schema changes.

### 18.3 Hit Count Accuracy

The RSM `<count/>` value may be approximate when results are merged from multiple indexes.

---

## 19. Examples

### 19.1 Keyword Search Across All Visible Archives

```xml
<iq type='set' id='s1' to='example.com'>
  <search xmlns='urn:xmpp:im:search:0'>
    <q>deployment</q>
    <set xmlns='http://jabber.org/protocol/rsm'><max>20</max></set>
  </search>
</iq>
```

### 19.2 Room, Author, and Date Range

```xml
<iq type='set' id='s2' to='example.com'>
  <search xmlns='urn:xmpp:im:search:0'>
    <q>from:alice in:general@conference.example.com after:2025-01-01 before:2025-12-31</q>
  </search>
</iq>
```

### 19.3 DM Search with a Specific Peer

```xml
<iq type='set' id='s3' to='example.com'>
  <search xmlns='urn:xmpp:im:search:0'>
    <q>in:bob@example.com contract</q>
  </search>
</iq>
```

### 19.4 Phrase and Free Text

```xml
<iq type='set' id='s4' to='example.com'>
  <search xmlns='urn:xmpp:im:search:0'>
    <q>"release blocker" status</q>
  </search>
</iq>
```

---

## 20. Future Work

The following are reserved but not defined in version 0:

| Feature | `<types/>` or `<sort/>` |
|---------|-------------------------|
| Channel / room list hits | `channels` |
| User list hits | `users` |
| Relevance-ranked results | `sort` = `relevance` |
| Highlight offsets in snippets | TBD |

Clients probing for future features SHOULD handle `feature-not-implemented` and consult IQ `get` capabilities.

---

## Appendix A: Document Information

| | |
|---|---|
| **Canonical namespace** | `urn:xmpp:im:search:0` |
| **Reference implementation** | Openfire Monitoring plugin |
| **Related specifications** | XEP-0030, XEP-0059, XEP-0082, XEP-0313, XEP-0431 |

---

## Appendix B: RFC 2119 Keywords

See [Section 1.3](#13-conformance).

---

## Appendix C: Revision History

### Version 0.1.0 (2026-07-08)

- Initial provisional specification.
- Namespace `urn:xmpp:im:search:0`.
- Message result type, `time` sort, modifiers `from` / `in` / `before` / `after`.
- RSM paging with opaque cursors.
