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

import org.xmpp.packet.JID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Instant;

/**
 * One search hit returned to the client.
 */
public final class SearchHit {

    public enum Source {
        personal,
        room_messages,
        muc_log
    }

    @Nonnull
    private final String id;
    @Nonnull
    private final SearchRequest.ResultType type;
    @Nonnull
    private final JID archive;
    @Nullable
    private final JID from;
    @Nonnull
    private final Instant stamp;
    @Nullable
    private final String snippet;
    @Nonnull
    private final Source source;
    private final long messageId;
    private final long timestampMillis;

    public SearchHit(
        @Nonnull final String id,
        @Nonnull final SearchRequest.ResultType type,
        @Nonnull final JID archive,
        @Nullable final JID from,
        @Nonnull final Instant stamp,
        @Nullable final String snippet,
        @Nonnull final Source source,
        final long messageId,
        final long timestampMillis
    ) {
        this.id = id;
        this.type = type;
        this.archive = archive;
        this.from = from;
        this.stamp = stamp;
        this.snippet = snippet;
        this.source = source;
        this.messageId = messageId;
        this.timestampMillis = timestampMillis;
    }

    @Nonnull
    public String getId() {
        return id;
    }

    @Nonnull
    public SearchRequest.ResultType getType() {
        return type;
    }

    @Nonnull
    public JID getArchive() {
        return archive;
    }

    @Nullable
    public JID getFrom() {
        return from;
    }

    @Nonnull
    public Instant getStamp() {
        return stamp;
    }

    @Nullable
    public String getSnippet() {
        return snippet;
    }

    @Nonnull
    public Source getSource() {
        return source;
    }

    public long getMessageId() {
        return messageId;
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    /**
     * Opaque RSM cursor for this hit.
     */
    @Nonnull
    public String toCursor() {
        return timestampMillis + ":" + messageId + ":" + source.name();
    }
}
