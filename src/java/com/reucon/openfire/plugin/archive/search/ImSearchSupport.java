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

import com.reucon.openfire.plugin.archive.xep.AbstractXepSupport;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.index.OpenSearchIndexer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;

/**
 * Registers the custom IM search IQ namespace {@value IQSearchHandler#NAMESPACE}.
 */
public class ImSearchSupport extends AbstractXepSupport {

    public ImSearchSupport(final XMPPServer server) {
        // muc=false: search is a server-level feature, not attached to MUC services.
        super(server, IQSearchHandler.NAMESPACE, IQSearchHandler.NAMESPACE, "IM Search IQ Dispatcher", false);
        this.iqHandlers = new ArrayList<>();
        iqHandlers.add(new IQSearchHandler());
    }

    @Override
    public void start() {
        super.start();
        if (!OpenSearchIndexer.isSearchEnabled()) {
            server.getIQDiscoInfoHandler().removeServerFeature(IQSearchHandler.NAMESPACE);
        }
    }

    @Override
    public Iterator<String> getFeatures() {
        if (!OpenSearchIndexer.isSearchEnabled()) {
            return Collections.emptyIterator();
        }
        return super.getFeatures();
    }
}
