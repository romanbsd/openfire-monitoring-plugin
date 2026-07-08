/*
 * Copyright (C) 2008 Jive Software, 2026 Ignite Realtime Foundation. All rights reserved.
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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import org.jivesoftware.openfire.index.OpenSearchClientHolder;
import org.jivesoftware.openfire.index.OpenSearchQueryHelper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.jivesoftware.database.CachedPreparedStatement;
import org.jivesoftware.database.DbConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;

/**
 * Searches archived conversations. If conversation archiving is not enabled,
 * this class does nothing. Searches may or may not include keyword searching. When
 * keywords are used, the search is executed against the OpenSearch index. When keywords
 * are not used, the search is database driven (e.g., "get all conversations between
 * two users over the past year").
 *
 * @see ArchiveIndexer
 * @author Matt Tucker
 */
public class ArchiveSearcher {

    private static final Logger Log = LoggerFactory.getLogger(ArchiveSearch.class);

    public void start() {

    }

    public void stop() {
    }

    /**
     * Searches the archive using the specified search. The {@link ArchiveSearch} class
     * is used to encapsulate all information about a search.
     *
     * @param search the search.
     * @return a Collection of conversations that match the search query.
     */
    public Collection<Conversation> search(ArchiveSearch search) {
        // If the search has a query string it will be driven by OpenSearch. Otherwise
        if (search.getQueryString() != null) {
            return openSearchSearch(search);
        }
        else {
            return databaseSearch(search);
        }
    }

    /**
     * Searches the OpenSearch index for all archived conversations using the specified search.
     *
     * @param search the search.
     * @return the collection of conversations that match the search.
     */
    private Collection<Conversation> openSearchSearch(ArchiveSearch search) {
        Log.debug("Executing new OpenSearch search for query string {}", search.getQueryString());
        try {
            final OpenSearchClient client = OpenSearchClientHolder.getClient();
            final String indexName = OpenSearchClientHolder.indexName("conversations");

            final List<Query> filters = new ArrayList<>();
            filters.add(OpenSearchQueryHelper.matchText("text", search.getQueryString()));

            if (search.getDateRangeMin() != null || search.getDateRangeMax() != null) {
                final Long min = search.getDateRangeMin() != null ? search.getDateRangeMin().getTime() : Long.MIN_VALUE;
                final Long max = search.getDateRangeMax() != null ? search.getDateRangeMax().getTime() : Long.MAX_VALUE;
                filters.add(OpenSearchQueryHelper.rangeQuery("date", min, max));
            }

            Collection<JID> participants = search.getParticipants();
            if (search.getParticipants().size() < 2 && search.isExternalWildcardMode()) {
                filters.add(OpenSearchQueryHelper.termQuery("external", "true"));
            }

            if (!participants.isEmpty()) {
                if (participants.size() == 1) {
                    filters.add(OpenSearchQueryHelper.termQuery("jid", participants.iterator().next().toBareJID()));
                } else {
                    Iterator<JID> iter = participants.iterator();
                    filters.add(OpenSearchQueryHelper.termQuery("jid", iter.next().toBareJID()));
                    filters.add(OpenSearchQueryHelper.termQuery("jid", iter.next().toBareJID()));
                }
            }

            final Query query = OpenSearchQueryHelper.boolMust(filters);
            final int startIndex = search.getStartIndex();
            final int endIndex = startIndex + search.getNumResults() - 1;
            if (((endIndex - startIndex) + 1) <= 0) {
                return Collections.emptyList();
            }

            final boolean sortDescending = search.getSortField() == ArchiveSearch.SortField.date
                && search.getSortOrder() == ArchiveSearch.SortOrder.descending;
            final List<Long> conversationIDs = OpenSearchQueryHelper.searchConversationIds(
                client,
                indexName,
                query,
                search.getSortField() == ArchiveSearch.SortField.date ? "date" : "_score",
                sortDescending,
                startIndex,
                (endIndex - startIndex) + 1
            );
            return new OpenSearchQueryResults(conversationIDs);
        } catch (Exception e) {
            Log.error(e.getMessage(), e);
            return Collections.emptySet();
        }
    }

