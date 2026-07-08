/*
 * Copyright (C) 2026 Ignite Realtime Foundation. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.reucon.openfire.plugin.archive.search;

import com.reucon.openfire.plugin.archive.impl.JdbcPersistenceManager;
import com.reucon.openfire.plugin.archive.impl.MucMamPersistenceManager;
import com.reucon.openfire.plugin.archive.model.ArchivedMessage;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.index.OpenSearchClientHolder;
import org.jivesoftware.openfire.index.OpenSearchQueryHelper;
import org.jivesoftware.openfire.muc.MUCRoom;
import org.jivesoftware.openfire.muc.MultiUserChatService;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Executes modifier-based message search against OpenSearch indexes and hydrates hits from the DB.
 */
public final class MessageSearchService {

    private static final Logger Log = LoggerFactory.getLogger(MessageSearchService.class);
    private static final int SNIPPET_MAX = 160;

    private MessageSearchService() {
    }

    @Nonnull
    public static SearchResult search(@Nonnull final JID requestor, @Nonnull final SearchRequest request) throws IOException {
        if (!OpenSearchClientHolder.isSearchEnabled()) {
            throw new IllegalStateException("Full-text search is not enabled.");
        }
        if (request.getSort() != SearchRequest.Sort.time) {
            throw new UnsupportedOperationException("Only sort=time is supported in v1.");
        }
        for (final SearchRequest.ResultType type : request.getTypes()) {
            if (type != SearchRequest.ResultType.messages) {
                throw new UnsupportedOperationException("Unsupported search type: " + type);
            }
        }

        final SearchAuthz.Scope scope = SearchAuthz.resolve(requestor, request);
        if (scope.isEmpty()) {
            return new SearchResult(List.of(), 0, true);
        }

        final OpenSearchClient client = OpenSearchClientHolder.getClient();
        final boolean paged = request.getAfterCursor() != null || request.getBeforeCursor() != null;
        final int fetchSize = paged
            ? Math.max(request.getMaxResults() + 1, 100)
            : request.getMaxResults() + 1;
        final List<SearchHit> merged = new ArrayList<>();
        long total = 0;

        if (scope.isIncludePersonal()) {
            final Query personalQuery = buildMessagesIndexQuery(request, scope, true);
            final OpenSearchQueryHelper.SearchHits hits = OpenSearchQueryHelper.search(
                client,
                OpenSearchClientHolder.indexName("messages"),
                personalQuery,
                "sentDate",
                !request.isPagingBackwards(),
                fetchSize
            );
            total += hits.total();
            for (final Map<String, Object> source : hits.sources()) {
                final SearchHit hit = hydrateFromMessagesIndex(source, scope.getRequestor(), true);
                if (hit != null) {
                    merged.add(hit);
                }
            }
        }

        if (scope.isIncludeRooms() && !scope.getAllowedRoomJids().isEmpty()) {
            final Query roomMessagesQuery = buildMessagesIndexQuery(request, scope, false);
            final OpenSearchQueryHelper.SearchHits roomHits = OpenSearchQueryHelper.search(
                client,
                OpenSearchClientHolder.indexName("messages"),
                roomMessagesQuery,
                "sentDate",
                !request.isPagingBackwards(),
                fetchSize
            );
            total += roomHits.total();
            for (final Map<String, Object> source : roomHits.sources()) {
                final SearchHit hit = hydrateFromMessagesIndex(source, scope.getRequestor(), false);
                if (hit != null) {
                    merged.add(hit);
                }
            }
        }

        if (scope.isIncludeRooms() && !scope.getAllowedRoomIds().isEmpty()) {
            final Query mucQuery = buildMucIndexQuery(request, scope);
            final OpenSearchQueryHelper.SearchHits mucHits = OpenSearchQueryHelper.search(
                client,
                OpenSearchClientHolder.indexName("muc-messages"),
                mucQuery,
                "logTime",
                !request.isPagingBackwards(),
                fetchSize
            );
            total += mucHits.total();
            for (final Map<String, Object> source : mucHits.sources()) {
                final SearchHit hit = hydrateFromMucIndex(source, scope);
                if (hit != null) {
                    merged.add(hit);
                }
            }
        }

        final Comparator<SearchHit> byTime = Comparator
            .comparingLong(SearchHit::getTimestampMillis)
            .thenComparingLong(SearchHit::getMessageId)
            .thenComparing(hit -> hit.getSource().name());
        if (request.isPagingBackwards()) {
            merged.sort(byTime);
        } else {
            merged.sort(byTime.reversed());
        }

        List<SearchHit> deduped = dedupeHits(merged);
        deduped = filterByCursor(deduped, request);
        final boolean complete = deduped.size() <= request.getMaxResults();
        final List<SearchHit> page = deduped.stream().limit(request.getMaxResults()).toList();
        return new SearchResult(page, (int) Math.min(Integer.MAX_VALUE, total), complete);
    }

