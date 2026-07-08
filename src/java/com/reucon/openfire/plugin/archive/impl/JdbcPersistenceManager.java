package com.reucon.openfire.plugin.archive.impl;

import com.reucon.openfire.plugin.archive.PersistenceManager;
import com.reucon.openfire.plugin.archive.model.ArchivedMessage;
import com.reucon.openfire.plugin.archive.model.ArchivedMessage.Direction;
import com.reucon.openfire.plugin.archive.xep0059.XmppResultSet;
import org.jivesoftware.database.DbConnectionManager;
import org.jivesoftware.openfire.archive.ConversationManager;
import org.jivesoftware.openfire.index.OpenSearchIndexer;
import org.jivesoftware.util.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;

/**
 * Manages database interactions to work with 'personal archives' for messages.
 */
public class JdbcPersistenceManager implements PersistenceManager {
    private static final Logger Log = LoggerFactory.getLogger( JdbcPersistenceManager.class );
    public static final int DEFAULT_MAX = 1000;

    public Date getAuditedStartDate(Date startDate) {
        Duration maxRetrievable = ConversationManager.MAX_RETRIEVABLE.getValue();
        Date result = startDate;
        if (maxRetrievable.toDays() > 0) {
            Date now = new Date();
            Date maxRetrievableDate = new Date(now.getTime() - maxRetrievable.toMillis());
            if (startDate == null || startDate.before(maxRetrievableDate)) {
                result = maxRetrievableDate;
            }
        }
        return result;
    }

    @Override
    public Collection<ArchivedMessage> findMessages(Date startDate, Date endDate, JID owner, JID with, String query, XmppResultSet xmppResultSet, boolean useStableID) throws DataRetrievalException, NotFoundException
    {
        Log.debug( "Finding messages of owner '{}' with start date '{}', end date '{}' with '{}' and resultset '{}', useStableId '{}'.", owner, startDate, endDate, with, xmppResultSet, useStableID );

        if (startDate == null) {
            Log.debug( "Request for message archive of user '{}' did not specify a start date. Using EPOCH.", owner );
            startDate = new Date(0L);
        }
        if (endDate == null) {
            Log.debug( "Request for message archive of user '{}' did not specify an end date. Using the current timestamp.", owner );
            endDate = new Date();
        }

        // Limit history, if so configured.
        startDate = getAuditedStartDate(startDate);

        final Long after;
        final Long before;
        if (xmppResultSet.getAfter() != null) {
            if ( useStableID ) {
                try {
                    after = ConversationManager.getMessageIdForStableId( owner, xmppResultSet.getAfter() );
                } catch ( IllegalArgumentException e ) {
                    throw new NotFoundException("The reference '"+xmppResultSet.getAfter()+"' used in the 'after' RSM element is not recognized.");
                }
            } else {
                after = Long.parseLong( xmppResultSet.getAfter() );
            }
        } else {
            after = null;
        }
        if (xmppResultSet.getBefore() != null) {
            if ( useStableID ) {
                try {
                    before = ConversationManager.getMessageIdForStableId(owner, xmppResultSet.getBefore());
                } catch ( IllegalArgumentException e ) {
                    throw new NotFoundException("The reference '"+xmppResultSet.getBefore()+"' used in the 'before' RSM element is not recognized.");
                }
            } else {
                before = Long.parseLong( xmppResultSet.getBefore() );
            }
        } else {
            before = null;
        }

        final int maxResults = xmppResultSet.getMax() != null ? xmppResultSet.getMax() : DEFAULT_MAX;
        final boolean isPagingBackwards = xmppResultSet.isPagingBackwards();

        List<ArchivedMessage> msgs;
        int totalCount;
        if ( query != null && !query.isEmpty() ) {
            if (!OpenSearchIndexer.isSearchEnabled()) {
                throw new DataRetrievalException("Unable to process a search request that contains a text-based query, as the full-text index functionality has been disabled by configuration.");
            }
            final PaginatedMessageOpenSearchQuery paginatedMessageOpenSearchQuery = new PaginatedMessageOpenSearchQuery( startDate, endDate, owner, with, query );
            Log.debug("Request for message archive of user '{}' resulted in the following query data: {}", owner, paginatedMessageOpenSearchQuery);
            totalCount = paginatedMessageOpenSearchQuery.getTotalCount();
            if ( totalCount == 0 ) {
                msgs = Collections.emptyList();
            } else {
                msgs = paginatedMessageOpenSearchQuery.getPage(after, before, maxResults, isPagingBackwards);
            }
        } else {
            final PaginatedMessageDatabaseQuery paginatedMessageDatabaseQuery = new PaginatedMessageDatabaseQuery(startDate, endDate, owner, with );
            Log.debug("Request for message archive of user '{}' resulted in the following query data: {}", owner, paginatedMessageDatabaseQuery);
            totalCount = paginatedMessageDatabaseQuery.getTotalCount();
            if ( totalCount == 0 ) {
                msgs = Collections.emptyList();
            } else {
                msgs = paginatedMessageDatabaseQuery.getPage(after, before, maxResults, isPagingBackwards);
            }
        }

        Log.debug( "Request for message archive of owner '{}' found a total of {} applicable messages. Of these, {} were actually retrieved from the database.", owner, totalCount, msgs.size() );

        xmppResultSet.setCount(totalCount);

        if ( !msgs.isEmpty() ) {
            final ArchivedMessage firstMessage = msgs.get(0);
            final ArchivedMessage lastMessage = msgs.get(msgs.size()-1);
            final String first;
            final String last;
            if ( useStableID ) {
                final String firstSid = firstMessage.getStableId(owner);
                if ( firstSid != null && !firstSid.isEmpty() ) {
                    first = firstSid;
                } else {
                    first = firstMessage.getId().toString();
                }
                final String lastSid = lastMessage.getStableId(owner);
                if ( lastSid != null && !lastSid.isEmpty()) {
                    last = lastSid;
                } else {
                    last = lastMessage.getId().toString();
                }
            } else {
                first = String.valueOf(firstMessage.getId() );
                last = String.valueOf(lastMessage.getId() );
            }
            xmppResultSet.setFirst(first);
            xmppResultSet.setLast(last);

            final Long afterForNextPage = isPagingBackwards ? null : lastMessage.getId();
            final Long beforeForNextPage = isPagingBackwards ? firstMessage.getId() : null;
            final List<ArchivedMessage> nextPage;
            if ( query != null && !query.isEmpty() )
            {
                if (!OpenSearchIndexer.isSearchEnabled()) {
                    throw new DataRetrievalException("Unable to process a search request that contains a text-based query, as the full-text index functionality has been disabled by configuration.");
                }
                final PaginatedMessageOpenSearchQuery paginatedMessageOpenSearchQuery = new PaginatedMessageOpenSearchQuery(startDate, endDate, owner, with, query);
                nextPage = paginatedMessageOpenSearchQuery.getPage(afterForNextPage, beforeForNextPage, 1, isPagingBackwards);
            }
            else
            {
                final PaginatedMessageDatabaseQuery paginatedMessageDatabaseQuery = new PaginatedMessageDatabaseQuery(startDate, endDate, owner, with );
                nextPage = paginatedMessageDatabaseQuery.getPage(afterForNextPage, beforeForNextPage, 1, isPagingBackwards);
            }
            Log.debug("Found results for 'next page': {} (based on after: {} before: {} isPagingBackwards: {})", !nextPage.isEmpty(), afterForNextPage, beforeForNextPage, isPagingBackwards);
            xmppResultSet.setComplete(nextPage.isEmpty());
        } else {
            xmppResultSet.setComplete(true);
        }
        return msgs;
    }