    /**
     * Searches the database for all archived conversations using the specified search.
     *
     * @param search the search.
     * @return the collection of conversations that match the search.
     */
    private Collection<Conversation> databaseSearch(ArchiveSearch search) {
        CachedPreparedStatement cachedPstmt = new CachedPreparedStatement();

        // Build the SQL
        StringBuilder query = new StringBuilder(160);
        query.append("SELECT DISTINCT ofConversation.conversationID");

        Collection<JID> participants = search.getParticipants();
        boolean filterParticipants = !participants.isEmpty();
        boolean filterDate = search.getDateRangeMin() != null || search.getDateRangeMax() != null;
        boolean filterTimestamp = search.getIncludeTimestamp() != null;
        boolean filterRoom = search.getRoom() != null;

        // SELECT -- need to add value that we sort on. We always sort on date since that's
        // the only valid current option for non-keyword searches.
        query.append(", ofConversation.startDate");

        // FROM -- values (in addition to jiveThread)
        query.append(" FROM ofConversation");
        if (filterParticipants) {
            for (int i=0; i < participants.size(); i++) {
                query.append(", ofConParticipant participant").append(i);
            }
        }

        // WHERE BLOCK
        boolean whereSet = false;
        // See if we need to match against external conversations.
        if (search.isExternalWildcardMode() && search.getParticipants().size() != 2) {
            query.append(" WHERE isExternal=?");
            cachedPstmt.addInt(1);
            whereSet = true;
        }
        // Participants
        if (filterParticipants) {
            Iterator<JID> iter = participants.iterator();
            for (int i=0; i < participants.size(); i++) {
                if (!whereSet) {
                    query.append(" WHERE");
                    whereSet = true;
                }
                else {
                    query.append(" AND");
                }
                query.append(" ofConversation.conversationID=participant").append(i).append(".conversationID");
                query.append(" AND ");
                query.append("participant").append(i).append(".bareJID=?");
                String partJID = iter.next().toString();
                cachedPstmt.addString(partJID);
            }
        }

        // Creation date range
        if (filterDate) {
            if (search.getDateRangeMin() != null) {
                if (!whereSet) {
                    query.append(" WHERE");
                    whereSet = true;
                }
                else {
                    query.append(" AND");
                }
                query.append(" ofConversation.startDate >= ?");
                cachedPstmt.addLong(search.getDateRangeMin().getTime());
            }
            if (search.getDateRangeMax() != null) {
                if (!whereSet) {
                    query.append(" WHERE");
                    whereSet = true;
                }
                else {
                    query.append(" AND");
                }
                query.append(" ofConversation.startDate <= ?");
                cachedPstmt.addLong(search.getDateRangeMax().getTime());
            }
        }

        // Check if conversations have to happen at a given point in time
        if (filterTimestamp) {
            if (!whereSet) {
                query.append(" WHERE");
                whereSet = true;
            }
            else {
                query.append(" AND");
            }
            query.append(" ofConversation.startDate <= ?");
            cachedPstmt.addLong(search.getIncludeTimestamp().getTime());

            query.append(" AND");
            query.append(" ofConversation.lastActivity >= ?");
            cachedPstmt.addLong(search.getIncludeTimestamp().getTime());
        }

        // Filter by room
        if (filterRoom) {
            if (!whereSet) {
                query.append(" WHERE");
            }
            else {
                query.append(" AND");
            }
            query.append(" ofConversation.room = ?");
            cachedPstmt.addString(search.getRoom().toString());
        }

        // ORDER BY
        query.append(" ORDER BY ofConversation.startDate");
        if (search.getSortOrder() == ArchiveSearch.SortOrder.descending) {
            query.append(" DESC");
        }
        else {
            query.append(" ASC");
        }

        int startIndex = search.getStartIndex();
        int numResults = search.getNumResults();
        if (numResults != ArchiveSearch.NULL_INT) {
            // MySQL/MariaDB optimization: use the LIMIT command to tell the database how many
            // rows we need returned. The syntax is LIMIT [offset],[rows]
            if (DbConnectionManager.getDatabaseType() == DbConnectionManager.DatabaseType.mysql
                || DbConnectionManager.getDatabaseType() == DbConnectionManager.DatabaseType.mariadb) {
                query.append(" LIMIT ").append(startIndex).append(",").append(numResults);
            }
            // PostgreSQL/CockroachDB optimization: use the LIMIT command to tell the database how many
            // rows we need returned. The syntax is LIMIT [rows] OFFSET [offset]
            else if (DbConnectionManager.getDatabaseType() == DbConnectionManager.DatabaseType.postgresql
                || DbConnectionManager.getDatabaseType() == DbConnectionManager.DatabaseType.cockroachdb) {
                query.append(" LIMIT ").append(numResults).append(" OFFSET ").append(startIndex);
            }
            // Firebird optimization: use the OFFSET/FETCH syntax.
            else if (DbConnectionManager.getDatabaseType() == DbConnectionManager.DatabaseType.firebird) {
                query.append(" OFFSET ").append(startIndex)
                    .append(" ROWS FETCH NEXT ").append(numResults)
                    .append(" ROWS ONLY ");
            }
            // SQL Server optimization: use the OFFSET command to tell the database how many
            // rows we need returned. The syntax is OFFSET [offset] ROWS FETCH NEXT [rows] ROWS ONLY
            else if (DbConnectionManager.getDatabaseType() == DbConnectionManager.DatabaseType.sqlserver) {
                query.append(" OFFSET ").append(startIndex)
                .append(" ROWS FETCH NEXT ").append(numResults)
                .append(" ROWS ONLY ");
            }
        }

        // Set the database query string.
        cachedPstmt.setSQL(query.toString());

        List<Long> conversationIDs = new ArrayList<>();

        // Get all matching conversations from the database.
        Connection con = null;
        PreparedStatement pstmt = null;
        try {
            con = DbConnectionManager.getConnection();
            pstmt = DbConnectionManager.createScrollablePreparedStatement(con, cachedPstmt.getSQL());
            cachedPstmt.setParams(pstmt);
            // Set the maximum number of rows to end at the end of this block.
            // A MySQL/MariaDB/CockroachDB/PostgreSQL/Firebird optimization using the result-set pagination syntax is part of the SQL.
            // Therefore, we can skip this call on MySQL/MariaDB/CockroachDB/PostgreSQL/Firebird.
            if (DbConnectionManager.getDatabaseType() != DbConnectionManager.DatabaseType.mysql
                && DbConnectionManager.getDatabaseType() != DbConnectionManager.DatabaseType.mariadb
                && DbConnectionManager.getDatabaseType() != DbConnectionManager.DatabaseType.postgresql
                && DbConnectionManager.getDatabaseType() != DbConnectionManager.DatabaseType.firebird
                && DbConnectionManager.getDatabaseType() != DbConnectionManager.DatabaseType.cockroachdb)
            {
                DbConnectionManager.setMaxRows(pstmt, startIndex+numResults);
            }
            ResultSet rs = pstmt.executeQuery();
            // Position the cursor right before the first row that we're insterested in.
            // A MySQL/MariaDB/CockroachDB/PostgreSQL/Firebird optimization using pagination syntax is part of the SQL.
            // Therefore, we can skip this call on MySQL/MariaDB/CockroachDB/PostgreSQL/Firebird.
            if (DbConnectionManager.getDatabaseType() != DbConnectionManager.DatabaseType.mysql
                && DbConnectionManager.getDatabaseType() != DbConnectionManager.DatabaseType.mariadb
                && DbConnectionManager.getDatabaseType() != DbConnectionManager.DatabaseType.postgresql
                && DbConnectionManager.getDatabaseType() != DbConnectionManager.DatabaseType.firebird
                && DbConnectionManager.getDatabaseType() != DbConnectionManager.DatabaseType.cockroachdb)
            {
                DbConnectionManager.scrollResultSet(rs, startIndex);
            }
            // Keep reading results until the result set is exhausted or
            // we come to the end of the block.
            int count = 0;
            while (rs.next() && count < numResults) {
                conversationIDs.add(rs.getLong(1));
                count++;
            }
            rs.close();
        }
        catch (SQLException sqle) {
            Log.error(sqle.getMessage(), sqle);
        }
        finally {
            DbConnectionManager.closeConnection(pstmt, con);
        }
        return new DatabaseQueryResults(conversationIDs);
    }