    @Nonnull
    static Query buildMessagesIndexQuery(
        @Nonnull final SearchRequest request,
        @Nonnull final SearchAuthz.Scope scope,
        final boolean personal
    ) {
        final List<Query> filters = new ArrayList<>();
        addTextFilters(filters, request);
        addFromFilters(filters, scope.getFromJids());
        addDateFilters(filters, request, "sentDate");
        addCursorFilters(filters, request, "sentDate");

        if (personal) {
            filters.add(OpenSearchQueryHelper.termQuery("owner", scope.getRequestor().toBareJID()));
            addExcludeGroupchatMirrors(filters);
            if (!scope.getPeerJids().isEmpty()) {
                final List<String> peers = scope.getPeerJids().stream().map(JID::toBareJID).toList();
                filters.add(OpenSearchQueryHelper.termsQuery("withBare", peers));
            }
        } else {
            final List<String> rooms = scope.getAllowedRoomJids().stream().map(JID::toBareJID).toList();
            filters.add(OpenSearchQueryHelper.termsQuery("room", rooms));
            filters.add(OpenSearchQueryHelper.termQuery("isPrivateMessage", "false"));
        }

        return OpenSearchQueryHelper.boolMust(filters);
    }

    @Nonnull
    static Query buildMucIndexQuery(@Nonnull final SearchRequest request, @Nonnull final SearchAuthz.Scope scope) {
        final List<Query> filters = new ArrayList<>();
        addTextFilters(filters, request);
        addFromFilters(filters, scope.getFromJids());
        addDateFilters(filters, request, "logTime");
        addCursorFilters(filters, request, "logTime");
        filters.add(OpenSearchQueryHelper.termsQueryLong("roomID", scope.getAllowedRoomIds()));
        return OpenSearchQueryHelper.boolMust(filters);
    }

    private static void addTextFilters(final List<Query> filters, final SearchRequest request) {
        if (request.getFreeText() != null && !request.getFreeText().isBlank()) {
            filters.add(OpenSearchQueryHelper.matchText("body", request.getFreeText()));
        }
        for (final String phrase : request.getPhrases()) {
            filters.add(OpenSearchQueryHelper.matchPhrase("body", phrase));
        }
    }

    private static void addFromFilters(final List<Query> filters, final Set<JID> fromJids) {
        if (!fromJids.isEmpty()) {
            final List<String> values = fromJids.stream().map(JID::toBareJID).collect(Collectors.toList());
            filters.add(OpenSearchQueryHelper.termsQuery("senderBare", values));
        }
    }

    private static void addDateFilters(final List<Query> filters, final SearchRequest request, final String field) {
        Long min = request.getAfter() != null ? request.getAfter().toEpochMilli() : null;
        Long max = request.getBefore() != null ? request.getBefore().toEpochMilli() : null;
        if (min != null || max != null) {
            filters.add(OpenSearchQueryHelper.rangeQuery(field, min, max));
        }
    }

    private static void addCursorFilters(final List<Query> filters, final SearchRequest request, final String timeField) {
        final Cursor after = parseCursor(request.getAfterCursor());
        final Cursor before = parseCursor(request.getBeforeCursor());
        if (after != null) {
            if (request.isPagingBackwards()) {
                filters.add(OpenSearchQueryHelper.newerThanSortKey(timeField, after.timestamp, after.messageId));
            } else {
                filters.add(OpenSearchQueryHelper.olderThanSortKey(timeField, after.timestamp, after.messageId));
            }
        }
        if (before != null) {
            if (request.isPagingBackwards()) {
                filters.add(OpenSearchQueryHelper.olderThanSortKey(timeField, before.timestamp, before.messageId));
            } else {
                filters.add(OpenSearchQueryHelper.newerThanSortKey(timeField, before.timestamp, before.messageId));
            }
        }
    }