    /**
     * Retrieve a specific message from the database.
     *
     * @param messageId The database ID of the message.
     * @param owner The owner of the archive in which the message was stored (cannot be null).
     * @return The message, or null if no message was found.
     */
    public static ArchivedMessage getArchivedMessage( long messageId, JID owner )
    {
        Connection connection = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            connection = DbConnectionManager.getConnection();
            final String query = "SELECT ofMessageArchive.fromJID, ofMessageArchive.fromJIDResource, ofMessageArchive.toJID, ofMessageArchive.toJIDResource, ofMessageArchive.sentDate, ofMessageArchive.body, ofMessageArchive.stanza, ofMessageArchive.messageID "
                + "FROM ofMessageArchive "
                + "INNER JOIN ofConParticipant ON ofMessageArchive.conversationID = ofConParticipant.conversationID "
                + "WHERE (ofMessageArchive.stanza IS NOT NULL OR ofMessageArchive.body IS NOT NULL) "
                + "AND ofMessageArchive.messageID = ? AND ofConParticipant.bareJID = ?";

            pstmt = connection.prepareStatement( query );
            pstmt.setLong( 1, messageId );
            pstmt.setString( 2, owner.toBareJID() );
            rs = pstmt.executeQuery();
            if (!rs.next()) {
                return null;
            }

            String fromJID = rs.getString(1);
            String fromJIDResource = rs.getString(2);
            String toJID = rs.getString(3);
            String toJIDResource = rs.getString(4);
            Date sentDate = new Date(rs.getLong(5));
            String body = rs.getString(6);
            String stanza = rs.getString(7);
            if ( stanza != null && stanza.isEmpty()) {
                stanza = null;
            }
            long id = rs.getLong(8);

            if ( rs.next() ) {
                Log.warn("Database contains more than one message with ID {} from the archive of {}.", messageId, owner);
            }

            return asArchivedMessage(owner, fromJID, fromJIDResource, toJID, toJIDResource, sentDate, body, stanza, id);
        } catch (SQLException ex) {
            Log.warn("SQL failure while trying to get message with ID {} from the archive of {}.", messageId, owner, ex);
            return null;
        } finally {
            DbConnectionManager.closeConnection(rs, pstmt, connection);
        }
    }

    static ArchivedMessage extractMessage(final JID owner, ResultSet rs) throws SQLException {
        Date time = new Date(rs.getLong("sentDate"));
        String body = rs.getString("body");
        String stanza = rs.getString("stanza");
        String fromJid = rs.getString("fromJID");
        String fromJIDResource = rs.getString("fromJIDResource");
        String toJid = rs.getString("toJID");
        String toJIDResource = rs.getString("toJIDResource");
        Long id = rs.getLong("messageID");

        return asArchivedMessage(owner, fromJid, fromJIDResource, toJid, toJIDResource, time, body, stanza, id);
    }

    static protected ArchivedMessage asArchivedMessage(JID owner, String fromJID, String fromJIDResource, String toJID, String toJIDResource, Date sentDate, String body, String stanza, Long id) {
        final JID from = new JID(fromJID + ( fromJIDResource == null || fromJIDResource.isEmpty() ? "" : "/" + fromJIDResource ));
        final JID to = new JID(toJID + ( toJIDResource == null || toJIDResource.isEmpty() ? "" : "/" + toJIDResource ));

        final ArchivedMessage.Direction direction = ArchivedMessage.Direction.getDirection(owner, to);
        final JID with = direction == Direction.from ? from : to;
        return new ArchivedMessage(id, sentDate, direction, with, body, stanza);
    }
}
