package com.reucon.openfire.plugin.archive.impl;

import com.reucon.openfire.plugin.archive.model.ArchivedMessage;
import com.reucon.openfire.plugin.archive.xep0313.IQQueryHandler;
import org.jivesoftware.openfire.index.OpenSearchClientHolder;
import org.jivesoftware.openfire.index.OpenSearchQueryHelper;
import org.jivesoftware.openfire.muc.MUCRoom;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

public class PaginatedMucMessageOpenSearchQuery extends AbstractPaginatedMamMucQuery
{
    private static final Logger Log = LoggerFactory.getLogger(PaginatedMucMessageOpenSearchQuery.class);

    public PaginatedMucMessageOpenSearchQuery(@Nullable final Date startDate, @Nullable final Date endDate, @Nonnull final MUCRoom owner, @Nullable final JID with, @Nonnull final String query)
    {
        super(startDate, endDate, owner, with, query);
    }

    protected OpenSearchClient getClient() {
        return OpenSearchClientHolder.getClient();
    }

    protected String getIndexName() {
        return OpenSearchClientHolder.indexName("messages");
    }

    @Override
    public List<ArchivedMessage> getPage(final Long after, final Long before, final int maxResults, final boolean isPagingBackwards) throws DataRetrievalException {
        final List<ArchivedMessage> result = new ArrayList<>();
        try {
            final List<Long> messageIds = OpenSearchQueryHelper.searchMessageIds(
                getClient(),
                getIndexName(),
                getQueryForPage(after, before),
                "sentDate",
                isPagingBackwards,
                maxResults
            );
            for (final long messageID : messageIds) {
                final ArchivedMessage archivedMessage = MucMamPersistenceManager.getArchivedMessage(messageID, room);
                if (archivedMessage != null) {
                    result.add(archivedMessage);
                }
            }
            if (isPagingBackwards) {
                Collections.reverse(result);
            }
        } catch (Exception e) {
            Log.warn("An exception occurred while trying to query OpenSearch to get messages from archive of room {}.", room.getJID(), e);
            if (!IQQueryHandler.IGNORE_RETRIEVAL_EXCEPTIONS.getValue()) {
                throw new DataRetrievalException(e);
            }
        }
        return result;
    }

    @Override
    public int getTotalCount() {
        try {
            return OpenSearchQueryHelper.count(getClient(), getIndexName(), getQueryForAllResults());
        } catch (Exception e) {
            Log.warn("An exception occurred while trying to get a count of messages that match a query for message from archive of room {}.", room.getJID(), e);
            return -1;
        }
    }

    protected Query getQueryForAllResults() {
        final List<Query> filters = new ArrayList<>();
        filters.add(OpenSearchQueryHelper.matchText("body", query));
        filters.add(OpenSearchQueryHelper.termQuery("room", room.getJID().toBareJID()));
        filters.add(OpenSearchQueryHelper.termQuery("isPrivateMessage", "false"));
        filters.add(OpenSearchQueryHelper.rangeQuery("sentDate", startDate.getTime(), endDate.getTime()));
        if (with != null) {
            filters.add(OpenSearchQueryHelper.termQuery("senderBare", with.toBareJID()));
            if (with.getResource() != null) {
                filters.add(OpenSearchQueryHelper.termQuery("senderResource", with.getResource()));
            }
        }
        return OpenSearchQueryHelper.boolMust(filters);
    }

    protected Query getQueryForPage(final Long after, final Long before) {
        if (after == null && before == null) {
            return getQueryForAllResults();
        }
        final List<Query> filters = new ArrayList<>();
        filters.add(getQueryForAllResults());
        filters.add(OpenSearchQueryHelper.rangeQuery(
            "messageIDRange",
            after != null ? Math.addExact(after, 1) : Long.MIN_VALUE,
            before != null ? Math.addExact(before, -1) : Long.MAX_VALUE
        ));
        return OpenSearchQueryHelper.boolMust(filters);
    }

    @Override
    public String toString() {
        return "PaginatedMucMessageOpenSearchQuery{" +
            "startDate=" + startDate +
            ", endDate=" + endDate +
            ", room=" + room.getJID() +
            ", with=" + with +
            ", query='" + query + '\'' +
            '}';
    }
}
