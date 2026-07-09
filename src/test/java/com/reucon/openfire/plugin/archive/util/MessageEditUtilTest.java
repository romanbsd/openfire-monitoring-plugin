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

import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.QName;
import org.junit.Test;
import org.xmpp.packet.Message;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link MessageEditUtil}.
 */
public class MessageEditUtilTest {

    @Test
    public void detectsCorrectionAndReplaceId() {
        final Message message = new Message();
        message.setID("good1");
        message.setBody("corrected");
        message.getElement().addElement(QName.get("replace", MessageEditUtil.NS_MESSAGE_CORRECT))
            .addAttribute("id", "bad1");

        assertTrue(MessageEditUtil.isCorrection(message));
        assertFalse(MessageEditUtil.isRetraction(message));
        assertTrue(MessageEditUtil.isMessageEdit(message));
        assertEquals("bad1", MessageEditUtil.getReplaceId(message));
        assertEquals("bad1", MessageEditUtil.getReferencedId(message));
    }

    @Test
    public void detectsRetractionV1WithIdAttribute() {
        final Message message = new Message();
        message.setID("retract-1");
        message.getElement().addElement(QName.get("retract", MessageEditUtil.NS_MESSAGE_RETRACT_1))
            .addAttribute("id", "wrong-recipient-1");

        assertTrue(MessageEditUtil.isRetraction(message));
        assertFalse(MessageEditUtil.isCorrection(message));
        assertEquals("wrong-recipient-1", MessageEditUtil.getRetractId(message));
        assertEquals("wrong-recipient-1", MessageEditUtil.getReferencedId(message));
    }

    @Test
    public void detectsRetractionV0WithNestedOriginId() {
        final Message message = new Message();
        final Element retract = message.getElement().addElement(QName.get("retract", MessageEditUtil.NS_MESSAGE_RETRACT_0));
        retract.addElement(QName.get("origin-id", MessageEditUtil.NS_SID)).addAttribute("id", "origin-42");

        assertEquals("origin-42", MessageEditUtil.getRetractId(message));
    }

    @Test
    public void missingEditPayloadReturnsNull() {
        final Message message = new Message();
        message.setBody("hello");
        assertNull(MessageEditUtil.getReplaceId(message));
        assertNull(MessageEditUtil.getRetractId(message));
        assertFalse(MessageEditUtil.isMessageEdit(message));
    }

    @Test
    public void emptyReplaceIdIsIgnored() {
        final Message message = new Message();
        message.getElement().addElement(QName.get("replace", MessageEditUtil.NS_MESSAGE_CORRECT))
            .addAttribute("id", "  ");
        assertNull(MessageEditUtil.getReplaceId(message));
        assertFalse(MessageEditUtil.isCorrection(message));
    }

    @Test
    public void stanzaMatchesByMessageId() throws Exception {
        final Message stored = new Message(DocumentHelper.parseText(
            "<message xmlns='jabber:client' id='bad1'><body>old</body></message>"
        ).getRootElement());
        assertTrue(MessageEditUtil.stanzaMatchesReferencedId(stored, "bad1", "alice@example.com"));
        assertFalse(MessageEditUtil.stanzaMatchesReferencedId(stored, "other", "alice@example.com"));
    }

    @Test
    public void stanzaMatchesByArchiveOwnerStanzaId() throws Exception {
        final Message stored = new Message(DocumentHelper.parseText(
            "<message xmlns='jabber:client' id='client-1'>"
                + "<body>old</body>"
                + "<stanza-id xmlns='urn:xmpp:sid:0' by='room@conference.example.com' id='sid-99'/>"
                + "</message>"
        ).getRootElement());
        assertTrue(MessageEditUtil.stanzaMatchesReferencedId(stored, "sid-99", "room@conference.example.com"));
        assertTrue(MessageEditUtil.stanzaMatchesReferencedId(stored, "client-1", "room@conference.example.com"));
        assertFalse(MessageEditUtil.stanzaMatchesReferencedId(stored, "sid-99", "alice@example.com"));
    }

    @Test
    public void sqlLikeContainsEscapesWildcards() {
        assertEquals("%plain%", MessageEditUtil.sqlLikeContains("plain"));
        assertEquals("%\\%%", MessageEditUtil.sqlLikeContains("%"));
        assertEquals("%\\_%", MessageEditUtil.sqlLikeContains("_"));
        assertEquals("%a\\%b\\_c%", MessageEditUtil.sqlLikeContains("a%b_c"));
        assertEquals("%foo\\\\bar%", MessageEditUtil.sqlLikeContains("foo\\bar"));
    }
}
