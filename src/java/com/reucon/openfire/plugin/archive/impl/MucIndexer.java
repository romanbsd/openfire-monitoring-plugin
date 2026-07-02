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
import org.jivesoftware.openfire.archive.ConversationManager;
import org.jivesoftware.openfire.index.OpenSearchClientHolder;
import org.jivesoftware.openfire.index.OpenSearchIndexer;
import org.jivesoftware.openfire.reporting.util.TaskEngine;
import org.jivesoftware.util.StringUtils;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.xmpp.packet.JID;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Creates and maintains an OpenSearch index for messages exchanged in multi-user chat.
 */
public class MucIndexer extends OpenSearchIndexer
{
    public static final int SCHEMA_VERSION = 1;
    private static final String INDEX_SUFFIX = "muc-messages";

    public static final String ALL_MUC_MESSAGES = "SELECT roomID, sender, logTime, body, messageID FROM ofMucConversationLog WHERE messageID IS NOT NULL";
    public static final String NEW_MUC_MESSAGES = "SELECT roomID, sender, logTime, body, messageID FROM ofMucConversationLog WHERE messageID IS NOT NULL AND logTime > ?";

    private final ConversationManager conversationManager;
    private final Set<Long> roomsPendingDeletion = new HashSet<>();

    public MucIndexer(final TaskEngine taskEngine, final ConversationManager conversationManager)
    {
        super(taskEngine, "MUCSEARCH", INDEX_SUFFIX, SCHEMA_VERSION);
        this.conversationManager = conversationManager;
    }

    public void scheduleForDeletion(final Long roomID)
    {
        roomsPendingDeletion.add(roomID);
    }

    @Override
    protected void ensureIndexExists() throws IOException {
        OpenSearchClientHolder.ensureIndex(getClient(), getIndexName(), mucMappings());
    }

    @Override
    protected Instant doUpdateIndex(final Instant lastModified) throws IOException {
        if (!conversationManager.isRoomArchivingEnabled()) {
            return lastModified;
        }
        Log.debug("... started to index MUC messages since {} to update the OpenSearch index.", lastModified);
        final Instant newestDate = indexMUCMessages(lastModified);
        Log.debug("... finished indexing MUC messages to update the OpenSearch index. Last indexed message date: {}", newestDate);
        return newestDate;
    }

    @Override
    protected Instant doRebuildIndex() throws IOException {
        if (!conversationManager.isRoomArchivingEnabled()) {
            return Instant.EPOCH;
        }
        Log.debug("... started to index MUC messages to rebuild the OpenSearch index.");
        final Instant newestDate = indexMUCMessages(Instant.EPOCH);
        Log.debug("... finished indexing MUC messages to rebuild the OpenSearch index. Last indexed message date {}", newestDate);
        return newestDate;
    }

    private Instant indexMUCMessages(final Instant since) throws IOException {
        Instant latest = since;
        final OpenSearchClient client = getClient();
        final List<BulkOperation> batch = new ArrayList<>();

        Connection con = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            con = DbConnectionManager.getTransactionConnection();

            if (since.equals(Instant.EPOCH)) {
                pstmt = con.prepareStatement(ALL_MUC_MESSAGES, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            } else {
                pstmt = con.prepareStatement(NEW_MUC_MESSAGES, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                pstmt.setString(1, StringUtils.dateToMillis(Date.from(since)));
            }

            pstmt.setFetchSize(250);
            pstmt.setFetchDirection(ResultSet.FETCH_FORWARD);
            rs = pstmt.executeQuery();

            long progress = 0;
            Instant lastProgressReport = Instant.now();
            while (rs.next()) {
                final long roomID = rs.getLong("roomID");
                final long messageID = rs.getLong("messageID");
                final JID sender;
                try {
                    sender = new JID(rs.getString("sender"));
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                final Instant logTime = Instant.ofEpochMilli(Long.parseLong(rs.getString("logTime")));
                final String body = DbConnectionManager.getLargeTextField(rs, 4);
                if (body == null || roomsPendingDeletion.contains(roomID)) {
                    continue;
                }

                batch.add(BulkOperation.of(op -> op.index(idx -> idx
                    .index(getIndexName())
                    .id(roomID + "-" + messageID)
                    .document(createDocument(roomID, messageID, sender, logTime, body))
                )));
                if (batch.size() >= 250) {
                    OpenSearchClientHolder.bulkIndex(client, getIndexName(), batch);
                    batch.clear();
                }

                if (logTime.isAfter(latest)) {
                    latest = logTime;
                }

                ++progress;
                if (lastProgressReport.isBefore(Instant.now().minus(10, ChronoUnit.SECONDS))) {
                    Log.debug("... processed {} messages so far.", progress);
                    lastProgressReport = Instant.now();
                }
            }
            OpenSearchClientHolder.bulkIndex(client, getIndexName(), batch);

            if (!since.equals(Instant.EPOCH) && !roomsPendingDeletion.isEmpty()) {
                for (long roomID : roomsPendingDeletion) {
                    OpenSearchClientHolder.deleteByQuery(
                        client,
                        getIndexName(),
                        Query.of(q -> q.term(t -> t.field("roomID").value(FieldValue.of(roomID))))
                    );
                }
            }
            roomsPendingDeletion.clear();
            Log.debug("... finished the entire result set. Processed {} messages in total.", progress);
        } catch (SQLException sqle) {
            Log.error("An exception occurred while trying to fetch MUC messages from the database to update the OpenSearch index.", sqle);
        } finally {
            DbConnectionManager.closeResultSet(rs);
            DbConnectionManager.closeTransactionConnection(pstmt, con, false);
        }
        return latest;
    }

    private static Map<String, Object> createDocument(final long roomID, final long messageID, final JID sender, final Instant logTime, final String body)
    {
        final Map<String, Object> document = new HashMap<>();
        document.put("roomID", roomID);
        document.put("messageID", messageID);
        document.put("messageIDRange", messageID);
        document.put("senderBare", sender.toBareJID());
        if (sender.getResource() != null) {
            document.put("senderResource", sender.getResource());
        }
        document.put("logTime", logTime.toEpochMilli());
        document.put("body", body);
        return document;
    }

    private static Map<String, Object> mucMappings() {
        final Map<String, Object> mappings = new HashMap<>();
        mappings.put("roomID", "{\"type\":\"long\"}");
        mappings.put("messageID", "{\"type\":\"long\"}");
        mappings.put("messageIDRange", "{\"type\":\"long\"}");
        mappings.put("senderBare", "{\"type\":\"keyword\"}");
        mappings.put("senderResource", "{\"type\":\"keyword\"}");
        mappings.put("logTime", "{\"type\":\"long\"}");
        mappings.put("body", "{\"type\":\"text\"}");
        return mappings;
    }
}
