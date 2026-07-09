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

import com.google.common.base.Splitter;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.core5.http.HttpHost;
import org.jivesoftware.openfire.archive.MonitoringConstants;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.SystemProperty;
import org.jspecify.annotations.NonNull;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.Conflicts;
import org.opensearch.client.opensearch._types.Refresh;
import org.opensearch.client.opensearch._types.Script;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.DeleteByQueryRequest;
import org.opensearch.client.opensearch.core.UpdateByQueryRequest;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5Transport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Shared OpenSearch client access for monitoring plugin indexers and searchers.
 */
public final class OpenSearchClientHolder {
    private static final Logger Log = LoggerFactory.getLogger(OpenSearchClientHolder.class);

    public static final SystemProperty<Boolean> ENABLED = SystemProperty.Builder.ofType(Boolean.class)
        .setKey("conversation.search.index-enabled")
        .setDefaultValue(true)
        .setDynamic(false)
        .setPlugin(MonitoringConstants.PLUGIN_NAME)
        .build();

    public static final SystemProperty<String> HOST = SystemProperty.Builder.ofType(String.class)
        .setKey("conversation.search.opensearch.host")
        .setDefaultValue("")
        .setDynamic(false)
        .setPlugin(MonitoringConstants.PLUGIN_NAME)
        .build();

    public static final SystemProperty<Integer> PORT = SystemProperty.Builder.ofType(Integer.class)
        .setKey("conversation.search.opensearch.port")
        .setDefaultValue(9200)
        .setDynamic(false)
        .setPlugin(MonitoringConstants.PLUGIN_NAME)
        .build();

    public static final SystemProperty<String> SCHEME = SystemProperty.Builder.ofType(String.class)
        .setKey("conversation.search.opensearch.scheme")
        .setDefaultValue("https")
        .setDynamic(false)
        .setPlugin(MonitoringConstants.PLUGIN_NAME)
        .build();

    public static final SystemProperty<String> USERNAME = SystemProperty.Builder.ofType(String.class)
        .setKey("conversation.search.opensearch.username")
        .setDefaultValue("")
        .setDynamic(false)
        .setPlugin(MonitoringConstants.PLUGIN_NAME)
        .build();

    public static final SystemProperty<String> PASSWORD = SystemProperty.Builder.ofType(String.class)
        .setKey("conversation.search.opensearch.password")
        .setDefaultValue("")
        .setDynamic(false)
        .setPlugin(MonitoringConstants.PLUGIN_NAME)
        .build();

    public static final SystemProperty<String> INDEX_PREFIX = SystemProperty.Builder.ofType(String.class)
        .setKey("conversation.search.opensearch.indexPrefix")
        .setDefaultValue("monitoring")
        .setDynamic(false)
        .setPlugin(MonitoringConstants.PLUGIN_NAME)
        .build();

    private static final Object LOCK = new Object();
    private static ApacheHttpClient5Transport transport;
    private static OpenSearchClient client;
    private static int referenceCount;

    private OpenSearchClientHolder() {
    }

    public static boolean isSearchEnabled() {
        if (!ENABLED.getValue()) {
            return false;
        }
        final String host = HOST.getValue();
        return host != null && !host.isBlank();
    }

    public static String indexName(final String suffix) {
        return INDEX_PREFIX.getValue() + "-" + suffix;
    }