    /**
     * Returns Hits from a database search against archived conversations as a Collection
     * of Conversation objects.
     */
    private static class DatabaseQueryResults extends AbstractCollection<Conversation> {

        private final List<Long> conversationIDs;

        /**
         * Constructs a new query results object.
         *
         * @param conversationIDs the list of conversation IDs.
         */
        private DatabaseQueryResults(List<Long> conversationIDs) {
            this.conversationIDs = conversationIDs;
        }

        @Override
        public Iterator<Conversation> iterator() {
            final Iterator<Long> convIterator = conversationIDs.iterator();
            return new Iterator<>() {

                private Conversation nextElement = null;

                @Override
                public boolean hasNext() {
                    if (nextElement == null) {
                        nextElement = getNextElement();
                        return nextElement != null;
                    }
                    return true;
                }

                @Override
                public Conversation next() {
                    Conversation element;
                    if (nextElement != null) {
                        element = nextElement;
                        nextElement = null;
                    } else {
                        element = getNextElement();
                        if (element == null) {
                            throw new NoSuchElementException();
                        }
                    }
                    return element;
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException();
                }

                private Conversation getNextElement() {
                    if (!convIterator.hasNext()) {
                        return null;
                    }
                    while (convIterator.hasNext()) {
                        try {
                            long conversationID = convIterator.next();
                            return ConversationDAO.loadConversation(conversationID);
                        } catch (Exception e) {
                            Log.error(e.getMessage(), e);
                        }
                    }
                    return null;
                }
            };
        }

