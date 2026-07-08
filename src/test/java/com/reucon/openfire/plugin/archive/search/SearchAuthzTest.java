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

import org.jivesoftware.openfire.muc.Affiliation;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link SearchAuthz} membership rules.
 */
public class SearchAuthzTest {

    @Test
    public void outcastCannotSearchRoomArchive() {
        assertFalse(SearchAuthz.passesRoomMembershipRules(Affiliation.outcast, false));
        assertFalse(SearchAuthz.passesRoomMembershipRules(Affiliation.outcast, true));
    }

    @Test
    public void ownerAndAdminAlwaysPassMembershipGate() {
        assertTrue(SearchAuthz.passesRoomMembershipRules(Affiliation.owner, true));
        assertTrue(SearchAuthz.passesRoomMembershipRules(Affiliation.admin, true));
    }

    @Test
    public void membersOnlyRoomRequiresMemberAffiliation() {
        assertFalse(SearchAuthz.passesRoomMembershipRules(Affiliation.none, true));
        assertTrue(SearchAuthz.passesRoomMembershipRules(Affiliation.member, true));
    }

    @Test
    public void openRoomAllowsNonMemberAffiliations() {
        assertTrue(SearchAuthz.passesRoomMembershipRules(Affiliation.none, false));
        assertTrue(SearchAuthz.passesRoomMembershipRules(Affiliation.member, false));
    }
}