    public static OpenSearchClient getClient() {
        synchronized (LOCK) {
            if (client != null) {
                return client;
            }
            if (!isSearchEnabled()) {
                return null;
            }
            final HttpHost httpHost = new HttpHost(SCHEME.getValue(), HOST.getValue(), PORT.getValue());
            final ApacheHttpClient5TransportBuilder builder = ApacheHttpClient5TransportBuilder.builder(httpHost)
                .setMapper(new JacksonJsonpMapper());
            final String username = USERNAME.getValue();
            if (username != null && !username.isBlank()) {
                final BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                credentialsProvider.setCredentials(
                    new AuthScope(httpHost),
                    new UsernamePasswordCredentials(username, PASSWORD.getValue().toCharArray())
                );
                builder.setHttpClientConfigCallback(httpClientBuilder ->
                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider)
                );
            }
            transport = builder.build();
            client = new OpenSearchClient(transport);
            Log.info("Connected to OpenSearch at {}://{}:{}", SCHEME.getValue(), HOST.getValue(), PORT.getValue());
            return client;
        }
    }

    public static void retainClient() {
        synchronized (LOCK) {
            referenceCount++;
        }
    }

    public static void releaseClient() {
        synchronized (LOCK) {
            referenceCount--;
            if (referenceCount <= 0) {
                closeClient();
            }
        }
    }

    public static void closeClient() {
        synchronized (LOCK) {
            if (transport != null) {
                try {
                    transport.close();
                } catch (IOException e) {
                    Log.warn("An exception occurred while closing the OpenSearch client.", e);
                }
            }
            transport = null;
            client = null;
            referenceCount = 0;
        }
    }

    public static void ensureIndex(final OpenSearchClient openSearchClient, final String indexName, final Map<String, Object> mappings) throws IOException {
        final boolean exists = openSearchClient.indices().exists(e -> e.index(indexName)).value();
        if (!exists) {
            openSearchClient.indices().create(c -> c
                .index(indexName)
                .mappings(m -> m.withJson(new StringReader(toJson(mappings))))
            );
            Log.info("Created OpenSearch index {}", indexName);
        }
    }

    public static void bulkIndex(final OpenSearchClient openSearchClient, final String indexName, final List<BulkOperation> operations) throws IOException {
        if (operations.isEmpty()) {
            return;
        }
        final BulkRequest request = new BulkRequest.Builder().operations(operations).build();
        final BulkResponse response = openSearchClient.bulk(request);
        if (response.errors()) {
            Log.warn("OpenSearch bulk indexing reported errors for index {}.", indexName);
        }
    }

    public static void deleteByQuery(final OpenSearchClient openSearchClient, final String indexName, final Query query) throws IOException {
        openSearchClient.deleteByQuery(new DeleteByQueryRequest.Builder()
            .index(indexName)
            .query(query)
            // Version conflicts are common when a concurrent indexer touches the same docs.
            .conflicts(Conflicts.Proceed)
            .refresh(Refresh.True)
            .build());
    }

    /**
     * Sets {@code body}/{@code retracted}/{@code contentStamp} on matching docs.
     * Noops when an existing stamp is newer. Pass {@code retracted=true} (and empty body) to tombstone.
     */
    public static void updateContentByQuery(
        final OpenSearchClient openSearchClient,
        final String indexName,
        final Query query,
        final String body,
        final boolean retracted,
        final long contentStamp
    ) throws IOException {
        openSearchClient.updateByQuery(new UpdateByQueryRequest.Builder()
            .index(indexName)
            .query(query)
            .conflicts(Conflicts.Proceed)
            .script(Script.of(s -> s.inline(i -> i
                .source(
                    "if (ctx._source.contentStamp != null && ctx._source.contentStamp > params.stamp) {"
                        + " ctx.op = 'noop'; "
                        + "} else {"
                        + " ctx._source.body = params.body;"
                        + " ctx._source.contentStamp = params.stamp;"
                        + " ctx._source.retracted = params.retracted;"
                        + "}"
                )
                .params("body", JsonData.of(body == null ? "" : body))
                .params("stamp", JsonData.of(contentStamp))
                .params("retracted", JsonData.of(retracted))
            )))
            .build());
    }

    public static long getIndexStoreSizeBytes(final OpenSearchClient openSearchClient, final String indexName) {
        try {
            final var stats = openSearchClient.indices().stats(s -> s.index(indexName));
            if (!stats.indices().containsKey(indexName)) {
                return 0;
            }
            return Objects.requireNonNull(stats.indices().get(indexName).total().store()).sizeInBytes();
        } catch (IOException e) {
            Log.warn("Unable to retrieve OpenSearch index size for {}.", indexName, e);
            return 0;
        }
    }

    public static Path cursorDirectory() {
        return JiveGlobals.getHomePath().resolve(Path.of(MonitoringConstants.NAME, "search-cursors"));
    }

    public static InstantCursor readCursor(final String logName) {
        final Path cursorFile = cursorDirectory().resolve(logName + ".lastModified");
        if (!Files.exists(cursorFile)) {
            return new InstantCursor(java.time.Instant.EPOCH, 0);
        }
        try {
            final String content = Files.readString(cursorFile, StandardCharsets.UTF_8).trim();
            final List<String> parts = Splitter.on(',').splitToList(content);
            final java.time.Instant instant = !parts.isEmpty() && !parts.get(0).isBlank()
                ? java.time.Instant.ofEpochMilli(Long.parseLong(parts.get(0)))
                : java.time.Instant.EPOCH;
            final int schemaVersion = parts.size() > 1 ? Integer.parseInt(parts.get(1)) : 0;
            return new InstantCursor(instant, schemaVersion);
        } catch (IOException | NumberFormatException e) {
            Log.warn("Unable to read OpenSearch cursor file for {}.", logName, e);
            return new InstantCursor(java.time.Instant.EPOCH, 0);
        }
    }

    public static void writeCursor(final String logName, final java.time.Instant lastModified, final int schemaVersion) throws IOException {
        final Path dir = cursorDirectory();
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        Files.writeString(
            dir.resolve(logName + ".lastModified"),
            lastModified.toEpochMilli() + "," + schemaVersion,
            StandardCharsets.UTF_8
        );
    }

    private static String toJson(final Map<String, Object> mappings) {
        final StringBuilder sb = new StringBuilder("{");
        sb.append("\"properties\":{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : mappings.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(entry.getKey()).append("\":");
            sb.append(entry.getValue());
        }
        sb.append("}}");
        return sb.toString();
    }

    public record InstantCursor(java.time.Instant lastModified, int schemaVersion) {
    }

    public static class RebuildFuture implements Future<Integer> {
        private int percentageDone = 0;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return percentageDone == 100;
        }

        @Override
        public Integer get() {
            return percentageDone;
        }

        @Override
        public Integer get(long timeout, @NonNull TimeUnit unit) {
            return percentageDone;
        }

        public void setPercentageDone(int percentageDone) {
            if (percentageDone < 0 || percentageDone > 100) {
                throw new IllegalArgumentException("Invalid value: " + percentageDone);
            }
            this.percentageDone = percentageDone;
        }
    }
}