        @Override
        public int size() {
            return conversationIDs.size();
        }
    }

    /**
     * Returns conversation IDs from an OpenSearch query as a Collection of Conversation objects.
     */
    private static class OpenSearchQueryResults extends AbstractCollection<Conversation> {

        private final List<Long> conversationIDs;

        private OpenSearchQueryResults(List<Long> conversationIDs) {
            this.conversationIDs = conversationIDs;
        }

        @Override
        public Iterator<Conversation> iterator() {
            final Iterator<Long> convIterator = conversationIDs.iterator();
            return new Iterator<>() {

                private Conversation nextElement = null;

                @Override
                public boolean hasNext() {
                    if (nextElement == null) {
                        nextElement = getNextElement();
                        return nextElement != null;
                    }
                    return true;
                }

                @Override
                public Conversation next() {
                    Conversation element;
                    if (nextElement != null) {
                        element = nextElement;
                        nextElement = null;
                    } else {
                        element = getNextElement();
                        if (element == null) {
                            throw new NoSuchElementException();
                        }
                    }
                    return element;
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException();
                }

                private Conversation getNextElement() {
                    if (!convIterator.hasNext()) {
                        return null;
                    }
                    while (convIterator.hasNext()) {
                        try {
                            return ConversationDAO.loadConversation(convIterator.next());
                        } catch (Exception e) {
                            Log.error(e.getMessage(), e);
                        }
                    }
                    return null;
                }
            };
        }

        @Override
        public int size() {
            return conversationIDs.size();
        }
    }
}
