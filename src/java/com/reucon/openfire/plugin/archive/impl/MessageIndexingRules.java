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

import org.xmpp.packet.JID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Pure indexing policy helpers for {@link MessageIndexer} (unit-testable without Openfire).
 */
final class MessageIndexingRules {

    private MessageIndexingRules() {
    }

    /**
     * Groupchat MUC rows are indexed in the room document only; personal mirrors are for private MUC (PM) only.
     */
    static boolean shouldIndexPersonalMirror(@Nullable final JID isPMforJID) {
        return isPMforJID != null;
    }

    /**
     * True when {@code bareJid} is a room on the given MUC service (e.g. {@code room@conference.example.com}).
     */
    static boolean matchesMucServiceSuffix(
        @Nonnull final String bareJid,
        @Nonnull final String serviceName,
        @Nonnull final String domain
    ) {
        return bareJid.endsWith("@" + serviceName + "." + domain);
    }
}
