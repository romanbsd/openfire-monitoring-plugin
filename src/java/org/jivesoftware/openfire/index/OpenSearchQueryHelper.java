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
package org.jivesoftware.openfire.index;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.CountRequest;
import org.opensearch.client.opensearch.core.CountResponse;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Shared OpenSearch query execution helpers.
 */
public final class OpenSearchQueryHelper
{
    private static final Logger Log = LoggerFactory.getLogger(OpenSearchQueryHelper.class);

    private OpenSearchQueryHelper() {
    }

    public static Query matchText(final String field, final String text) {
        return Query.of(q -> q.match(m -> m.field(field).query(FieldValue.of(text))));
    }

    public static Query matchPhrase(final String field, final String text) {
        return Query.of(q -> q.matchPhrase(m -> m.field(field).query(text)));
    }

    public static Query termQuery(final String field, final String value) {
        return termQuery(field, FieldValue.of(value));
    }

    public static Query termQuery(final String field, final long value) {
        return termQuery(field, FieldValue.of(value));
    }

    private static Query termQuery(final String field, final FieldValue value) {
        return Query.of(q -> q.term(t -> t.field(field).value(value)));
    }

    public static Query termsQuery(final String field, final Collection<String> values) {
        return termsQuery(field, values.stream().map(FieldValue::of).toList());
    }

    public static Query termsQueryLong(final String field, final Collection<Long> values) {
        return termsQuery(field, values.stream().map(FieldValue::of).toList());
    }

    private static Query termsQuery(final String field, final List<FieldValue> fieldValues) {
        return Query.of(q -> q.terms(t -> t.field(field).terms(tv -> tv.value(fieldValues))));
    }

    public static Query rangeQuery(final String field, final Long min, final Long max) {
        return Query.of(q -> q.range(r -> {
            r.field(field);
            if (min != null) {
                r.gte(org.opensearch.client.json.JsonData.of(min));
            }
            if (max != null) {
                r.lte(org.opensearch.client.json.JsonData.of(max));
            }
            return r;
        }));
    }

    public static Query boolMust(final List<Query> queries) {
        return Query.of(q -> q.bool(BoolQuery.of(b -> b.must(queries))));
    }

    public static Query boolShouldMinOne(final List<Query> should) {
        return Query.of(q -> q.bool(BoolQuery.of(b -> b.should(should).minimumShouldMatch("1"))));
    }

    /**
     * Matches documents strictly before {@code (timestamp, messageId)} in a descending
     * {@code (timeField, messageID)} sort order.
     */
    public static Query olderThanSortKey(final String timeField, final long timestamp, final long messageId) {
        final List<Query> should = new ArrayList<>();
        should.add(rangeQuery(timeField, null, timestamp - 1));
        should.add(boolMust(List.of(
            termQuery(timeField, timestamp),
            Query.of(q -> q.range(r -> r.field("messageID").lt(org.opensearch.client.json.JsonData.of(messageId))))
        )));
        return boolShouldMinOne(should);
    }

    /**
     * Matches documents strictly after {@code (timestamp, messageId)} in a descending
     * {@code (timeField, messageID)} sort order.
     */
    public static Query newerThanSortKey(final String timeField, final long timestamp, final long messageId) {
        final List<Query> should = new ArrayList<>();
        should.add(rangeQuery(timeField, timestamp + 1, null));
        should.add(boolMust(List.of(
            termQuery(timeField, timestamp),
            Query.of(q -> q.range(r -> r.field("messageID").gt(org.opensearch.client.json.JsonData.of(messageId))))
        )));
        return boolShouldMinOne(should);
    }

    public static int count(final OpenSearchClient client, final String indexName, final Query query) throws IOException {
        final CountResponse response = client.count(new CountRequest.Builder().index(indexName).query(query).build());
        return (int) response.count();
    }

