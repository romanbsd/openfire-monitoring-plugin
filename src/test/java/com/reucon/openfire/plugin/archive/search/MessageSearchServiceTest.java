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

import org.junit.Test;
import org.xmpp.packet.JID;

import java.time.Instant;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Unit tests for {@link MessageSearchService} helpers that need no Openfire runtime.
 */
public class MessageSearchServiceTest {
    private static final JID ARCHIVE = new JID("alice@example.com");

    @Test
    public void parsesCursorWithSource() {
        final MessageSearchService.Cursor cursor = MessageSearchService.parseCursor("1710000000000:42:personal");
        assertNotNull(cursor);
        assertEquals(1710000000000L, cursor.timestamp);
        assertEquals(42L, cursor.messageId);
        assertEquals(SearchHit.Source.personal, cursor.source);
    }

    @Test
    public void parsesCursorWithoutSourceForBackwardsCompatibility() {
        final MessageSearchService.Cursor cursor = MessageSearchService.parseCursor("1710000000000:42");
        assertNotNull(cursor);
        assertEquals(1710000000000L, cursor.timestamp);
        assertEquals(42L, cursor.messageId);
        assertNull(cursor.source);
    }

    @Test
    public void parsesCursorForEachSource() {
        for (final SearchHit.Source source : SearchHit.Source.values()) {
            final MessageSearchService.Cursor cursor = MessageSearchService.parseCursor(
                "1000:1:" + source.name()
            );
            assertNotNull(cursor);
            assertEquals(source, cursor.source);
        }
    }

    @Test
    public void rejectsBlankCursor() {
        assertNull(MessageSearchService.parseCursor(" "));
        assertNull(MessageSearchService.parseCursor(null));
        assertNull(MessageSearchService.parseCursor("bad"));
        assertNull(MessageSearchService.parseCursor("1:2:not-a-source"));
    }

    @Test
    public void dedupesBySourceAndMessageId() {
        final SearchHit personal = hit(1000L, 10L, SearchHit.Source.personal);
        final SearchHit mucLog = hit(1000L, 10L, SearchHit.Source.muc_log);
        final SearchHit duplicatePersonal = hit(1000L, 10L, SearchHit.Source.personal);

        final List<SearchHit> deduped = MessageSearchService.dedupeHits(
            List.of(personal, mucLog, duplicatePersonal)
        );

        assertEquals(2, deduped.size());
        assertEquals(SearchHit.Source.personal, deduped.get(0).getSource());
        assertEquals(SearchHit.Source.muc_log, deduped.get(1).getSource());
    }

    @Test
    public void forwardPagingAfterCursorKeepsStrictlyOlderHits() {
        final SearchHit newer = hit(2000L, 20L, SearchHit.Source.personal);
        final SearchHit cursorHit = hit(2000L, 10L, SearchHit.Source.muc_log);
        final SearchHit older = hit(1999L, 99L, SearchHit.Source.personal);
        final SearchRequest request = SearchRequest.builder()
            .afterCursor(cursorHit.toCursor())
            .build();

        final List<SearchHit> filtered = MessageSearchService.filterByCursor(
            List.of(newer, cursorHit, older),
            request
        );

        assertEquals(1, filtered.size());
        assertEquals(older.getMessageId(), filtered.get(0).getMessageId());
    }

    @Test
    public void forwardPagingAfterCursorBreaksTiesBySource() {
        final SearchHit personalMirror = hit(2000L, 10L, SearchHit.Source.personal);
        final SearchHit mucLog = hit(2000L, 10L, SearchHit.Source.muc_log);
        final SearchRequest request = SearchRequest.builder()
            .afterCursor(mucLog.toCursor())
            .build();

        final List<SearchHit> filtered = MessageSearchService.filterByCursor(
            List.of(personalMirror, mucLog),
            request
        );

        assertEquals(1, filtered.size());
        assertEquals(SearchHit.Source.personal, filtered.get(0).getSource());
    }

    @Test
    public void backwardPagingAfterCursorKeepsStrictlyNewerHits() {
        final SearchHit newer = hit(2000L, 20L, SearchHit.Source.personal);
        final SearchHit cursorHit = hit(2000L, 10L, SearchHit.Source.muc_log);
        final SearchHit older = hit(1999L, 99L, SearchHit.Source.personal);
        final SearchRequest request = SearchRequest.builder()
            .afterCursor(cursorHit.toCursor())
            .pagingBackwards(true)
            .build();

        final List<SearchHit> filtered = MessageSearchService.filterByCursor(
            List.of(older, cursorHit, newer),
            request
        );

        assertEquals(1, filtered.size());
        assertEquals(newer.getMessageId(), filtered.get(0).getMessageId());
    }

    @Test
    public void filterByCursorIsNoOpWithoutCursors() {
        final List<SearchHit> hits = List.of(hit(1L, 1L, SearchHit.Source.personal));
        final SearchRequest request = SearchRequest.builder().build();
        assertEquals(hits, MessageSearchService.filterByCursor(hits, request));
    }

    private static SearchHit hit(final long timestampMillis, final long messageId, final SearchHit.Source source) {
        return new SearchHit(
            Long.toString(messageId),
            SearchRequest.ResultType.messages,
            ARCHIVE,
            ARCHIVE,
            Instant.ofEpochMilli(timestampMillis),
            "snippet",
            source,
            messageId,
            timestampMillis
        );
    }
}
