/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.graphhopper.gtfs;

import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntLongHashMap;
import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.Frequency;
import com.conveyal.gtfs.model.StopTime;
import com.conveyal.gtfs.model.Trip;
import com.google.transit.realtime.GtfsRealtime;
import com.graphhopper.routing.querygraph.VirtualEdgeIteratorState;
import com.graphhopper.storage.GraphHopperStorage;
import org.mapdb.Fun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship.NO_DATA;
import static com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship.SKIPPED;
import static java.time.temporal.ChronoUnit.DAYS;

public class RealtimeFeed {
    private static final Logger logger = LoggerFactory.getLogger(RealtimeFeed.class);
    private final IntHashSet blockedEdges;
    private final IntLongHashMap delaysForBoardEdges;
    private final IntLongHashMap delaysForAlightEdges;
    private final List<PtGraph.PtEdge> additionalEdges;
    public final Map<String, GtfsRealtime.FeedMessage> feedMessages;
    private final GtfsStorage staticGtfs;
    private final Map<Integer, byte[]> additionalTripDescriptors;
    private final Map<Integer, Integer> stopSequences;
    private final Map<Integer, GtfsStorageI.PlatformDescriptor> platformDescriptorByEdge;

    private RealtimeFeed(GtfsStorage staticGtfs, Map<String, GtfsRealtime.FeedMessage> feedMessages, IntHashSet blockedEdges,
                         IntLongHashMap delaysForBoardEdges, IntLongHashMap delaysForAlightEdges, List<PtGraph.PtEdge> additionalEdges, Map<Integer, byte[]> tripDescriptors, Map<Integer, Integer> stopSequences, Map<Integer, GtfsStorageI.PlatformDescriptor> platformDescriptorByEdge) {
        this.staticGtfs = staticGtfs;
        this.feedMessages = feedMessages;
        this.blockedEdges = blockedEdges;
        this.delaysForBoardEdges = delaysForBoardEdges;
        this.delaysForAlightEdges = delaysForAlightEdges;
        this.additionalEdges = additionalEdges;
        this.additionalTripDescriptors = tripDescriptors;
        this.stopSequences = stopSequences;
        this.platformDescriptorByEdge = platformDescriptorByEdge;
    }

    public static RealtimeFeed empty(GtfsStorage staticGtfs) {
        return new RealtimeFeed(staticGtfs, Collections.emptyMap(), new IntHashSet(), new IntLongHashMap(), new IntLongHashMap(), Collections.emptyList(), Collections.emptyMap(), Collections.emptyMap(), staticGtfs.getPlatformDescriptorByEdge());
    }

