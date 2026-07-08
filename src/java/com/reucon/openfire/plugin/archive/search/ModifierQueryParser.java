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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a modifier-based search string into a {@link SearchRequest}.
 *
 * <p>Supported modifiers: {@code from:}, {@code in:}, {@code before:}, {@code after:}, plus
 * free text and {@code "quoted phrases"}.</p>
 */
public final class ModifierQueryParser {

    public static final Set<String> SUPPORTED_MODIFIERS = Set.of("from", "in", "before", "after");

    private static final Pattern TOKEN = Pattern.compile(
        "\"([^\"]*)\"|(\\S+:\\S+)|(\\S+)"
    );

    private ModifierQueryParser() {
    }

    @Nonnull
    public static SearchRequest parse(@Nonnull final String query) throws IllegalArgumentException {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Search query must not be empty.");
        }

        final SearchRequest.Builder builder = SearchRequest.builder();
        final List<String> freeWords = new ArrayList<>();
        Instant after = null;
        Instant before = null;

        final Matcher matcher = TOKEN.matcher(query.trim());
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                builder.addPhrase(matcher.group(1));
                continue;
            }
            final String token = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            final int colon = token.indexOf(':');
            if (colon > 0 && matcher.group(2) != null) {
                final String key = token.substring(0, colon).toLowerCase(Locale.ROOT);
                final String value = token.substring(colon + 1);
                if (value.isEmpty()) {
                    throw new IllegalArgumentException("Modifier '" + key + "' requires a value.");
                }
                switch (key) {
                    case "from" -> builder.addFrom(value);
                    case "in" -> builder.addIn(value);
                    case "after" -> after = parseInstant(value, false);
                    case "before" -> before = parseInstant(value, true);
                    default -> throw new IllegalArgumentException("Unsupported search modifier: " + key);
                }
            } else {
                freeWords.add(token);
            }
        }

        builder.freeText(String.join(" ", freeWords));
        builder.after(after);
        builder.before(before);
        return builder.build();
    }

    /**
     * @param endOfDay when true and value is a date-only, use end of that UTC day (inclusive before filter).
     */
    @Nonnull
    static Instant parseInstant(@Nonnull final String value, final boolean endOfDay) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            final LocalDate date = LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
            if (endOfDay) {
                return date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusMillis(1);
            }
            return date.atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date value: " + value);
        }
    }
}