    public static List<Long> searchMessageIds(
        final OpenSearchClient client,
        final String indexName,
        final Query query,
        final String sortField,
        final boolean sortDescending,
        final int size
    ) throws IOException {
        final SearchResponse<Map<String, Object>> response = searchDocuments(
            client,
            new SearchRequest.Builder()
                .index(indexName)
                .query(query)
                .sort(s -> s.field(f -> f.field(sortField).order(sortDescending ? SortOrder.Desc : SortOrder.Asc)))
                .size(size)
                .build()
        );

        final List<Long> ids = new ArrayList<>();
        for (Hit<Map<String, Object>> hit : response.hits().hits()) {
            parseLongField(hit.source(), "messageID").ifPresent(ids::add);
        }
        Log.debug("OpenSearch query on {} returned {} message IDs.", indexName, ids.size());
        return ids;
    }

    public static List<Long> searchConversationIds(
        final OpenSearchClient client,
        final String indexName,
        final Query query,
        final String sortField,
        final boolean sortDescending,
        final int from,
        final int size
    ) throws IOException {
        final SearchResponse<Map<String, Object>> response = searchDocuments(
            client,
            new SearchRequest.Builder()
                .index(indexName)
                .query(query)
                .sort(s -> s.field(f -> f.field(sortField).order(sortDescending ? SortOrder.Desc : SortOrder.Asc)))
                .from(from)
                .size(size)
                .build()
        );

        final List<Long> ids = new ArrayList<>();
        for (Hit<Map<String, Object>> hit : response.hits().hits()) {
            parseLongField(hit.source(), "conversationID").ifPresent(ids::add);
        }
        return ids;
    }

    public static long totalHits(final OpenSearchClient client, final String indexName, final Query query) throws IOException {
        final SearchResponse<Map<String, Object>> response = searchDocuments(
            client,
            new SearchRequest.Builder().index(indexName).query(query).size(0).build()
        );
        final var total = response.hits().total();
        return total != null ? total.value() : 0;
    }

    /**
     * Runs a search and returns document sources (plus optional total hit count).
     */
    public static SearchHits search(
        final OpenSearchClient client,
        final String indexName,
        final Query query,
        final String sortField,
        final boolean sortDescending,
        final int size
    ) throws IOException {
        final SearchResponse<Map<String, Object>> response = searchDocuments(
            client,
            new SearchRequest.Builder()
                .index(indexName)
                .query(query)
                .sort(s -> s.field(f -> f.field(sortField).order(sortDescending ? SortOrder.Desc : SortOrder.Asc)))
                .sort(s -> s.field(f -> f.field("messageID").order(sortDescending ? SortOrder.Desc : SortOrder.Asc)))
                .size(size)
                .trackTotalHits(t -> t.enabled(true))
                .build()
        );
        final List<Map<String, Object>> sources = new ArrayList<>();
        for (Hit<Map<String, Object>> hit : response.hits().hits()) {
            if (hit.source() != null) {
                sources.add(hit.source());
            }
        }
        final var total = response.hits().total();
        final long totalValue = total != null ? total.value() : sources.size();
        return new SearchHits(sources, totalValue);
    }

    @SuppressWarnings("unchecked")
    private static SearchResponse<Map<String, Object>> searchDocuments(
        final OpenSearchClient client,
        final SearchRequest request
    ) throws IOException {
        return (SearchResponse<Map<String, Object>>) (SearchResponse<?>) client.search(request, Map.class);
    }

    public static OptionalLong parseLongField(final Map<String, Object> source, final String fieldName) {
        if (source == null) {
            return OptionalLong.empty();
        }
        final Object value = source.get(fieldName);
        if (value instanceof Number number) {
            return OptionalLong.of(number.longValue());
        }
        if (value != null) {
            return OptionalLong.of(Long.parseLong(value.toString()));
        }
        return OptionalLong.empty();
    }

    public static String parseStringField(final Map<String, Object> source, final String fieldName) {
        if (source == null) {
            return null;
        }
        final Object value = source.get(fieldName);
        return value == null ? null : value.toString();
    }

    /**
     * Result of {@link #search(OpenSearchClient, String, Query, String, boolean, int)}.
     */
    public record SearchHits(List<Map<String, Object>> sources, long total) {
    }
}