    public static RealtimeFeed fromProtobuf(GraphHopperStorage graphHopperStorage, GtfsStorage staticGtfs, Map<String, Transfers> transfers, Map<String, GtfsRealtime.FeedMessage> feedMessages) {
        final IntHashSet blockedEdges = new IntHashSet();
        final IntLongHashMap delaysForBoardEdges = new IntLongHashMap();
        final IntLongHashMap delaysForAlightEdges = new IntLongHashMap();
        final LinkedList<PtGraph.PtEdge> additionalEdges = new LinkedList<>();
        final GtfsReader.PtGraphOut overlayGraph = new GtfsReader.PtGraphOut() {
            int pups = 3000000;
            int wurst = 30000000;
            @Override
            public void putPlatformNode(int platformEnterNode, GtfsStorageI.PlatformDescriptor platformDescriptor) {

            }

            @Override
            public int createEdge(int src, int dest, PtEdgeAttributes attrs) {
                int edgeId = pups++;
                additionalEdges.add(new PtGraph.PtEdge(edgeId, src, dest, attrs));
                return edgeId;
            }

            @Override
            public int createNode() {
                return wurst++;
            }
        };

        Map<Integer, byte[]> tripDescriptors = new HashMap<>();
        Map<Integer, Integer> stopSequences = new HashMap<>();
        Map<String, int[]> boardEdgesForTrip = new HashMap<>();
        Map<String, int[]> alightEdgesForTrip = new HashMap<>();
        Map<Integer, GtfsStorageI.PlatformDescriptor> platformDescriptorByEdge = new HashMap<>(staticGtfs.getPlatformDescriptorByEdge()); // FIXME: Too slow for production

        feedMessages.forEach((feedKey, feedMessage) -> {
            GTFSFeed feed = staticGtfs.getGtfsFeeds().get(feedKey);
            ZoneId timezone = ZoneId.of(feed.agency.values().stream().findFirst().get().agency_timezone);
            PtGraph ptGraphNodesAndEdges = staticGtfs.getPtGraph();
            final GtfsReader gtfsReader = new GtfsReader(feedKey, graphHopperStorage, ptGraphNodesAndEdges, overlayGraph, staticGtfs, null, transfers.get(feedKey), null);
            Instant timestamp = Instant.ofEpochSecond(feedMessage.getHeader().getTimestamp());
            LocalDate dateToChange = timestamp.atZone(timezone).toLocalDate(); //FIXME
            BitSet validOnDay = new BitSet();
            LocalDate startDate = feed.getStartDate();
            validOnDay.set((int) DAYS.between(startDate, dateToChange));
            feedMessage.getEntityList().stream()
                    .filter(GtfsRealtime.FeedEntity::hasTripUpdate)
                    .map(GtfsRealtime.FeedEntity::getTripUpdate)
                    .filter(tripUpdate -> tripUpdate.getTrip().getScheduleRelationship() == GtfsRealtime.TripDescriptor.ScheduleRelationship.SCHEDULED)
                    .forEach(tripUpdate -> {
                        Collection<Frequency> frequencies = feed.getFrequencies(tripUpdate.getTrip().getTripId());
                        int timeOffset = (tripUpdate.getTrip().hasStartTime() && !frequencies.isEmpty()) ? LocalTime.parse(tripUpdate.getTrip().getStartTime()).toSecondOfDay() : 0;
                        String key = GtfsStorage.tripKey(tripUpdate.getTrip(), !frequencies.isEmpty());
                        final int[] boardEdges = staticGtfs.getBoardEdgesForTrip().get(key);
                        final int[] leaveEdges = staticGtfs.getAlightEdgesForTrip().get(key);
                        if (boardEdges == null || leaveEdges == null) {
                            logger.warn("Trip not found: {}", tripUpdate.getTrip());
                            return;
                        }
                        tripUpdate.getStopTimeUpdateList().stream()
                                .filter(stopTimeUpdate -> stopTimeUpdate.getScheduleRelationship() == SKIPPED)
                                .mapToInt(GtfsRealtime.TripUpdate.StopTimeUpdate::getStopSequence)
                                .forEach(skippedStopSequenceNumber -> {
                                    blockedEdges.add(boardEdges[skippedStopSequenceNumber]);
                                    blockedEdges.add(leaveEdges[skippedStopSequenceNumber]);
                                });
                        GtfsReader.TripWithStopTimes tripWithStopTimes = toTripWithStopTimes(feed, tripUpdate);
                        tripWithStopTimes.stopTimes.forEach(stopTime -> {
                            if (stopTime.stop_sequence > leaveEdges.length - 1) {
                                logger.warn("Stop sequence number too high {} vs {}", stopTime.stop_sequence, leaveEdges.length);
                                return;
                            }
                            final StopTime originalStopTime = feed.stop_times.get(new Fun.Tuple2(tripUpdate.getTrip().getTripId(), stopTime.stop_sequence));
                            int arrivalDelay = stopTime.arrival_time - originalStopTime.arrival_time;
                            delaysForAlightEdges.put(leaveEdges[stopTime.stop_sequence], arrivalDelay * 1000);
                            int departureDelay = stopTime.departure_time - originalStopTime.departure_time;
                            if (departureDelay > 0) {
                                int boardEdge = boardEdges[stopTime.stop_sequence];
                                int departureNode = ptGraphNodesAndEdges.edge(boardEdge).getAdjNode();
                                int delayedBoardEdge = gtfsReader.addDelayedBoardEdge(timezone, tripUpdate.getTrip(), stopTime.stop_sequence, stopTime.departure_time + timeOffset, departureNode, validOnDay);
                                delaysForBoardEdges.put(delayedBoardEdge, departureDelay * 1000);
                            }
                        });
                    });
            feedMessage.getEntityList().stream()
                    .filter(GtfsRealtime.FeedEntity::hasTripUpdate)
                    .map(GtfsRealtime.FeedEntity::getTripUpdate)
                    .filter(tripUpdate -> tripUpdate.getTrip().getScheduleRelationship() == GtfsRealtime.TripDescriptor.ScheduleRelationship.ADDED)
                    .forEach(tripUpdate -> {
                        Trip trip = new Trip();
                        trip.trip_id = tripUpdate.getTrip().getTripId();
                        trip.route_id = tripUpdate.getTrip().getRouteId();
                        final List<StopTime> stopTimes = tripUpdate.getStopTimeUpdateList().stream()
                                .map(stopTimeUpdate -> {
                                    final StopTime stopTime = new StopTime();
                                    stopTime.stop_sequence = stopTimeUpdate.getStopSequence();
                                    stopTime.stop_id = stopTimeUpdate.getStopId();
                                    stopTime.trip_id = trip.trip_id;
                                    final ZonedDateTime arrival_time = Instant.ofEpochSecond(stopTimeUpdate.getArrival().getTime()).atZone(timezone);
                                    stopTime.arrival_time = (int) Duration.between(arrival_time.truncatedTo(ChronoUnit.DAYS), arrival_time).getSeconds();
                                    final ZonedDateTime departure_time = Instant.ofEpochSecond(stopTimeUpdate.getArrival().getTime()).atZone(timezone);
                                    stopTime.departure_time = (int) Duration.between(departure_time.truncatedTo(ChronoUnit.DAYS), departure_time).getSeconds();
                                    return stopTime;
                                })
                                .collect(Collectors.toList());
                        GtfsReader.TripWithStopTimes tripWithStopTimes = new GtfsReader.TripWithStopTimes(trip, stopTimes, validOnDay, Collections.emptySet(), Collections.emptySet());
                        gtfsReader.addTrip(timezone, 0, new ArrayList<>(), tripWithStopTimes, tripUpdate.getTrip(), false);
                    });
            gtfsReader.wireUpAdditionalDeparturesAndArrivals(timezone);
        });

        return new RealtimeFeed(staticGtfs, feedMessages, blockedEdges, delaysForBoardEdges, delaysForAlightEdges, additionalEdges, tripDescriptors, stopSequences, platformDescriptorByEdge);
    }

