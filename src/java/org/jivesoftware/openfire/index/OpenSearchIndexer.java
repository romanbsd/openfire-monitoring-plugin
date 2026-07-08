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
package org.jivesoftware.openfire.index;

import org.jivesoftware.openfire.archive.MonitoringConstants;
import org.jivesoftware.openfire.reporting.util.TaskEngine;
import org.jivesoftware.util.SystemProperty;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.TimerTask;
import java.util.concurrent.Future;

/**
 * Base class for OpenSearch-backed archive indexers.
 */
public abstract class OpenSearchIndexer
{
    protected final Logger Log;

    private final int schemaVersion;
    private final String logName;
    private final String indexSuffix;
    protected final TaskEngine taskEngine;
    protected OpenSearchClientHolder.RebuildFuture rebuildFuture;
    private boolean stopped = false;
    private boolean rebuildInProgress = false;
    private TimerTask indexUpdater;

    private static final SystemProperty<Duration> UPDATE_INTERVAL = SystemProperty.Builder.ofType(Duration.class)
        .setKey("conversation.search.updateInterval")
        .setDefaultValue(Duration.ofMinutes(5))
        .setChronoUnit(ChronoUnit.MINUTES)
        .setDynamic(true)
        .setPlugin(MonitoringConstants.PLUGIN_NAME)
        .build();

    public static final SystemProperty<Boolean> ENABLED = OpenSearchClientHolder.ENABLED;

    protected OpenSearchIndexer(TaskEngine taskEngine, String logName, String indexSuffix, int schemaVersion)
    {
        this.taskEngine = taskEngine;
        this.logName = logName;
        this.indexSuffix = indexSuffix;
        this.schemaVersion = schemaVersion;
        Log = LoggerFactory.getLogger(OpenSearchIndexer.class.getName() + "[" + logName + "]");
    }

    public static boolean isSearchEnabled() {
        return OpenSearchClientHolder.isSearchEnabled();
    }

    protected String getIndexName() {
        return OpenSearchClientHolder.indexName(indexSuffix);
    }

    protected OpenSearchClient getClient() {
        return OpenSearchClientHolder.getClient();
    }

    public void start()
    {
        if (!OpenSearchClientHolder.isSearchEnabled()) {
            Log.debug("Unable to start: indexing is disabled or OpenSearch is not configured.");
            return;
        }

        Log.debug("Starting...");
        OpenSearchClientHolder.retainClient();

        try {
            ensureIndexExists();
            final OpenSearchClientHolder.InstantCursor cursor = OpenSearchClientHolder.readCursor(logName);
            if (cursor.schemaVersion() < schemaVersion || cursor.lastModified().equals(Instant.EPOCH)) {
                Log.info("OpenSearch index {} requires rebuild (schema {} vs required {}).", getIndexName(), cursor.schemaVersion(), schemaVersion);
                submitUnchecked(this::rebuildIndexUnchecked);
            }
        } catch (IOException e) {
            Log.error("An exception occurred while initializing the OpenSearch index {}.", getIndexName(), e);
        }

        indexUpdater = new TimerTask() {
            @Override
            public void run() {
                updateIndex();
            }
        };
        final Duration updateInterval = UPDATE_INTERVAL.getValue();
        taskEngine.schedule(indexUpdater, Duration.ofMinutes(1), updateInterval);
    }

    public void stop()
    {
        Log.debug("Stopping...");
        stopped = true;
        if (indexUpdater != null) {
            indexUpdater.cancel();
            indexUpdater = null;
        }
        OpenSearchClientHolder.releaseClient();
        rebuildFuture = null;
    }

    public long getIndexSize()
    {
        if (!OpenSearchClientHolder.isSearchEnabled()) {
            return 0;
        }
        return OpenSearchClientHolder.getIndexStoreSizeBytes(getClient(), getIndexName());
    }

    public void updateIndex()
    {
        if (!OpenSearchClientHolder.isSearchEnabled()) {
            Log.debug("Unable to update: indexing is disabled or OpenSearch is not configured.");
            return;
        }
        if (stopped || rebuildInProgress) {
            return;
        }

        Log.debug("Updating the OpenSearch index {}...", getIndexName());
        final Instant start = Instant.now();
        try {
            final OpenSearchClientHolder.InstantCursor cursor = OpenSearchClientHolder.readCursor(logName);
            final Instant lastModified = doUpdateIndex(cursor.lastModified());
            OpenSearchClientHolder.writeCursor(logName, lastModified, schemaVersion);
            Log.debug("Finished updating OpenSearch index {}. Duration: {}.", getIndexName(), Duration.between(start, Instant.now()));
        } catch (Exception e) {
            Log.error("An exception occurred while updating the OpenSearch index {}.", getIndexName(), e);
        }
    }

    public synchronized Future<Integer> rebuildIndex()
    {
        if (!OpenSearchClientHolder.isSearchEnabled()) {
            Log.debug("Unable to rebuild: indexing is disabled or OpenSearch is not configured.");
            return null;
        }
        if (stopped || rebuildInProgress) {
            return null;
        }

        rebuildInProgress = true;
        rebuildFuture = new OpenSearchClientHolder.RebuildFuture();

        submitUnchecked(() -> {
            Log.debug("Rebuilding the OpenSearch index {}...", getIndexName());
            final Instant start = Instant.now();
            try {
                ensureIndexExists();
                final Instant newest = doRebuildIndex();
                OpenSearchClientHolder.writeCursor(logName, newest, schemaVersion);
                Log.debug("Finished rebuilding OpenSearch index {}. Duration: {}.", getIndexName(), Duration.between(start, Instant.now()));
            } catch (Exception e) {
                Log.error("An exception occurred while rebuilding the OpenSearch index {}.", getIndexName(), e);
            } finally {
                rebuildFuture.setPercentageDone(100);
                rebuildFuture = null;
                rebuildInProgress = false;
            }
        });

        return rebuildFuture;
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private void rebuildIndexUnchecked()
    {
        rebuildIndex();
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private void submitUnchecked(final Runnable task)
    {
        taskEngine.submit(task);
    }

    public Future<Integer> getIndexRebuildProgress()
    {
        return rebuildFuture;
    }

    protected abstract void ensureIndexExists() throws IOException;

    protected abstract Instant doUpdateIndex(Instant lastModified) throws IOException;

    protected abstract Instant doRebuildIndex() throws IOException;
}
