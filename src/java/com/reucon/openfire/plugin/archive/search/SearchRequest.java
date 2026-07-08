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
import javax.annotation.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Structured representation of a modifier-based search query.
 */
public final class SearchRequest {

    public enum Sort {
        time,
        relevance
    }

    public enum ResultType {
        messages,
        channels,
        users
    }

    @Nonnull
    private final String freeText;
    @Nonnull
    private final List<String> phrases;
    @Nonnull
    private final List<String> fromValues;
    @Nonnull
    private final List<String> inValues;
    @Nullable
    private final Instant after;
    @Nullable
    private final Instant before;
    @Nonnull
    private final Set<ResultType> types;
    @Nonnull
    private final Sort sort;
    private final boolean includeHighlights;
    private final int maxResults;
    @Nullable
    private final String afterCursor;
    @Nullable
    private final String beforeCursor;
    private final boolean pagingBackwards;

    private SearchRequest(final Builder builder) {
        this.freeText = builder.freeText;
        this.phrases = List.copyOf(builder.phrases);
        this.fromValues = List.copyOf(builder.fromValues);
        this.inValues = List.copyOf(builder.inValues);
        this.after = builder.after;
        this.before = builder.before;
        this.types = Collections.unmodifiableSet(new LinkedHashSet<>(builder.types));
        this.sort = builder.sort;
        this.includeHighlights = builder.includeHighlights;
        this.maxResults = builder.maxResults;
        this.afterCursor = builder.afterCursor;
        this.beforeCursor = builder.beforeCursor;
        this.pagingBackwards = builder.pagingBackwards;
    }

    @Nonnull
    public String getFreeText() {
        return freeText;
    }

    @Nonnull
    public List<String> getPhrases() {
        return phrases;
    }

    @Nonnull
    public List<String> getFromValues() {
        return fromValues;
    }

    @Nonnull
    public List<String> getInValues() {
        return inValues;
    }

    @Nullable
    public Instant getAfter() {
        return after;
    }

    @Nullable
    public Instant getBefore() {
        return before;
    }

    @Nonnull
    public Set<ResultType> getTypes() {
        return types;
    }

    @Nonnull
    public Sort getSort() {
        return sort;
    }

    public boolean isIncludeHighlights() {
        return includeHighlights;
    }

    public int getMaxResults() {
        return maxResults;
    }

    @Nullable
    public String getAfterCursor() {
        return afterCursor;
    }

    @Nullable
    public String getBeforeCursor() {
        return beforeCursor;
    }

    public boolean isPagingBackwards() {
        return pagingBackwards;
    }

    public boolean hasTextConstraint() {
        return (freeText != null && !freeText.isBlank()) || !phrases.isEmpty();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
            .freeText(freeText)
            .phrases(phrases)
            .fromValues(fromValues)
            .inValues(inValues)
            .after(after)
            .before(before)
            .types(types)
            .sort(sort)
            .includeHighlights(includeHighlights)
            .maxResults(maxResults)
            .afterCursor(afterCursor)
            .beforeCursor(beforeCursor)
            .pagingBackwards(pagingBackwards);
    }

    public static final class Builder {
        private String freeText = "";
        private final List<String> phrases = new ArrayList<>();
        private final List<String> fromValues = new ArrayList<>();
        private final List<String> inValues = new ArrayList<>();
        private Instant after;
        private Instant before;
        private final Set<ResultType> types = new LinkedHashSet<>(List.of(ResultType.messages));
        private Sort sort = Sort.time;
        private boolean includeHighlights;
        private int maxResults = 25;
        private String afterCursor;
        private String beforeCursor;
        private boolean pagingBackwards;

        public Builder freeText(final String freeText) {
            this.freeText = freeText == null ? "" : freeText.trim();
            return this;
        }

        public Builder addPhrase(final String phrase) {
            if (phrase != null && !phrase.isBlank()) {
                phrases.add(phrase);
            }
            return this;
        }

        public Builder phrases(final List<String> phrases) {
            this.phrases.clear();
            if (phrases != null) {
                this.phrases.addAll(phrases);
            }
            return this;
        }

        public Builder addFrom(final String from) {
            if (from != null && !from.isBlank()) {
                fromValues.add(from.trim());
            }
            return this;
        }

        public Builder fromValues(final List<String> fromValues) {
            this.fromValues.clear();
            if (fromValues != null) {
                this.fromValues.addAll(fromValues);
            }
            return this;
        }

        public Builder addIn(final String in) {
            if (in != null && !in.isBlank()) {
                inValues.add(in.trim());
            }
            return this;
        }

        public Builder inValues(final List<String> inValues) {
            this.inValues.clear();
            if (inValues != null) {
                this.inValues.addAll(inValues);
            }
            return this;
        }

        public Builder after(final Instant after) {
            this.after = after;
            return this;
        }

        public Builder before(final Instant before) {
            this.before = before;
            return this;
        }

        public Builder types(final Set<ResultType> types) {
            this.types.clear();
            if (types == null || types.isEmpty()) {
                this.types.add(ResultType.messages);
            } else {
                this.types.addAll(types);
            }
            return this;
        }

        public Builder sort(final Sort sort) {
            this.sort = sort == null ? Sort.time : sort;
            return this;
        }

        public Builder includeHighlights(final boolean includeHighlights) {
            this.includeHighlights = includeHighlights;
            return this;
        }

        public Builder maxResults(final int maxResults) {
            this.maxResults = Math.max(1, maxResults);
            return this;
        }

        public Builder afterCursor(final String afterCursor) {
            this.afterCursor = afterCursor;
            return this;
        }

        public Builder beforeCursor(final String beforeCursor) {
            this.beforeCursor = beforeCursor;
            return this;
        }

        public Builder pagingBackwards(final boolean pagingBackwards) {
            this.pagingBackwards = pagingBackwards;
            return this;
        }

        public SearchRequest build() {
            return new SearchRequest(this);
        }
    }
}
