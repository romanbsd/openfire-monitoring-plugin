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

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Paged search response.
 */
public final class SearchResult {

    @Nonnull
    private final List<SearchHit> hits;
    private final int count;
    private final boolean complete;

    public SearchResult(@Nonnull final List<SearchHit> hits, final int count, final boolean complete) {
        this.hits = List.copyOf(hits);
        this.count = count;
        this.complete = complete;
    }

    @Nonnull
    public List<SearchHit> getHits() {
        return hits;
    }

    public int getCount() {
        return count;
    }

    public boolean isComplete() {
        return complete;
    }
}
