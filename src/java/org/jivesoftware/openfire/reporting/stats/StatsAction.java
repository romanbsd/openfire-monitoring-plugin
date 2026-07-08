/*
 * Copyright (C) 2008 Jive Software, Ignite Realtime Foundation 2025. All rights reserved.
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
package org.jivesoftware.openfire.reporting.stats;

import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.archive.Conversation;
import org.jivesoftware.openfire.archive.ConversationManager;
import org.jivesoftware.openfire.archive.MonitoringConstants;
import org.jivesoftware.openfire.plugin.MonitoringPlugin;
import org.jivesoftware.openfire.reporting.graph.GraphEngine;
import org.jivesoftware.openfire.stats.Statistic;
import org.jivesoftware.util.JiveGlobals;

import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Provides the server side callbacks for client side JavaScript functions for
 * the stats dashboard page.
 *
 * @author Aaron Johnson
 */
public class StatsAction {

    /**
     * Retrieves a map containing the high / low and current count statistics
     * for the 'sessions', 'conversations' and 'packet_count' statistics.
     * @return map containing 3 maps (keys = 'sessions, 'conversations' and
     * 'packet_count') each containing an array of int (low value, high value
     * and current value).
     */
    public Map<String, Map<String, Object>> getUpdatedStats(String timePeriod) {
        Map<String, Map<String, Object>> results = new HashMap<>();
        long[] startAndEnd = GraphEngine.parseTimePeriod(timePeriod);
        String[] stats = new String[] {
            StatisticsModule.SESSIONS_KEY, ConversationManager.CONVERSATIONS_KEY, StatisticsModule.TRAFFIC_KEY,
                "proxy_transfer_amt", "muc_rooms_amt", StatisticsModule.SERVER_2_SERVER_SESSIONS_KEY, "server_bytes_amt"};
        for (String stat : stats) {
            results.put(stat, getUpdatedStat(stat, startAndEnd));
        }
        return results;
    }

    /**
     * Retrieve a a single stat update given a stat name and the name of a
     * time period.
     * @return map containing keys 'low', 'high' and 'count'.
     */
    public Map<String, Object> getUpdatedStat(String statkey, String timePeriod) {
        long[] startAndEnd = GraphEngine.parseTimePeriod(timePeriod);
        return getUpdatedStat(statkey, startAndEnd);
    }

    private Map<String, Object> getUpdatedStat(String statkey, long[] timePeriod) {
        MonitoringPlugin plugin = (MonitoringPlugin)XMPPServer.getInstance().getPluginManager().getPluginByName(MonitoringConstants.PLUGIN_NAME).get();
        StatsViewer viewer = plugin.getStatsViewer();
        String[] lowHigh = getLowAndHigh(statkey, timePeriod);
        Map<String, Object> stat = new HashMap<>();
        stat.put("low", lowHigh[0]);
        stat.put("high", lowHigh[1]);
        stat.put("count", (int)viewer.getCurrentValue(statkey)[0]);
        return stat;
    }

    /**
     * Given a statistic key and a start date, end date and number of datapoints, returns
     * a String[] containing the low and high values (in that order) for the given time period.
     * 
     * @param key the name of the statistic to return high and low values for.
     * @param timePeriod start date, end date and number of data points.
     * @return low and high values for the given time period / number of datapoints
     */
    public static String[] getLowAndHigh(String key,  long[] timePeriod) {
        MonitoringPlugin plugin = (MonitoringPlugin)XMPPServer.getInstance().getPluginManager().getPluginByName(MonitoringConstants.PLUGIN_NAME).get();
        StatsViewer viewer = plugin.getStatsViewer();
        Statistic.RepresentationSemantics representationSemantics = viewer.getStatistic(key)[0].getRepresentationSemantics();
        double[] lows = viewer.getMin(key, timePeriod[0], timePeriod[1], (int)timePeriod[2]);
        double[] highs = viewer.getMax(key, timePeriod[0], timePeriod[1], (int)timePeriod[2]);
        String low;
        NumberFormat format = NumberFormat.getNumberInstance();
        format.setMaximumFractionDigits(0);
        if(lows.length > 0) {
            if (representationSemantics == Statistic.RepresentationSemantics.SNAPSHOT) {
                double result = 0;
                for (double v : lows) {
                    result += v;
                }
                low = String.valueOf((int) result);
            }
            else {
                double l = 0;
                for (int i = 0; i < lows.length; i++ ) {
                        if(Double.isNaN(lows[i])) {
                                lows[i] = 0;
                        }
                        l += lows[i];
                }
                low = format.format(l);
            }
        }
        else {
            low = String.valueOf(0);
        }
        String high;
        if(highs.length > 0) {
            if (representationSemantics == Statistic.RepresentationSemantics.SNAPSHOT) {
                double result = 0;
                for (double v : highs) {
                    result += v;
                }
                high = String.valueOf((int) result);
            }
            else {
                double h= 0;
                for (int i = 0; i < highs.length; i++) {
                        if(Double.isNaN(highs[i])) {
                                highs[i] = 0;
                        }
                        h += highs[i];
                }
                high = format.format(h);
            }

        }
        else {
            high = String.valueOf(0);
        }

        return new String[]{low, high};
    }

    /**
     * Formats a given time using the <code>DateFormat.MEDIUM</code>. In the 'en' locale, this
     * should result in a time formatted like this: 4:59:23 PM. The seconds are necessary when
     * displaying time in the conversation scroller.
     * @return string a date formatted using DateFormat.MEDIUM
     */
    public static String formatTimeLong(Date time) {
        DateFormat formatter = DateFormat.getTimeInstance(DateFormat.MEDIUM, JiveGlobals.getLocale());
        formatter.setTimeZone(JiveGlobals.getTimeZone());
        return formatter.format(time);
    }

}
