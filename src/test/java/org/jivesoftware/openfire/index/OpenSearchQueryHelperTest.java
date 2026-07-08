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

import org.junit.Test;
import org.opensearch.client.opensearch._types.query_dsl.Query;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Smoke tests for {@link OpenSearchQueryHelper} cursor filter queries.
 */
public class OpenSearchQueryHelperTest {

    @Test
    public void olderThanSortKeyBuildsBoolShouldQuery() {
        final Query query = OpenSearchQueryHelper.olderThanSortKey("logTime", 1_710_000_000_000L, 42L);
        assertNotNull(query);
        assertTrue(query.isBool());
        assertTrue(query.bool().should().size() >= 2);
    }

    @Test
    public void newerThanSortKeyBuildsBoolShouldQuery() {
        final Query query = OpenSearchQueryHelper.newerThanSortKey("logTime", 1_710_000_000_000L, 42L);
        assertNotNull(query);
        assertTrue(query.isBool());
        assertTrue(query.bool().should().size() >= 2);
    }
}
