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

import static org.junit.Assert.assertEquals;

/**
 * Unit tests for {@link SearchHit}.
 */
public class SearchHitTest {

    @Test
    public void toCursorEncodesTimestampMessageIdAndSource() {
        final SearchHit hit = new SearchHit(
            "7",
            SearchRequest.ResultType.messages,
            new JID("room@conference.example.com"),
            new JID("alice@example.com"),
            Instant.ofEpochMilli(1710000000000L),
            "hello",
            SearchHit.Source.muc_log,
            7L,
            1710000000000L
        );

        assertEquals("1710000000000:7:muc_log", hit.toCursor());

        final MessageSearchService.Cursor parsed = MessageSearchService.parseCursor(hit.toCursor());
        assertEquals(1710000000000L, parsed.timestamp);
        assertEquals(7L, parsed.messageId);
        assertEquals(SearchHit.Source.muc_log, parsed.source);
    }
}
