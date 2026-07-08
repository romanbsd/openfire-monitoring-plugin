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

import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.muc.Affiliation;
import org.jivesoftware.openfire.muc.MUCRoom;
import org.jivesoftware.openfire.muc.MultiUserChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Computes the set of archives a requestor may search.
 *
 * <p>Room access uses affiliation rules similar to MAM. Password-protected rooms are only
 * included when the requestor is currently an occupant.</p>
 */
public final class SearchAuthz {

    private static final Logger Log = LoggerFactory.getLogger(SearchAuthz.class);

    private SearchAuthz() {
    }

    /**
     * Authorization and filter resolution for one search request.
     */
    public static final class Scope {
        @Nonnull
        private final JID requestor;
        @Nonnull
        private final Set<JID> allowedRoomJids;
        @Nonnull
        private final Set<Long> allowedRoomIds;
        @Nonnull
        private final Map<Long, JID> roomIdToJid;
        @Nonnull
        private final Set<JID> fromJids;
        @Nonnull
        private final Set<JID> peerJids;
        private final boolean includePersonal;
        private final boolean includeRooms;

        Scope(
            @Nonnull final JID requestor,
            @Nonnull final Set<JID> allowedRoomJids,
            @Nonnull final Set<Long> allowedRoomIds,
            @Nonnull final Map<Long, JID> roomIdToJid,
            @Nonnull final Set<JID> fromJids,
            @Nonnull final Set<JID> peerJids,
            final boolean includePersonal,
            final boolean includeRooms
        ) {
            this.requestor = requestor;
            this.allowedRoomJids = Collections.unmodifiableSet(allowedRoomJids);
            this.allowedRoomIds = Collections.unmodifiableSet(allowedRoomIds);
            this.roomIdToJid = Collections.unmodifiableMap(roomIdToJid);
            this.fromJids = Collections.unmodifiableSet(fromJids);
            this.peerJids = Collections.unmodifiableSet(peerJids);
            this.includePersonal = includePersonal;
            this.includeRooms = includeRooms;
        }

        @Nonnull
        public JID getRequestor() {
            return requestor;
        }

        @Nonnull
        public Set<JID> getAllowedRoomJids() {
            return allowedRoomJids;
        }

        @Nonnull
        public Set<Long> getAllowedRoomIds() {
            return allowedRoomIds;
        }

        @Nonnull
        public Map<Long, JID> getRoomIdToJid() {
            return roomIdToJid;
        }

        @Nonnull
        public Set<JID> getFromJids() {
            return fromJids;
        }

        @Nonnull
        public Set<JID> getPeerJids() {
            return peerJids;
        }

        public boolean isIncludePersonal() {
            return includePersonal;
        }

        public boolean isIncludeRooms() {
            return includeRooms;
        }

        public boolean isEmpty() {
            return !includePersonal && allowedRoomJids.isEmpty() && allowedRoomIds.isEmpty();
        }

        @Nullable
        public JID roomJidForId(final long roomId) {
            return roomIdToJid.get(roomId);
        }
    }

    @Nonnull
    public static Scope resolve(@Nonnull final JID requestorFullOrBare, @Nonnull final SearchRequest request) {
        final JID requestor = requestorFullOrBare.asBareJID();
        final AllowedRooms allAllowed = collectAllowedRooms(requestor, requestorFullOrBare);
        augmentWithRequestedRooms(allAllowed, request.getInValues(), requestor, requestorFullOrBare);
        final Set<JID> fromJids = resolveFromModifiers(request.getFromValues());
        final ResolvedIn in = resolveInModifiers(request.getInValues());

        final Set<JID> roomFilter = new LinkedHashSet<>();
        final Set<JID> peerFilter = new LinkedHashSet<>(in.peerJids);
        final boolean includePersonal;
        final boolean includeRooms;

        if (request.getInValues().isEmpty()) {
            roomFilter.addAll(allAllowed.roomJids);
            includePersonal = true;
            includeRooms = true;
        } else {
            for (final JID room : in.roomJids) {
                if (allAllowed.roomJids.contains(room.asBareJID())) {
                    roomFilter.add(room.asBareJID());
                }
            }
            // Rooms only → no personal. Peers only → personal DM filter. Both → both. Neither → empty.
            includeRooms = !roomFilter.isEmpty();
            includePersonal = !peerFilter.isEmpty();
        }

        final Set<Long> roomIds = new LinkedHashSet<>();
        for (final Map.Entry<Long, JID> entry : allAllowed.roomIdToJid.entrySet()) {
            if (roomFilter.contains(entry.getValue())) {
                roomIds.add(entry.getKey());
            }
        }

        return new Scope(
            requestor,
            includeRooms ? roomFilter : Set.of(),
            includeRooms ? roomIds : Set.of(),
            allAllowed.roomIdToJid,
            fromJids,
            peerFilter,
            includePersonal,
            includeRooms
        );
    }