    boolean isBlocked(int edgeId) {
        return blockedEdges.contains(edgeId);
    }

    List<PtGraph.PtEdge> getAdditionalEdges() {
        return additionalEdges;
    }

    public Optional<GtfsReader.TripWithStopTimes> getTripUpdate(GTFSFeed staticFeed, GtfsRealtime.TripDescriptor tripDescriptor, Instant boardTime) {
        try {
            logger.trace("getTripUpdate {}", tripDescriptor);
            if (!isThisRealtimeUpdateAboutThisLineRun(boardTime)) {
                return Optional.empty();
            } else {
                GtfsRealtime.TripDescriptor normalizedTripDescriptor = normalize(tripDescriptor);
                return feedMessages.values().stream().flatMap(feedMessage -> feedMessage.getEntityList().stream()
                        .filter(e -> e.hasTripUpdate())
                        .map(e -> e.getTripUpdate())
                        .filter(tu -> normalize(tu.getTrip()).equals(normalizedTripDescriptor))
                        .map(tu -> toTripWithStopTimes(staticFeed, tu)))
                        .findFirst();
            }
        } catch (RuntimeException e) {
            feedMessages.forEach((name, feed) -> {
                try (OutputStream s = new FileOutputStream(name+".gtfsdump")) {
                    feed.writeTo(s);
                } catch (IOException e1) {
                    throw new RuntimeException();
                }
            });
            return Optional.empty();
        }
    }

    public GtfsRealtime.TripDescriptor normalize(GtfsRealtime.TripDescriptor tripDescriptor) {
        return GtfsRealtime.TripDescriptor.newBuilder(tripDescriptor).clearRouteId().build();
    }