    @Nullable
    private static SearchHit hydrateFromMessagesIndex(
        final Map<String, Object> source,
        final JID requestor,
        final boolean personal
    ) {
        final OptionalLong messageIdOpt = OpenSearchQueryHelper.parseLongField(source, "messageID");
        final OptionalLong sentDateOpt = OpenSearchQueryHelper.parseLongField(source, "sentDate");
        if (messageIdOpt.isEmpty() || sentDateOpt.isEmpty()) {
            return null;
        }
        final long messageId = messageIdOpt.getAsLong();
        final long sentDate = sentDateOpt.getAsLong();

        final JID archive;
        final SearchHit.Source hitSource;
        final String senderBare = OpenSearchQueryHelper.parseStringField(source, "senderBare");
        JID from = null;
        try {
            if (senderBare != null) {
                from = new JID(senderBare);
            }
        } catch (IllegalArgumentException ignored) {
            from = null;
        }

        String snippet = null;
        if (personal) {
            archive = requestor;
            hitSource = SearchHit.Source.personal;
            final ArchivedMessage message = JdbcPersistenceManager.getArchivedMessage(messageId, requestor);
            if (message == null) {
                return null;
            }
            snippet = snippetOf(message.getBody());
            if (from == null && message.getDirection() == ArchivedMessage.Direction.from) {
                from = message.getWith();
            } else if (from == null) {
                from = requestor;
            }
        } else {
            final String room = OpenSearchQueryHelper.parseStringField(source, "room");
            if (room == null) {
                return null;
            }
            try {
                archive = new JID(room);
            } catch (IllegalArgumentException e) {
                return null;
            }
            hitSource = SearchHit.Source.room_messages;
            final MUCRoom mucRoom = lookupRoom(archive);
            if (mucRoom == null) {
                snippet = snippetOf(OpenSearchQueryHelper.parseStringField(source, "body"));
            } else {
                final ArchivedMessage message = MucMamPersistenceManager.getArchivedMessage(messageId, mucRoom);
                if (message != null) {
                    snippet = snippetOf(message.getBody());
                    if (from == null) {
                        from = message.getWith();
                    }
                } else {
                    snippet = snippetOf(OpenSearchQueryHelper.parseStringField(source, "body"));
                }
            }
        }

        return new SearchHit(
            Long.toString(messageId),
            SearchRequest.ResultType.messages,
            archive,
            from,
            Instant.ofEpochMilli(sentDate),
            snippet,
            hitSource,
            messageId,
            sentDate
        );
    }

    @Nullable
    private static SearchHit hydrateFromMucIndex(final Map<String, Object> source, final SearchAuthz.Scope scope) {
        final OptionalLong messageIdOpt = OpenSearchQueryHelper.parseLongField(source, "messageID");
        final OptionalLong roomIdOpt = OpenSearchQueryHelper.parseLongField(source, "roomID");
        final OptionalLong logTimeOpt = OpenSearchQueryHelper.parseLongField(source, "logTime");
        if (messageIdOpt.isEmpty() || roomIdOpt.isEmpty() || logTimeOpt.isEmpty()) {
            return null;
        }
        final long messageId = messageIdOpt.getAsLong();
        final long roomId = roomIdOpt.getAsLong();
        final long logTime = logTimeOpt.getAsLong();
        final JID archive = scope.roomJidForId(roomId);
        if (archive == null) {
            return null;
        }
        final String senderBare = OpenSearchQueryHelper.parseStringField(source, "senderBare");
        JID from = null;
        try {
            if (senderBare != null) {
                from = new JID(senderBare);
            }
        } catch (IllegalArgumentException ignored) {
            from = null;
        }
        String snippet = snippetOf(OpenSearchQueryHelper.parseStringField(source, "body"));
        final MUCRoom room = lookupRoom(archive);
        if (room != null) {
            final ArchivedMessage message = MucMamPersistenceManager.getArchivedMessage(messageId, room);
            if (message != null) {
                snippet = snippetOf(message.getBody());
                if (from == null) {
                    from = message.getWith();
                }
            }
        }
        return new SearchHit(
            Long.toString(messageId),
            SearchRequest.ResultType.messages,
            archive,
            from,
            Instant.ofEpochMilli(logTime),
            snippet,
            SearchHit.Source.muc_log,
            messageId,
            logTime
        );
    }

