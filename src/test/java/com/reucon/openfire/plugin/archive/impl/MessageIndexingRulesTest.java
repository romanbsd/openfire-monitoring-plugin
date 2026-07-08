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
package com.reucon.openfire.plugin.archive.impl;

import org.junit.Test;
import org.xmpp.packet.JID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link MessageIndexingRules}.
 */
public class MessageIndexingRulesTest {

    @Test
    public void personalMirrorOnlyForPrivateMuc() {
        assertFalse(MessageIndexingRules.shouldIndexPersonalMirror(null));
        assertTrue(MessageIndexingRules.shouldIndexPersonalMirror(new JID("room@conference.example.com/alice")));
    }

    @Test
    public void matchesMucServiceSuffixForRoomBareJid() {
        assertTrue(MessageIndexingRules.matchesMucServiceSuffix(
            "general@conference.example.com",
            "conference",
            "example.com"
        ));
        assertFalse(MessageIndexingRules.matchesMucServiceSuffix(
            "alice@example.com",
            "conference",
            "example.com"
        ));
        assertFalse(MessageIndexingRules.matchesMucServiceSuffix(
            "general@conference.other.com",
            "conference",
            "example.com"
        ));
    }
}