    public static GtfsReader.TripWithStopTimes toTripWithStopTimes(GTFSFeed feed, GtfsRealtime.TripUpdate tripUpdate) {
        ZoneId timezone = ZoneId.of(feed.agency.values().stream().findFirst().get().agency_timezone);
        logger.trace("{}", tripUpdate.getTrip());
        final List<StopTime> stopTimes = new ArrayList<>();
        Set<Integer> cancelledArrivals = new HashSet<>();
        Set<Integer> cancelledDepartures = new HashSet<>();
        Trip originalTrip = feed.trips.get(tripUpdate.getTrip().getTripId());
        Trip trip = new Trip();
        if (originalTrip != null) {
            trip.trip_id = originalTrip.trip_id;
            trip.route_id = originalTrip.route_id;
        } else {
            trip.trip_id = tripUpdate.getTrip().getTripId();
            trip.route_id = tripUpdate.getTrip().getRouteId();
        }
        int delay = 0;
        int time = -1;
        List<GtfsRealtime.TripUpdate.StopTimeUpdate> stopTimeUpdateListWithSentinel = new ArrayList<>(tripUpdate.getStopTimeUpdateList());
        Iterable<StopTime> interpolatedStopTimesForTrip;
        try {
            interpolatedStopTimesForTrip = feed.getInterpolatedStopTimesForTrip(tripUpdate.getTrip().getTripId());
        } catch (GTFSFeed.FirstAndLastStopsDoNotHaveTimes firstAndLastStopsDoNotHaveTimes) {
            throw new RuntimeException(firstAndLastStopsDoNotHaveTimes);
        }
        int stopSequenceCeiling = Math.max(stopTimeUpdateListWithSentinel.isEmpty() ? 0 : stopTimeUpdateListWithSentinel.get(stopTimeUpdateListWithSentinel.size() - 1).getStopSequence(),
                StreamSupport.stream(interpolatedStopTimesForTrip.spliterator(), false).mapToInt(stopTime -> stopTime.stop_sequence).max().orElse(0)
        ) + 1;
        stopTimeUpdateListWithSentinel.add(GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder().setStopSequence(stopSequenceCeiling).setScheduleRelationship(NO_DATA).build());
        for (GtfsRealtime.TripUpdate.StopTimeUpdate stopTimeUpdate : stopTimeUpdateListWithSentinel) {
            int nextStopSequence = stopTimes.isEmpty() ? 1 : stopTimes.get(stopTimes.size() - 1).stop_sequence + 1;
            for (int i = nextStopSequence; i < stopTimeUpdate.getStopSequence(); i++) {
                StopTime previousOriginalStopTime = feed.stop_times.get(new Fun.Tuple2(tripUpdate.getTrip().getTripId(), i));
                if (previousOriginalStopTime == null) {
                    continue; // This can and does happen. Stop sequence numbers can be left out.
                }
                StopTime updatedPreviousStopTime = previousOriginalStopTime.clone();
                updatedPreviousStopTime.arrival_time = Math.max(previousOriginalStopTime.arrival_time + delay, time);
                logger.trace("stop_sequence {} scheduled arrival {} updated arrival {}", i, previousOriginalStopTime.arrival_time, updatedPreviousStopTime.arrival_time);
                time = updatedPreviousStopTime.arrival_time;
                updatedPreviousStopTime.departure_time = Math.max(previousOriginalStopTime.departure_time + delay, time);
                logger.trace("stop_sequence {} scheduled departure {} updated departure {}", i, previousOriginalStopTime.departure_time, updatedPreviousStopTime.departure_time);
                time = updatedPreviousStopTime.departure_time;
                stopTimes.add(updatedPreviousStopTime);
                logger.trace("Number of stop times: {}", stopTimes.size());
            }

            final StopTime originalStopTime = feed.stop_times.get(new Fun.Tuple2(tripUpdate.getTrip().getTripId(), stopTimeUpdate.getStopSequence()));
            if (originalStopTime != null) {
                StopTime updatedStopTime = originalStopTime.clone();
                if (stopTimeUpdate.getScheduleRelationship() == NO_DATA) {
                    delay = 0;
                }
                if (stopTimeUpdate.hasArrival()) {
                    delay = stopTimeUpdate.getArrival().getDelay();
                }
                updatedStopTime.arrival_time = Math.max(originalStopTime.arrival_time + delay, time);
                logger.trace("stop_sequence {} scheduled arrival {} updated arrival {}", stopTimeUpdate.getStopSequence(), originalStopTime.arrival_time, updatedStopTime.arrival_time);
                time = updatedStopTime.arrival_time;
                if (stopTimeUpdate.hasDeparture()) {
                    delay = stopTimeUpdate.getDeparture().getDelay();
                }
                updatedStopTime.departure_time = Math.max(originalStopTime.departure_time + delay, time);
                logger.trace("stop_sequence {} scheduled departure {} updated departure {}", stopTimeUpdate.getStopSequence(), originalStopTime.departure_time, updatedStopTime.departure_time);
                time = updatedStopTime.departure_time;
                stopTimes.add(updatedStopTime);
                logger.trace("Number of stop times: {}", stopTimes.size());
                if (stopTimeUpdate.getScheduleRelationship() == SKIPPED) {
                    cancelledArrivals.add(stopTimeUpdate.getStopSequence());
                    cancelledDepartures.add(stopTimeUpdate.getStopSequence());
                }
            } else if (stopTimeUpdate.getScheduleRelationship() == NO_DATA) {
            } else if (tripUpdate.getTrip().getScheduleRelationship() == GtfsRealtime.TripDescriptor.ScheduleRelationship.ADDED) {
                final StopTime stopTime = new StopTime();
                stopTime.stop_sequence = stopTimeUpdate.getStopSequence();
                stopTime.stop_id = stopTimeUpdate.getStopId();
                stopTime.trip_id = trip.trip_id;
                final ZonedDateTime arrival_time = Instant.ofEpochSecond(stopTimeUpdate.getArrival().getTime()).atZone(timezone);
                stopTime.arrival_time = (int) Duration.between(arrival_time.truncatedTo(ChronoUnit.DAYS), arrival_time).getSeconds();
                final ZonedDateTime departure_time = Instant.ofEpochSecond(stopTimeUpdate.getArrival().getTime()).atZone(timezone);
                stopTime.departure_time = (int) Duration.between(departure_time.truncatedTo(ChronoUnit.DAYS), departure_time).getSeconds();
                stopTimes.add(stopTime);
                logger.trace("Number of stop times: {}", stopTimes.size());
            } else {
                // http://localhost:3000/route?point=45.51043713898763%2C-122.68381118774415&point=45.522104713562825%2C-122.6455307006836&weighting=fastest&pt.earliest_departure_time=2018-08-24T16%3A56%3A17Z&arrive_by=false&pt.max_walk_distance_per_leg=1000&pt.limit_solutions=5&locale=en-US&vehicle=pt&elevation=false&use_miles=false&points_encoded=false&pt.profile=true
                // long query:
                // http://localhost:3000/route?point=45.518526513612244%2C-122.68612861633302&point=45.52908004573869%2C-122.6862144470215&weighting=fastest&pt.earliest_departure_time=2018-08-24T16%3A51%3A20Z&arrive_by=false&pt.max_walk_distance_per_leg=10000&pt.limit_solutions=4&locale=en-US&vehicle=pt&elevation=false&use_miles=false&points_encoded=false&pt.profile=true
                throw new RuntimeException();
            }
        }
        logger.trace("Number of stop times: {}", stopTimes.size());
        BitSet validOnDay = new BitSet(); // Not valid on any day. Just a template.

        return new GtfsReader.TripWithStopTimes(trip, stopTimes, validOnDay, cancelledArrivals, cancelledDepartures);
    }

