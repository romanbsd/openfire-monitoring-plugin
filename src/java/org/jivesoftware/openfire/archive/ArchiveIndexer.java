/*
 * Copyright (C) 2008 Jive Software, Ignite Realtime Foundation 2024-2026. All rights reserved.
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

package org.jivesoftware.openfire.archive;

import org.jivesoftware.database.DbConnectionManager;
import org.jivesoftware.openfire.index.OpenSearchClientHolder;
import org.jivesoftware.openfire.index.OpenSearchIndexer;
import org.jivesoftware.openfire.reporting.util.TaskEngine;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Indexes archived conversations in OpenSearch for admin console full-text search.
 */
public class ArchiveIndexer extends OpenSearchIndexer
{
    private static final String ALL_CONVERSATIONS = "SELECT conversationID, isExternal FROM ofConversation";
    private static final String NEW_CONVERSATIONS = "SELECT DISTINCT conversationID FROM ofMessageArchive WHERE sentDate > ?";
    private static final String CONVERSATION_METADATA = "SELECT isExternal FROM ofConversation WHERE conversationID=?";
    private static final String CONVERSATION_MESSAGES = "SELECT conversationID, sentDate, fromJID, toJID, body FROM ofMessageArchive WHERE conversationID IN ? ORDER BY conversationID";

    public static final int SCHEMA_VERSION = 1;
    private static final String INDEX_SUFFIX = "conversations";

    private ConversationManager conversationManager;
    private final Set<Long> conversationsPendingDeletion = new HashSet<>();

    public ArchiveIndexer(ConversationManager conversationManager, TaskEngine taskEngine) {
        super(taskEngine, "CONVERSATION", INDEX_SUFFIX, SCHEMA_VERSION);
        this.conversationManager = conversationManager;
    }

    @Override
    public void stop()
    {
        super.stop();
        conversationManager = null;
    }

    public void scheduleForDeletion(final Set<Long> conversations)
    {
        conversationsPendingDeletion.addAll(conversations);
    }

    @Override
    protected void ensureIndexExists() throws IOException {
        OpenSearchClientHolder.ensureIndex(getClient(), getIndexName(), conversationMappings());
    }

    @Override
    protected Instant doUpdateIndex(final Instant lastModified) throws IOException {
        if (!conversationManager.isArchivingEnabled()) {
            return lastModified;
        }

        final List<Long> conversationIDs = findModifiedConversations(lastModified);
        if (conversationIDs.isEmpty() && conversationsPendingDeletion.isEmpty()) {
            return lastModified;
        }

        final SortedMap<Long, Boolean> externalMetaData = extractMetaData(conversationIDs);
        conversationIDs.addAll(conversationsPendingDeletion);
        conversationsPendingDeletion.clear();

        for (long conversationID : conversationIDs) {
            OpenSearchClientHolder.deleteByQuery(
                getClient(),
                getIndexName(),
                Query.of(q -> q.term(t -> t.field("conversationID").value(FieldValue.of(conversationID))))
            );
        }

        Log.debug("... started to index conversations to update the OpenSearch index.");
        final Instant newestDate = indexConversations(externalMetaData, false);
        return newestDate.isAfter(lastModified) ? newestDate : lastModified;
    }

    @Override
    protected Instant doRebuildIndex() throws IOException {
        if (!conversationManager.isArchivingEnabled()) {
            return Instant.EPOCH;
        }

        final SortedMap<Long, Boolean> conversationMetadata = findAllConversations();
        conversationsPendingDeletion.forEach(conversationMetadata::remove);
        conversationsPendingDeletion.clear();

        if (conversationMetadata.isEmpty()) {
            return Instant.EPOCH;
        }

        Log.debug("... started to index conversations to rebuild the OpenSearch index.");
        return indexConversations(conversationMetadata, true);
    }

