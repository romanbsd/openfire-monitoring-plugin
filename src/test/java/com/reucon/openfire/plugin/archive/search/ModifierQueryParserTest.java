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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link ModifierQueryParser}.
 */
public class ModifierQueryParserTest {

    @Test
    public void parsesBareText() {
        final SearchRequest request = ModifierQueryParser.parse("budget report");
        assertEquals("budget report", request.getFreeText());
        assertTrue(request.getPhrases().isEmpty());
        assertTrue(request.getFromValues().isEmpty());
    }

    @Test
    public void parsesQuotedPhrase() {
        final SearchRequest request = ModifierQueryParser.parse("\"exact phrase\" later");
        assertEquals(1, request.getPhrases().size());
        assertEquals("exact phrase", request.getPhrases().get(0));
        assertEquals("later", request.getFreeText());
    }

    @Test
    public void parsesFromInBeforeAfter() {
        final SearchRequest request = ModifierQueryParser.parse(
            "budget from:alice in:general@conference.example before:2026-01-01 after:2025-01-01"
        );
        assertEquals("budget", request.getFreeText());
        assertEquals(1, request.getFromValues().size());
        assertEquals("alice", request.getFromValues().get(0));
        assertEquals(1, request.getInValues().size());
        assertEquals("general@conference.example", request.getInValues().get(0));
        assertEquals(LocalDate.of(2025, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC), request.getAfter());
        assertEquals(
            LocalDate.of(2026, 1, 2).atStartOfDay().toInstant(ZoneOffset.UTC).minusMillis(1),
            request.getBefore()
        );
    }

    @Test
    public void parsesRepeatedModifiersAsOrLists() {
        final SearchRequest request = ModifierQueryParser.parse("from:alice from:bob in:room1 in:room2 hello");
        assertEquals(2, request.getFromValues().size());
        assertEquals(2, request.getInValues().size());
        assertEquals("hello", request.getFreeText());
    }

    @Test
    public void parsesInstantDateTime() {
        final SearchRequest request = ModifierQueryParser.parse("after:2025-06-01T12:00:00Z");
        assertEquals(Instant.parse("2025-06-01T12:00:00Z"), request.getAfter());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptyQuery() {
        ModifierQueryParser.parse("   ");
    }

    @Test
    public void rejectsUnknownModifier() {
        try {
            ModifierQueryParser.parse("has:link budget");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("has"));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidDate() {
        ModifierQueryParser.parse("before:not-a-date");
    }
}