    public long getDelayForBoardEdge(PtGraph.PtEdge edge, Instant now) {
        if (isThisRealtimeUpdateAboutThisLineRun(now)) {
            return delaysForBoardEdges.getOrDefault(edge.getId(), 0);
        } else {
            return 0;
        }
    }

    public long getDelayForAlightEdge(PtGraph.PtEdge edge, Instant now) {
        if (isThisRealtimeUpdateAboutThisLineRun(now)) {
            return delaysForAlightEdges.getOrDefault(edge.getId(), 0);
        } else {
            return 0;
        }
    }

    boolean isThisRealtimeUpdateAboutThisLineRun(Instant now) {
        if (Duration.between(feedTimestampOrNow(), now).toHours() > 24) {
            return false;
        } else {
            return true;
        }
    }

    private Instant feedTimestampOrNow() {
        return feedMessages.values().stream().map(feedMessage -> {
            if (feedMessage.getHeader().hasTimestamp()) {
                return Instant.ofEpochSecond(feedMessage.getHeader().getTimestamp());
            } else {
                return Instant.now();
            }
        }).findFirst().orElse(Instant.now());
    }

    public byte[] getTripDescriptor(int edge) {
        return staticGtfs.getTripDescriptors().getOrDefault(edge, additionalTripDescriptors.get(edge));
    }

    public int getStopSequence(int edge) {
        return staticGtfs.getStopSequences().getOrDefault(edge, stopSequences.get(edge));
    }

    public StopTime getStopTime(GTFSFeed staticFeed, GtfsRealtime.TripDescriptor tripDescriptor, Label.Transition t, Instant boardTime, int stopSequence) {
        StopTime stopTime = staticFeed.stop_times.get(new Fun.Tuple2<>(tripDescriptor.getTripId(), stopSequence));
        if (stopTime == null) {
            return getTripUpdate(staticFeed, tripDescriptor, boardTime).get().stopTimes.get(stopSequence - 1);
        } else {
            return stopTime;
        }
    }

    public Map<Integer, GtfsStorageI.PlatformDescriptor> getPlatformDescriptorByEdge() {
        return platformDescriptorByEdge;
    }

}