    private SortedMap<Long, Boolean> findAllConversations()
    {
        SortedMap<Long, Boolean> externalMetaData = new TreeMap<>();
        Connection con = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(ALL_CONVERSATIONS);
            rs = pstmt.executeQuery();
            while (rs.next()) {
                externalMetaData.put(rs.getLong(1), rs.getInt(2) == 1);
            }
        } catch (SQLException sqle) {
            Log.error("An exception occurred while trying to fetch all conversations from the database to rebuild the OpenSearch index.", sqle);
        } finally {
            DbConnectionManager.closeConnection(rs, pstmt, con);
        }
        return externalMetaData;
    }

    private List<Long> findModifiedConversations(final Instant lastModified)
    {
        final List<Long> results = new ArrayList<>();
        Connection con = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = con.prepareStatement(NEW_CONVERSATIONS);
            pstmt.setLong(1, lastModified.toEpochMilli());
            rs = pstmt.executeQuery();
            while (rs.next()) {
                results.add(rs.getLong(1));
            }
        } catch (SQLException sqle) {
            Log.error("An exception occurred while trying to fetch new/updated conversations from the database to update the OpenSearch index.", sqle);
        } finally {
            DbConnectionManager.closeConnection(rs, pstmt, con);
        }
        return results;
    }

    private SortedMap<Long, Boolean> extractMetaData(final List<Long> conversationIDs)
    {
        final SortedMap<Long, Boolean> results = new TreeMap<>();
        Connection con = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        for (long conversationID : conversationIDs) {
            try {
                con = DbConnectionManager.getConnection();
                pstmt = con.prepareStatement(CONVERSATION_METADATA);
                pstmt.setLong(1, conversationID);
                rs = pstmt.executeQuery();
                while (rs.next()) {
                    results.put(conversationID, rs.getInt(1) == 1);
                }
            } catch (SQLException sqle) {
                Log.error("An exception occurred while trying to load metadata for conversations to be updated in the OpenSearch index.", sqle);
            } finally {
                DbConnectionManager.closeConnection(rs, pstmt, con);
            }
        }
        return results;
    }

    private Instant indexConversations(final SortedMap<Long, Boolean> conversations, final boolean indexRebuild) throws IOException
    {
        if (conversations.isEmpty()) {
            return Instant.EPOCH;
        }

        final List<Long> conversationIDs = new ArrayList<>(conversations.keySet());
        final OpenSearchClient client = getClient();
        final List<BulkOperation> batch = new ArrayList<>();
        Instant newestDate = Instant.EPOCH;
        int indexedConversations = 0;

        final int OP_SIZE = 250;
        int n = Math.max(1, (conversationIDs.size() - 1) / OP_SIZE);
        for (int i = 0; i < n; i++) {
            StringBuilder inSQL = new StringBuilder(" (");
            int start = i * OP_SIZE;
            int end = Math.min(start + OP_SIZE, conversationIDs.size());
            inSQL.append(conversationIDs.get(start));
            for (int j = start + 1; j < end; j++) {
                inSQL.append(", ").append(conversationIDs.get(j));
            }
            inSQL.append(")");

            Connection con = null;
            PreparedStatement pstmt = null;
            ResultSet rs = null;
            try {
                con = DbConnectionManager.getConnection();
                pstmt = con.prepareStatement(CONVERSATION_MESSAGES.replace("?", inSQL.toString()));
                rs = pstmt.executeQuery();
                long conversationID = -1;
                Instant date = Instant.EPOCH;
                Set<JID> jids = null;
                StringBuilder text = null;
                while (rs.next()) {
                    long id = rs.getLong(1);
                    if (id != conversationID) {
                        if (conversationID != -1) {
                            batch.add(buildConversationBulk(conversationID, conversations.get(conversationID), date, jids, text.toString()));
                            flushBatchIfNeeded(client, batch);
                        }
                        conversationID = id;
                        date = Instant.ofEpochMilli(rs.getLong(2));
                        jids = new TreeSet<>();
                        jids.add(new JID(rs.getString(3)).asBareJID());
                        jids.add(new JID(rs.getString(4)).asBareJID());
                        text = new StringBuilder();
                    }
                    final Instant msgDate = Instant.ofEpochMilli(rs.getLong(2));
                    if (msgDate.isBefore(date)) {
                        date = msgDate;
                    }
                    if (msgDate.isAfter(newestDate)) {
                        newestDate = msgDate;
                    }
                    text.append(DbConnectionManager.getLargeTextField(rs, 5)).append("\n");
                }
                if (conversationID != -1) {
                    batch.add(buildConversationBulk(conversationID, conversations.get(conversationID), date, jids, text.toString()));
                    flushBatchIfNeeded(client, batch);
                }
                if (indexRebuild) {
                    indexedConversations++;
                    if (rebuildFuture != null) {
                        rebuildFuture.setPercentageDone((indexedConversations * 100) / conversationIDs.size());
                    }
                }
            } catch (SQLException sqle) {
                Log.error("An exception occurred while indexing conversations.", sqle);
            } finally {
                DbConnectionManager.closeConnection(rs, pstmt, con);
            }
        }
        OpenSearchClientHolder.bulkIndex(client, getIndexName(), batch);
        return newestDate;
    }

    private void flushBatchIfNeeded(final OpenSearchClient client, final List<BulkOperation> batch) throws IOException {
        if (batch.size() >= 250) {
            OpenSearchClientHolder.bulkIndex(client, getIndexName(), batch);
            batch.clear();
        }
    }

    private BulkOperation buildConversationBulk(
        final long conversationID,
        final boolean external,
        final Instant date,
        final Set<JID> jids,
        final String text)
    {
        final Map<String, Object> document = new HashMap<>();
        document.put("conversationID", conversationID);
        document.put("external", String.valueOf(external));
        document.put("date", date.toEpochMilli());
        document.put("jid", jids.stream().map(JID::toBareJID).toList());
        document.put("text", text);
        return BulkOperation.of(op -> op.index(idx -> idx.index(getIndexName()).id(String.valueOf(conversationID)).document(document)));
    }

    private static Map<String, Object> conversationMappings() {
        final Map<String, Object> mappings = new HashMap<>();
        mappings.put("conversationID", "{\"type\":\"long\"}");
        mappings.put("external", "{\"type\":\"keyword\"}");
        mappings.put("date", "{\"type\":\"long\"}");
        mappings.put("jid", "{\"type\":\"keyword\"}");
        mappings.put("text", "{\"type\":\"text\"}");
        return mappings;
    }
}