    @Nonnull
    static Set<JID> resolveFromModifiers(final Collection<String> values) {
        final Set<JID> result = new LinkedHashSet<>();
        final String domain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        for (final String value : values) {
            try {
                if (value.contains("@")) {
                    result.add(new JID(value).asBareJID());
                } else {
                    result.add(new JID(value, domain, null));
                }
            } catch (IllegalArgumentException e) {
                Log.debug("Ignoring invalid from: value '{}'", value);
            }
        }
        return result;
    }

    private static final class ResolvedIn {
        private final Set<JID> roomJids = new LinkedHashSet<>();
        private final Set<JID> peerJids = new LinkedHashSet<>();
    }

    @Nonnull
    private static ResolvedIn resolveInModifiers(final Collection<String> values) {
        final ResolvedIn resolved = new ResolvedIn();
        final String domain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        for (final String value : values) {
            try {
                final JID jid;
                if (value.contains("@")) {
                    jid = new JID(value).asBareJID();
                } else {
                    final JID roomMatch = findRoomByNode(value);
                    jid = roomMatch != null ? roomMatch : new JID(value, domain, null);
                }
                if (XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatService(jid) != null) {
                    resolved.roomJids.add(jid.asBareJID());
                } else {
                    resolved.peerJids.add(jid.asBareJID());
                }
            } catch (IllegalArgumentException e) {
                Log.debug("Ignoring invalid in: value '{}'", value);
            }
        }
        return resolved;
    }

    @Nullable
    private static JID findRoomByNode(final String node) {
        for (final MultiUserChatService service : XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatServices()) {
            final MUCRoom room = service.getChatRoom(node);
            if (room != null) {
                return room.getJID().asBareJID();
            }
        }
        return null;
    }

    private static void augmentWithRequestedRooms(
        final AllowedRooms allowed,
        final Collection<String> inValues,
        @Nonnull final JID requestorBare,
        @Nonnull final JID requestorFull
    ) {
        for (final String value : inValues) {
            final JID roomJid = resolveInValueToRoomJid(value);
            if (roomJid == null || allowed.roomJids.contains(roomJid)) {
                continue;
            }
            final MultiUserChatService service = XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatService(roomJid);
            if (service == null) {
                continue;
            }
            final MUCRoom room = service.getChatRoom(roomJid.getNode());
            if (room == null) {
                continue;
            }
            if (canAccessRoomArchive(room, requestorBare, requestorFull, service.isSysadmin(requestorBare))) {
                final JID bareRoomJid = roomJid.asBareJID();
                allowed.roomJids.add(bareRoomJid);
                allowed.roomIdToJid.put(room.getID(), bareRoomJid);
            }
        }
    }

    @Nullable
    private static JID resolveInValueToRoomJid(final String value) {
        try {
            if (value.contains("@")) {
                final JID jid = new JID(value).asBareJID();
                return XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatService(jid) != null
                    ? jid
                    : null;
            }
            return findRoomByNode(value);
        } catch (IllegalArgumentException e) {
            Log.debug("Ignoring invalid in: room value '{}'", value);
            return null;
        }
    }

    private static final class AllowedRooms {
        private final Set<JID> roomJids = new LinkedHashSet<>();
        private final Map<Long, JID> roomIdToJid = new HashMap<>();
    }

    @Nonnull
    private static AllowedRooms collectAllowedRooms(@Nonnull final JID requestorBare, @Nonnull final JID requestorFull) {
        final AllowedRooms allowed = new AllowedRooms();
        for (final MultiUserChatService service : XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatServices()) {
            final boolean isSysadmin = service.isSysadmin(requestorBare);
            for (final MUCRoom room : service.getActiveChatRooms()) {
                if (canAccessRoomArchive(room, requestorBare, requestorFull, isSysadmin)) {
                    final JID roomJid = room.getJID().asBareJID();
                    allowed.roomJids.add(roomJid);
                    allowed.roomIdToJid.put(room.getID(), roomJid);
                }
            }
        }
        return allowed;
    }

    static boolean canAccessRoomArchive(
        final MUCRoom room,
        final JID requestorBare,
        final JID requestorFull,
        final boolean isSysadmin
    ) {
        if (isSysadmin) {
            return true;
        }
        if (!passesRoomMembershipRules(room.getAffiliation(requestorBare), room.isMembersOnly())) {
            return false;
        }
        if (room.isPasswordProtected()) {
            return room.getOccupantByFullJID(requestorFull) != null;
        }
        return true;
    }

    /**
     * Membership / affiliation gate for room archive search (password protection handled separately).
     */
    static boolean passesRoomMembershipRules(final Affiliation aff, final boolean membersOnly) {
        if (aff == Affiliation.outcast) {
            return false;
        }
        if (aff == Affiliation.owner || aff == Affiliation.admin) {
            return true;
        }
        if (membersOnly) {
            return aff == Affiliation.member;
        }
        return true;
    }
}
