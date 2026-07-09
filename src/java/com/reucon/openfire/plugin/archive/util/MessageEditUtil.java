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
package com.reucon.openfire.plugin.archive.util;

import org.dom4j.Element;
import org.dom4j.QName;
import org.jivesoftware.openfire.stanzaid.StanzaIDUtil;
import org.xmpp.packet.Message;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Helpers for XEP-0308 (Last Message Correction) and XEP-0424 (Message Retraction).
 */
public final class MessageEditUtil {

    public static final String NS_MESSAGE_CORRECT = "urn:xmpp:message-correct:0";
    public static final String NS_MESSAGE_RETRACT_0 = "urn:xmpp:message-retract:0";
    public static final String NS_MESSAGE_RETRACT_1 = "urn:xmpp:message-retract:1";
    public static final String NS_SID = "urn:xmpp:sid:0";

    private MessageEditUtil() {
    }

    public static boolean isCorrection(@Nullable final Message message) {
        return getReplaceId(message) != null;
    }

    public static boolean isRetraction(@Nullable final Message message) {
        return getRetractId(message) != null;
    }

    public static boolean isMessageEdit(@Nullable final Message message) {
        return isCorrection(message) || isRetraction(message);
    }

    /**
     * Returns the {@code id} from {@code <replace xmlns='urn:xmpp:message-correct:0'/>}, or {@code null}.
     */
    @Nullable
    public static String getReplaceId(@Nullable final Message message) {
        if (message == null) {
            return null;
        }
        final Element replace = message.getElement().element(QName.get("replace", NS_MESSAGE_CORRECT));
        return attributeId(replace);
    }

    /**
     * Returns the target id from a retract element ({@code :0} or {@code :1}), or {@code null}.
     */
    @Nullable
    public static String getRetractId(@Nullable final Message message) {
        if (message == null) {
            return null;
        }
        Element retract = message.getElement().element(QName.get("retract", NS_MESSAGE_RETRACT_1));
        if (retract == null) {
            retract = message.getElement().element(QName.get("retract", NS_MESSAGE_RETRACT_0));
        }
        if (retract == null) {
            return null;
        }
        final String idAttr = attributeId(retract);
        if (idAttr != null) {
            return idAttr;
        }
        final Element originId = retract.element(QName.get("origin-id", NS_SID));
        final String origin = attributeId(originId);
        if (origin != null) {
            return origin;
        }
        final Element stanzaId = retract.element(QName.get("stanza-id", NS_SID));
        return attributeId(stanzaId);
    }

    /**
     * Target id for either a correction or a retraction.
     */
    @Nullable
    public static String getReferencedId(@Nullable final Message message) {
        final String replaceId = getReplaceId(message);
        if (replaceId != null) {
            return replaceId;
        }
        return getRetractId(message);
    }

    /**
     * Whether a stored archive stanza is the target of {@code referencedId}, matching message {@code @id}
     * or an archive-owner XEP-0359 {@code <stanza-id/>}.
     */
    public static boolean stanzaMatchesReferencedId(
        @Nonnull final Message stored,
        @Nonnull final String referencedId,
        @Nonnull final String archiveOwnerBare
    ) {
        if (referencedId.equals(stored.getID())) {
            return true;
        }
        final String sid = StanzaIDUtil.findFirstUniqueAndStableStanzaID(stored, archiveOwnerBare);
        return referencedId.equals(sid);
    }

    /**
     * Builds a SQL {@code LIKE} pattern that matches {@code literal} as a substring,
     * escaping {@code %}, {@code _}, and {@code \} so client-supplied ids cannot widen the scan.
     * Pair with {@code ESCAPE '\\'} in the SQL.
     */
    @Nonnull
    public static String sqlLikeContains(@Nonnull final String literal) {
        return "%" + literal.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    @Nullable
    private static String attributeId(@Nullable final Element element) {
        if (element == null) {
            return null;
        }
        final String id = element.attributeValue("id");
        if (id == null || id.isBlank()) {
            return null;
        }
        return id;
    }
}