    @Nullable
    private static MUCRoom lookupRoom(final JID roomJid) {
        final MultiUserChatService service = XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatService(roomJid);
        if (service == null) {
            return null;
        }
        return service.getChatRoom(roomJid.getNode());
    }

    private static void addExcludeGroupchatMirrors(final List<Query> filters) {
        final List<Query> mustNot = new ArrayList<>();
        final String domain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        for (final MultiUserChatService service : XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatServices()) {
            final String pattern = "*@" + service.getServiceName() + "." + domain;
            mustNot.add(Query.of(q -> q.wildcard(w -> w.field("withBare").value(pattern))));
        }
        if (!mustNot.isEmpty()) {
            filters.add(Query.of(q -> q.bool(b -> b.mustNot(mustNot))));
        }
    }

    static List<SearchHit> filterByCursor(final List<SearchHit> hits, final SearchRequest request) {
        final Cursor after = parseCursor(request.getAfterCursor());
        final Cursor before = parseCursor(request.getBeforeCursor());
        if (after == null && before == null) {
            return hits;
        }
        return hits.stream()
            .filter(hit -> {
                if (after != null) {
                    if (request.isPagingBackwards()) {
                        if (!isStrictlyNewerThanCursor(hit, after)) {
                            return false;
                        }
                    } else if (!isStrictlyOlderThanCursor(hit, after)) {
                        return false;
                    }
                }
                if (before != null) {
                    if (request.isPagingBackwards()) {
                        if (!isStrictlyOlderThanCursor(hit, before)) {
                            return false;
                        }
                    } else if (!isStrictlyNewerThanCursor(hit, before)) {
                        return false;
                    }
                }
                return true;
            })
            .toList();
    }

    private static boolean isStrictlyOlderThanCursor(final SearchHit hit, final Cursor cursor) {
        final int ts = Long.compare(hit.getTimestampMillis(), cursor.timestamp);
        if (ts < 0) {
            return true;
        }
        if (ts > 0) {
            return false;
        }
        final int id = Long.compare(hit.getMessageId(), cursor.messageId);
        if (id < 0) {
            return true;
        }
        if (id > 0) {
            return false;
        }
        return cursor.source != null && hit.getSource().compareTo(cursor.source) < 0;
    }

    private static boolean isStrictlyNewerThanCursor(final SearchHit hit, final Cursor cursor) {
        final int ts = Long.compare(hit.getTimestampMillis(), cursor.timestamp);
        if (ts > 0) {
            return true;
        }
        if (ts < 0) {
            return false;
        }
        final int id = Long.compare(hit.getMessageId(), cursor.messageId);
        if (id > 0) {
            return true;
        }
        if (id < 0) {
            return false;
        }
        return cursor.source != null && hit.getSource().compareTo(cursor.source) > 0;
    }

    static List<SearchHit> dedupeHits(final List<SearchHit> hits) {
        final List<SearchHit> result = new ArrayList<>();
        final java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (final SearchHit hit : hits) {
            final String key = hit.getSource().name() + ":" + hit.getMessageId();
            if (seen.add(key)) {
                result.add(hit);
            }
        }
        return result;
    }

    @Nullable
    private static String snippetOf(@Nullable final String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        final String trimmed = body.trim().replaceAll("\\s+", " ");
        if (trimmed.length() <= SNIPPET_MAX) {
            return trimmed;
        }
        return trimmed.substring(0, SNIPPET_MAX - 1) + "…";
    }

    @Nullable
    static Cursor parseCursor(@Nullable final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        final String[] parts = value.split(":", 3);
        if (parts.length < 2) {
            return null;
        }
        try {
            final SearchHit.Source source = parts.length >= 3
                ? SearchHit.Source.valueOf(parts[2])
                : null;
            return new Cursor(Long.parseLong(parts[0]), Long.parseLong(parts[1]), source);
        } catch (IllegalArgumentException e) {
            Log.debug("Ignoring invalid search cursor '{}'", value);
            return null;
        }
    }

    static final class Cursor {
        final long timestamp;
        final long messageId;
        @Nullable
        final SearchHit.Source source;

        Cursor(final long timestamp, final long messageId, @Nullable final SearchHit.Source source) {
            this.timestamp = timestamp;
            this.messageId = messageId;
            this.source = source;
        }
    }
}
