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
package com.reucon.openfire.plugin.archive.impl;

import com.reucon.openfire.plugin.archive.util.MessageEditUtil;
import org.dom4j.QName;
import org.junit.Test;
import org.xmpp.packet.Message;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Indexer policy checks for XEP-0308 / XEP-0424 (no OpenSearch required).
 */
public class MessageEditIndexRulesTest {

    @Test
    public void correctionShouldUpdateNotIndexAsOwnHit() {
        final Message correction = new Message();
        correction.setBody("new text");
        correction.getElement().addElement(QName.get("replace", MessageEditUtil.NS_MESSAGE_CORRECT))
            .addAttribute("id", "orig-1");

        assertTrue(MessageEditUtil.isCorrection(correction));
        assertFalse(MessageEditUtil.isRetraction(correction));
        assertEquals("orig-1", MessageEditUtil.getReferencedId(correction));
        // Rebuild path: edit rows are skipped as standalone documents.
        assertTrue(MessageEditUtil.isMessageEdit(correction));
    }

    @Test
    public void bodyLessRetractionStillDetected() {
        final Message retract = new Message();
        retract.getElement().addElement(QName.get("retract", MessageEditUtil.NS_MESSAGE_RETRACT_1))
            .addAttribute("id", "orig-2");

        assertTrue(MessageEditUtil.isRetraction(retract));
        assertTrue(MessageEditUtil.isMessageEdit(retract));
        assertEquals("orig-2", MessageEditUtil.getReferencedId(retract));
    }

    @Test
    public void normalMessageIsIndexedAsOwnHit() {
        final Message normal = new Message();
        normal.setBody("hello");
        assertFalse(MessageEditUtil.isMessageEdit(normal));
    }
}
