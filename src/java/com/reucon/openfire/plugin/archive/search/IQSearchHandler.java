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

import com.reucon.openfire.plugin.archive.xep.AbstractIQHandler;
import com.reucon.openfire.plugin.archive.xep0059.XmppResultSet;
import org.dom4j.Element;
import org.dom4j.QName;
import org.jivesoftware.openfire.disco.ServerFeaturesProvider;
import org.jivesoftware.openfire.index.OpenSearchIndexer;
import org.jivesoftware.util.XMPPDateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.IQ;
import org.xmpp.packet.PacketError;

import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Handles {@code urn:xmpp:im:search:0} IQ stanzas for cross-archive message search.
 */
public class IQSearchHandler extends AbstractIQHandler implements ServerFeaturesProvider {

    public static final String NAMESPACE = "urn:xmpp:im:search:0";
    private static final String ELEMENT = "search";
    private static final Logger Log = LoggerFactory.getLogger(IQSearchHandler.class);

    public IQSearchHandler() {
        super("IM Search Query Handler", ELEMENT, NAMESPACE);
    }

    @Override
    public IQ handleIQ(final IQ packet) {
        if (packet.getType() == IQ.Type.get) {
            return buildCapabilities(packet);
        }
        if (packet.getType() != IQ.Type.set) {
            return error(packet, PacketError.Condition.bad_request);
        }
        if (!OpenSearchIndexer.isSearchEnabled()) {
            return error(packet, PacketError.Condition.service_unavailable);
        }

        final Element search = packet.getChildElement();
        if (search == null || !NAMESPACE.equals(search.getNamespaceURI())) {
            return error(packet, PacketError.Condition.bad_request);
        }

        final String q = search.elementTextTrim("q");
        try {
            final SearchRequest.Builder builder = ModifierQueryParser.parse(q == null ? "" : q).toBuilder();

            final Element typesElement = search.element("types");
            if (typesElement != null && typesElement.getTextTrim() != null && !typesElement.getTextTrim().isEmpty()) {
                final String typeValue = typesElement.getTextTrim().toLowerCase(Locale.ROOT);
                try {
                    final SearchRequest.ResultType type = SearchRequest.ResultType.valueOf(typeValue);
                    if (type != SearchRequest.ResultType.messages) {
                        return error(packet, PacketError.Condition.feature_not_implemented);
                    }
                    builder.types(Set.of(type));
                } catch (IllegalArgumentException e) {
                    return error(packet, PacketError.Condition.bad_request);
                }
            }

            final Element sortElement = search.element("sort");
            if (sortElement != null && sortElement.getTextTrim() != null && !sortElement.getTextTrim().isEmpty()) {
                final String sortValue = sortElement.getTextTrim().toLowerCase(Locale.ROOT);
                if ("relevance".equals(sortValue)) {
                    return error(packet, PacketError.Condition.feature_not_implemented);
                }
                try {
                    final SearchRequest.Sort sort = SearchRequest.Sort.valueOf(sortValue);
                    if (sort != SearchRequest.Sort.time) {
                        return error(packet, PacketError.Condition.feature_not_implemented);
                    }
                    builder.sort(sort);
                } catch (IllegalArgumentException e) {
                    return error(packet, PacketError.Condition.bad_request);
                }
            }

            final Element setElement = search.element(QName.get("set", XmppResultSet.NAMESPACE));
            if (setElement != null) {
                final XmppResultSet rsm = new XmppResultSet(setElement);
                if (rsm.getIndex() != null) {
                    return error(packet, PacketError.Condition.feature_not_implemented);
                }
                if (rsm.getMax() != null) {
                    builder.maxResults(rsm.getMax());
                }
                builder.afterCursor(rsm.getAfter());
                builder.beforeCursor(rsm.getBefore());
                builder.pagingBackwards(rsm.isPagingBackwards());
            }

            final SearchRequest request = builder.build();
            final SearchResult result = MessageSearchService.search(packet.getFrom(), request);
            return buildResult(packet, result);
        } catch (IllegalArgumentException e) {
            Log.debug("Invalid search request from {}: {}", packet.getFrom(), e.getMessage());
            final IQ reply = error(packet, PacketError.Condition.bad_request);
            reply.getError().setText(e.getMessage());
            return reply;
        } catch (UnsupportedOperationException e) {
            return error(packet, PacketError.Condition.feature_not_implemented);
        } catch (IllegalStateException e) {
            return error(packet, PacketError.Condition.service_unavailable);
        } catch (Exception e) {
            Log.warn("Search failed for {}", packet.getFrom(), e);
            return error(packet, PacketError.Condition.internal_server_error);
        }
    }

    private IQ buildCapabilities(final IQ packet) {
        final IQ result = IQ.createResultIQ(packet);
        final Element search = result.setChildElement(ELEMENT, NAMESPACE);
        search.addElement("enabled").setText(Boolean.toString(OpenSearchIndexer.isSearchEnabled()));
        search.addElement("types").setText("messages");
        search.addElement("sort").setText("time");
        final Element modifiers = search.addElement("modifiers");
        for (final String modifier : ModifierQueryParser.SUPPORTED_MODIFIERS) {
            modifiers.addElement("modifier").setText(modifier);
        }
        return result;
    }

    private IQ buildResult(final IQ packet, final SearchResult searchResult) {
        final IQ result = IQ.createResultIQ(packet);
        final Element search = result.setChildElement(ELEMENT, NAMESPACE);
        final List<SearchHit> hits = searchResult.getHits();
        for (final SearchHit hit : hits) {
            final Element item = search.addElement("item");
            item.addAttribute("id", hit.getId());
            item.addAttribute("type", hit.getType().name());
            item.addAttribute("archive", hit.getArchive().toBareJID());
            if (hit.getFrom() != null) {
                item.addAttribute("from", hit.getFrom().toBareJID());
            }
            item.addAttribute("stamp", XMPPDateTimeFormat.format(Date.from(hit.getStamp())));
            if (hit.getSnippet() != null) {
                item.addElement("snippet").setText(hit.getSnippet());
            }
        }

        final Element set = search.addElement(QName.get("set", XmppResultSet.NAMESPACE));
        if (!hits.isEmpty()) {
            set.addElement("first").setText(hits.get(0).toCursor());
            set.addElement("last").setText(hits.get(hits.size() - 1).toCursor());
        }
        set.addElement("count").setText(Integer.toString(searchResult.getCount()));
        if (searchResult.isComplete()) {
            search.addAttribute("complete", "true");
        }
        return result;
    }

    @Override
    public Iterator<String> getFeatures() {
        if (!OpenSearchIndexer.isSearchEnabled()) {
            return List.<String>of().iterator();
        }
        return List.of(NAMESPACE).iterator();
    }
}
