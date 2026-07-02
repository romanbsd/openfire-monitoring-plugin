/*
 * Copyright (C) 2008 Jive Software, 2024-2026 Ignite Realtime Foundation. All rights reserved.
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

import org.jivesoftware.database.DbConnectionManager;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.archive.ConversationManager;
import org.jivesoftware.openfire.index.OpenSearchClientHolder;
import org.jivesoftware.openfire.index.OpenSearchIndexer;
import org.jivesoftware.openfire.reporting.util.TaskEngine;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.xmpp.packet.JID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates and maintains an OpenSearch index for archived messages.
 */
public class MessageIndexer extends OpenSearchIndexer
{
    public static final int SCHEMA_VERSION = 1;
    private static final String INDEX_SUFFIX = "messages";

    public static final String ALL_MESSAGES = "SELECT fromJID, fromJIDResource, toJID, toJIDResource, sentDate, body, messageID, isPMforJID "
        + "FROM ofMessageArchive "
        + "WHERE body IS NOT NULL "
        + "AND messageID IS NOT NULL";

    public static final String NEW_MESSAGES = ALL_MESSAGES + " AND sentDate > ?";

    private final ConversationManager conversationManager;

    public MessageIndexer(final TaskEngine taskEngine, final ConversationManager conversationManager)
    {
        super(taskEngine, "MESSAGE", INDEX_SUFFIX, SCHEMA_VERSION);
        this.conversationManager = conversationManager;
    }

    @Override
    protected void ensureIndexExists() throws IOException {
        OpenSearchClientHolder.ensureIndex(getClient(), getIndexName(), messageMappings());
    }

    @Override
    protected Instant doUpdateIndex(final Instant lastModified) throws IOException {
        if (!conversationManager.isMessageArchivingEnabled()) {
            return lastModified;
        }
        Log.debug("... started to index messages since {} to update the OpenSearch index.", lastModified);
        final Instant newestDate = indexMessages(lastModified, false);
        Log.debug("... finished indexing messages to update the OpenSearch index. Last indexed message date: {}", newestDate);
        return newestDate;
    }

    @Override
    protected Instant doRebuildIndex() throws IOException {
        if (!conversationManager.isMessageArchivingEnabled()) {
            return Instant.EPOCH;
        }
        Log.debug("... started to index messages to rebuild the OpenSearch index.");
        final Instant newestDate = indexMessages(Instant.EPOCH, true);
        Log.debug("... finished indexing messages to rebuild the OpenSearch index. Last indexed message date {}", newestDate);
        return newestDate;
    }

    private Instant indexMessages(final Instant since, final boolean rebuild) throws IOException {
        Instant latest = since;
        final OpenSearchClient client = getClient();
        final List<BulkOperation> batch = new ArrayList<>();

        Connection con = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            con = DbConnectionManager.getTransactionConnection();
            if (since.equals(Instant.EPOCH)) {
                pstmt = con.prepareStatement(ALL_MESSAGES);
            } else {
                pstmt = con.prepareStatement(NEW_MESSAGES);
                pstmt.setLong(1, java.util.Date.from(since).getTime());
            }

            pstmt.setFetchSize(250);
            rs = pstmt.executeQuery();

            long progress = 0;
            Instant lastProgressReport = Instant.now();
            while (rs.next()) {
                final long messageID = rs.getLong("messageID");
                final String fromJIDBare = rs.getString("fromJID");
                final String fromJIDResource = rs.getString("fromJIDResource");
                final JID fromJID;
                try {
                    fromJID = new JID(fromJIDResource == null || fromJIDResource.isEmpty() ? fromJIDBare : fromJIDBare + "/" + fromJIDResource);
                } catch (IllegalArgumentException ex) {
                    continue;
                }

                final String toJIDBare = rs.getString("toJID");
                final String toJIDResource = rs.getString("toJIDResource");
                final JID toJID;
                try {
                    toJID = new JID(toJIDResource == null || toJIDResource.isEmpty() ? toJIDBare : toJIDBare + "/" + toJIDResource);
                } catch (IllegalArgumentException ex) {
                    continue;
                }

                final String isPMforJIDValue = rs.getString("isPMforJID");
                final JID isPMforJID;
                if (isPMforJIDValue == null) {
                    isPMforJID = null;
                } else {
                    try {
                        isPMforJID = new JID(isPMforJIDValue);
                    } catch (IllegalArgumentException ex) {
                        continue;
                    }
                }
                final Instant sentDate = Instant.ofEpochMilli(Long.parseLong(rs.getString("sentDate")));
                final String body = DbConnectionManager.getLargeTextField(rs, 6);
                if (body == null) {
                    continue;
                }

                if (XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatService(toJID) != null) {
                    final JID room = toJID.asBareJID();
                    final JID pmFromJID = isPMforJID != null ? fromJID.asBareJID() : null;
                    batch.add(buildBulkOperation(createMUCDocument(room, messageID, pmFromJID, isPMforJID, sentDate, body), room.toBareJID() + "-" + messageID + "-room"));
                    flushBatchIfNeeded(client, batch);

                    if (XMPPServer.getInstance().isLocal(fromJID)) {
                        batch.add(buildBulkOperation(createPersonalDocument(fromJID.asBareJID(), messageID, fromJID, toJID, sentDate, body), fromJID.toBareJID() + "-" + messageID));
                        flushBatchIfNeeded(client, batch);
                    }
                } else {
                    if (XMPPServer.getInstance().isLocal(fromJID)) {
                        batch.add(buildBulkOperation(createPersonalDocument(fromJID.asBareJID(), messageID, fromJID, toJID, sentDate, body), fromJID.toBareJID() + "-" + messageID));
                        flushBatchIfNeeded(client, batch);
                    }
                    if (XMPPServer.getInstance().isLocal(toJID)) {
                        batch.add(buildBulkOperation(createPersonalDocument(toJID.asBareJID(), messageID, fromJID, toJID, sentDate, body), toJID.toBareJID() + "-" + messageID));
                        flushBatchIfNeeded(client, batch);
                    }
                }

                if (sentDate.isAfter(latest)) {
                    latest = sentDate;
                }

                ++progress;
                if (lastProgressReport.isBefore(Instant.now().minus(10, ChronoUnit.SECONDS))) {
                    Log.debug("... processed {} messages so far.", progress);
                    lastProgressReport = Instant.now();
                }
            }
            OpenSearchClientHolder.bulkIndex(client, getIndexName(), batch);
            Log.debug("... finished the entire result set. Processed {} messages in total.", progress);
        } catch (SQLException sqle) {
            Log.error("An exception occurred while trying to fetch messages from the database to update the OpenSearch index.", sqle);
        } finally {
            DbConnectionManager.closeResultSet(rs);
            DbConnectionManager.closeTransactionConnection(pstmt, con, false);
        }
        return latest;
    }

    private void flushBatchIfNeeded(final OpenSearchClient client, final List<BulkOperation> batch) throws IOException {
        if (batch.size() >= 250) {
            OpenSearchClientHolder.bulkIndex(client, getIndexName(), batch);
            batch.clear();
        }
    }

    private BulkOperation buildBulkOperation(final Map<String, Object> document, final String id) {
        return BulkOperation.of(op -> op.index(idx -> idx.index(getIndexName()).id(id).document(document)));
    }

    private static Map<String, Object> createPersonalDocument(
        @Nonnull final JID owner,
        final long messageID,
        @Nonnull final JID fromJID,
        @Nonnull final JID toJID,
        @Nonnull final Instant sentDate,
        @Nonnull final String body)
    {
        final JID with = owner.asBareJID().equals(fromJID.asBareJID()) ? toJID : fromJID;
        final Map<String, Object> document = new HashMap<>();
        document.put("messageID", messageID);
        document.put("messageIDRange", messageID);
        document.put("owner", owner.toBareJID());
        document.put("withBare", with.toBareJID());
        if (with.getResource() != null) {
            document.put("withResource", with.getResource());
        }
        document.put("sentDate", sentDate.toEpochMilli());
        document.put("body", body);
        return document;
    }

    private static Map<String, Object> createMUCDocument(
        @Nonnull final JID owner,
        final long messageID,
        @Nullable final JID pmFromJID,
        @Nullable final JID pmToJID,
        @Nonnull final Instant sentDate,
        @Nonnull final String body)
    {
        final Map<String, Object> document = new HashMap<>();
        document.put("messageID", messageID);
        document.put("messageIDRange", messageID);
        document.put("room", owner.toBareJID());
        document.put("isPrivateMessage", Boolean.toString(pmFromJID != null || pmToJID != null));
        if (pmFromJID != null) {
            document.put("pmFromJID", pmFromJID.toBareJID());
        }
        if (pmToJID != null) {
            document.put("pmToJID", pmToJID.toBareJID());
        }
        document.put("sentDate", sentDate.toEpochMilli());
        document.put("body", body);
        return document;
    }

    private static Map<String, Object> messageMappings() {
        final Map<String, Object> mappings = new HashMap<>();
        mappings.put("messageID", "{\"type\":\"long\"}");
        mappings.put("messageIDRange", "{\"type\":\"long\"}");
        mappings.put("owner", "{\"type\":\"keyword\"}");
        mappings.put("withBare", "{\"type\":\"keyword\"}");
        mappings.put("withResource", "{\"type\":\"keyword\"}");
        mappings.put("room", "{\"type\":\"keyword\"}");
        mappings.put("isPrivateMessage", "{\"type\":\"keyword\"}");
        mappings.put("pmFromJID", "{\"type\":\"keyword\"}");
        mappings.put("pmToJID", "{\"type\":\"keyword\"}");
        mappings.put("sentDate", "{\"type\":\"long\"}");
        mappings.put("body", "{\"type\":\"text\"}");
        return mappings;
    }
}
