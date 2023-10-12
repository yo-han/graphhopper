package com.graphhopper.gtfs;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.StopTime;
import com.conveyal.gtfs.model.Trip;
import com.graphhopper.gtfs.analysis.Trips;
import com.graphhopper.reader.osm.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

import static com.graphhopper.gtfs.analysis.Trips.TripAtStopTime.ArrivalDeparture.ARRIVAL;
import static com.graphhopper.gtfs.analysis.Trips.TripAtStopTime.print;
import static java.util.Comparator.comparingInt;

public class TripBasedRouter {

    private static final Logger logger = LoggerFactory.getLogger(TripBasedRouter.class);

    private final Trips tripTransfers;
    private GtfsStorage gtfsStorage;
    int earliestArrivalTime = Integer.MAX_VALUE;
    private int[] tripDoneFromIndex;
    private List<ResultLabel> result = new ArrayList<>();
    private Parameters parameters;

    public TripBasedRouter(GtfsStorage gtfsStorage, Trips tripTransfers) {
        this.gtfsStorage = gtfsStorage;
        this.tripTransfers = tripTransfers;
        this.tripDoneFromIndex = new int[tripTransfers.idx];
        Arrays.fill(this.tripDoneFromIndex, Integer.MAX_VALUE);
    }

    public static class StopWithTimeDelta {
        GtfsStorage.FeedIdWithStopId stopId;
        ZoneId zoneId;
        long timeDelta;

        public StopWithTimeDelta(GtfsStorage.FeedIdWithStopId stopId, ZoneId zoneId, long timeDelta) {
            this.stopId = stopId;
            this.zoneId = zoneId;
            this.timeDelta = timeDelta;
        }
    }

    public List<ResultLabel> routeNaiveProfileWithNaiveBetas(Parameters parameters) {
        for (StopWithTimeDelta accessStation : parameters.getAccessStations()) {
            Parameters newParameters = new Parameters(Collections.singletonList(accessStation), parameters.getEgressStations(), parameters.getProfileStartTime(), parameters.getProfileLength(), parameters.getTripFilter(), parameters.getBetaAccessTime(), parameters.getBetaEgressTime(), parameters.getBetaTransfers());
            routeNaiveProfile(newParameters);
        }
        return result;
    }

    public List<ResultLabel> routeNaiveProfile(Parameters parameters) {
        this.parameters = parameters;
        while (!parameters.getProfileLength().isNegative()) {
            Instant initialTime = parameters.getProfileStartTime().plus(parameters.getProfileLength());
            route(parameters.getAccessStations(), initialTime, parameters.getTripFilter());
            parameters.setProfileLength(parameters.getProfileLength().minus(Duration.ofMinutes(1)));
        }
        route(parameters.getAccessStations(), parameters.getProfileStartTime(), parameters.getTripFilter());
        return result;
    }

    public List<ResultLabel> route(List<StopWithTimeDelta> accessStations, Instant initialTime, Predicate<GTFSFeed.StopTimesForTripWithTripPatternKey> tripFilter) {
        List<EnqueuedTripSegment> queue = new ArrayList<>();
        for (StopWithTimeDelta accessStation : accessStations) {
            ZonedDateTime earliestDepartureTime = initialTime.atZone(accessStation.zoneId).plus(accessStation.timeDelta, ChronoUnit.MILLIS);
            LocalDate serviceDay = earliestDepartureTime.toLocalDate(); // FIXME service day across timezones FIXME service day wraparound
            Map<String, List<Trips.TripAtStopTime>> boardingsByPattern = tripTransfers.getPatternBoardings(accessStation.stopId);
            int targetSecondOfDay = earliestDepartureTime.toLocalTime().toSecondOfDay();
            for (List<Trips.TripAtStopTime> boardings : boardingsByPattern.values()) {
                int index = binarySearch(targetSecondOfDay, boardings, boarding -> tripTransfers.getTrip(boarding.tripIdx).stopTimes.get(boarding.stop_sequence).departure_time);
                for (int i = index; i < boardings.size(); i++) {
                    Trips.TripAtStopTime boarding = boardings.get(i);
                    GTFSFeed.StopTimesForTripWithTripPatternKey tripPointer = tripTransfers.getTrip(boarding.tripIdx);
                    if (tripPointer.service.activeOn(serviceDay) && tripFilter.test(tripPointer)) {
                        enqueue(queue, tripPointer, boarding, null, null, serviceDay, accessStation);
                        break;
                    }
                }
            }
        }
        queue.sort(comparingInt(this::getDepartureTime));
        iterate(queue);
        return result;
    }

    private static int binarySearch(int targetSecondOfDay, List<Trips.TripAtStopTime> boardings, ToIntFunction<Trips.TripAtStopTime> tripAtStopTimeToIntFunction) {
        int index = Collections.binarySearch(boardings, null, (boarding, key) -> {
            int x = tripAtStopTimeToIntFunction.applyAsInt(boarding);
            return x >= targetSecondOfDay ? 1 : -1;
        });
        return index >= 0 ? index : (- index) - 1;
    }

    private void iterate(List<EnqueuedTripSegment> queue) {
        int round = 0;
        logger.debug("Round {}: {}", round, queue.size());
        reportQueue(queue);
        checkArrivals(queue, round);
        while (queue.size() != 0 && round < 3) {
            List<EnqueuedTripSegment> queue1 = enqueueTransfers(queue);
            queue = queue1;
            round = round + 1;
            logger.debug("Round {}: {}", round, queue.size());
            reportQueue(queue);
            checkArrivals(queue, round);
        }
    }

    private void reportQueue(List<EnqueuedTripSegment> queue) {
        List<Pair<EnqueuedTripSegment, GTFSFeed.StopTimesForTripWithTripPatternKey>> pairs = queue.stream()
                .map(segment -> new Pair<>(segment, tripTransfers.getTrip(segment.tripAtStopTime.tripIdx)))
                .sorted(Comparator.comparing(p -> p.second.pattern.pattern_id))
                .collect(Collectors.toList());
        pairs.forEach(p -> {
                    EnqueuedTripSegment segment = p.first;
                    GTFSFeed.StopTimesForTripWithTripPatternKey trip = p.second;
                    logger.debug(" pattern: {}   trip: {},   stops: [{}, {}]",
                            trip.pattern.pattern_id,
                            p.first.tripPointer.trip.trip_id,
                            segment.tripAtStopTime.stop_sequence,
                            segment.toStopSequence);
                });
    }

    static class EnqueuedTripSegment {
        GTFSFeed.StopTimesForTripWithTripPatternKey tripPointer;
        Trips.TripAtStopTime tripAtStopTime;
        int toStopSequence;
        LocalDate serviceDay;
        Trips.TripAtStopTime transferOrigin;
        EnqueuedTripSegment parent;
        StopWithTimeDelta accessStation;
        int nRealTransfers;

        public EnqueuedTripSegment(GTFSFeed.StopTimesForTripWithTripPatternKey tripPointer, Trips.TripAtStopTime tripAtStopTime, int toStopSequence, LocalDate serviceDay, Trips.TripAtStopTime transferOrigin, EnqueuedTripSegment parent, StopWithTimeDelta accessStation) {
            this.tripPointer = tripPointer;
            this.tripAtStopTime = tripAtStopTime;
            this.toStopSequence = toStopSequence;
            this.serviceDay = serviceDay;
            this.transferOrigin = transferOrigin;
            this.parent = parent;
            this.accessStation = accessStation;
        }

        @Override
        public String toString() {
            return "EnqueuedTripSegment{" +
                    "tripAtStopTime=" + tripAtStopTime +
                    ", serviceDay=" + serviceDay +
                    '}';
        }
    }

    private List<EnqueuedTripSegment> enqueueTransfers(List<EnqueuedTripSegment> queue0) {
        List<EnqueuedTripSegment> queue1 = new ArrayList<>();
        for (EnqueuedTripSegment enqueuedTripSegment : queue0) {
            logger.debug("{}", enqueuedTripSegment);
            int toStopSequence = Math.min(enqueuedTripSegment.toStopSequence, enqueuedTripSegment.tripPointer.stopTimes.size());
            for (int i = enqueuedTripSegment.tripAtStopTime.stop_sequence + 1; i < toStopSequence; i++) {
                StopTime stopTime = enqueuedTripSegment.tripPointer.stopTimes.get(i);
                if (stopTime == null) continue;
                if (! (getArrivalTime(enqueuedTripSegment, stopTime, 0) < earliestArrivalTime))
                    break;
                Trips.TripAtStopTime transferOrigin = new Trips.TripAtStopTime(enqueuedTripSegment.tripPointer.idx, stopTime.stop_sequence);
                logger.debug("  {}", print(transferOrigin, tripTransfers, ARRIVAL));
                Collection<Trips.TripAtStopTime> transferDestinations = gtfsStorage.tripTransfers.getTripTransfers(enqueuedTripSegment.serviceDay).get(transferOrigin);
                for (Trips.TripAtStopTime transferDestination : transferDestinations) {
                    GTFSFeed.StopTimesForTripWithTripPatternKey destinationTripPointer = tripTransfers.getTrip(transferDestination.tripIdx);
                    StopTime transferStopTime = destinationTripPointer.stopTimes.get(transferDestination.stop_sequence);
                    if (transferStopTime.departure_time >= stopTime.arrival_time && destinationTripPointer.service.activeOn(enqueuedTripSegment.serviceDay) && parameters.getTripFilter().test(destinationTripPointer)) {
                        logger.debug("    {}", transferDestination);
                        enqueue(queue1, destinationTripPointer, transferDestination, transferOrigin, enqueuedTripSegment, enqueuedTripSegment.serviceDay, enqueuedTripSegment.accessStation);
                    }
                }
            }
        }
        return queue1;
    }

    private void checkArrivals(List<EnqueuedTripSegment> queue0, int round) {
        for (EnqueuedTripSegment enqueuedTripSegment : queue0) {
            int toStopSequence = Math.min(enqueuedTripSegment.toStopSequence, enqueuedTripSegment.tripPointer.stopTimes.size());
            for (int i = enqueuedTripSegment.tripAtStopTime.stop_sequence + 1; i < toStopSequence; i++) {
                StopTime stopTime = enqueuedTripSegment.tripPointer.stopTimes.get(i);
                if (stopTime == null) continue;
                for (StopWithTimeDelta destination : parameters.getEgressStations()) {
                    int newArrivalTime = getArrivalTime(enqueuedTripSegment, stopTime, (int) (destination.timeDelta / 1000L));
                    if (destination.stopId.stopId.equals(stopTime.stop_id) && destination.stopId.feedId.equals(enqueuedTripSegment.tripPointer.feedId) && newArrivalTime < earliestArrivalTime) {
                        earliestArrivalTime = newArrivalTime;
                        ResultLabel newResult = new ResultLabel(round, destination, enqueuedTripSegment.tripPointer.idx, stopTime.stop_sequence, enqueuedTripSegment);
                        int newRealTransfers = newResult.getRealTransfers();
                        int newDepartureTime = newResult.getDepartureTime();
                        Iterator<ResultLabel> it = result.iterator();
                        while (it.hasNext()) {
                            ResultLabel oldResult = it.next();
                            if (oldResult.getArrivalTime() < newArrivalTime) continue;
                            if (oldResult.getRealTransfers() < newRealTransfers) continue;
                            if (oldResult.getDepartureTime() > newDepartureTime) continue;
                            it.remove();
                        }
                        result.add(newResult);
                    }
                }
            }
        }
    }

    private int getArrivalTime(EnqueuedTripSegment enqueuedTripSegment, StopTime stopTime, int extraSeconds) {
        int extraDisutilityOfAccessSeconds = (int) (((long) (enqueuedTripSegment.accessStation.timeDelta * (parameters.getBetaAccessTime() - 1.0))) / 1000L);
        int extraDisutilityOfTransfersSeconds = (int) (((long) enqueuedTripSegment.nRealTransfers * parameters.getBetaTransfers()) / 1000L);
        return stopTime.arrival_time + extraDisutilityOfAccessSeconds + extraDisutilityOfTransfersSeconds + extraSeconds;
    }

    private void enqueue(List<EnqueuedTripSegment> queue1, GTFSFeed.StopTimesForTripWithTripPatternKey destinationTripPointer, Trips.TripAtStopTime transferDestination, Trips.TripAtStopTime transferOrigin, EnqueuedTripSegment parent, LocalDate serviceDay, StopWithTimeDelta accessStation) {
        int thisTripDoneFromIndex = tripDoneFromIndex[destinationTripPointer.idx];
        if (transferDestination.stop_sequence < thisTripDoneFromIndex) {
            if (transferDestination.stop_sequence + 1 < thisTripDoneFromIndex) {
                EnqueuedTripSegment enqueuedTripSegment = new EnqueuedTripSegment(destinationTripPointer, transferDestination, thisTripDoneFromIndex, serviceDay, transferOrigin, parent, accessStation);
                if (parent != null) {
                    enqueuedTripSegment.nRealTransfers = parent.nRealTransfers + 1;
                }
                queue1.add(enqueuedTripSegment);
            }
            markAsDone(destinationTripPointer, transferDestination.stop_sequence);
        }
    }

    private void markAsDone(GTFSFeed.StopTimesForTripWithTripPatternKey destinationTripPointer, int doneFromIndex) {
        for (int i = destinationTripPointer.idx; i < destinationTripPointer.endIdxOfPattern; i++) {
            // Trips within a pattern are sorted by start time. All that come after me can be marked as done.
            int previousDoneFromIndex = tripDoneFromIndex[i];
            if (doneFromIndex < previousDoneFromIndex)
                tripDoneFromIndex[i] = doneFromIndex;
            else
                break;
        }
    }

    public class ResultLabel {
        private final int round;
        public final StopWithTimeDelta destination;
        private final int tripIdx;
        public final int stopTime;
        public EnqueuedTripSegment enqueuedTripSegment;

        public ResultLabel(int round, StopWithTimeDelta destination, int tripIdx, int stop_time, EnqueuedTripSegment enqueuedTripSegment) {
            this.round = round;
            this.destination = destination;
            this.tripIdx = tripIdx;
            stopTime = stop_time;
            this.enqueuedTripSegment = enqueuedTripSegment;
        }

        @Override
        public String toString() {
            StopTime stopTime = getStopTime();
            return String.format("%s+%d %s", LocalTime.ofSecondOfDay(stopTime.arrival_time % (60 * 60 * 24)), stopTime.arrival_time / (60 * 60 * 24), stopTime.stop_id);
        }

        private StopTime getStopTime() {
            List<StopTime> stopTimes = tripTransfers.getTrip(tripIdx).stopTimes;
            return stopTimes.get(stopTime);
        }

        int getDepartureTime() {
            return TripBasedRouter.this.getDepartureTime(enqueuedTripSegment);
        }

        public StopWithTimeDelta getAccessStop() {
            EnqueuedTripSegment i = enqueuedTripSegment;
            while (i.parent != null)
                i = i.parent;
            return i.accessStation;
        }

        int getArrivalTime() {
            return TripBasedRouter.this.getArrivalTime(enqueuedTripSegment, getStopTime(), (int) ((destination.timeDelta / 1000L) * parameters.getBetaEgressTime()));
        }

        public int getRound() {
            return round;
        }

        public int getRealTransfers() {
            int result = 0;
            EnqueuedTripSegment i = enqueuedTripSegment;
            while (i.parent != null) {
                Trip trip1 = tripTransfers.getTrip(i.tripAtStopTime.tripIdx).trip;
                Trip trip2 = tripTransfers.getTrip(i.transferOrigin.tripIdx).trip;
                if (trip1.block_id == null || trip2.block_id == null || !trip1.block_id.equals(trip2.block_id)) {
                    result = result + 1;
                }
                i = i.parent;
            }
            return result;
        }
    }

    private int getDepartureTime(EnqueuedTripSegment i) {
        while (i.parent != null)
            i = i.parent;
        List<StopTime> stopTimes = tripTransfers.getTrip(i.tripPointer.idx).stopTimes;
        StopTime stopTime = stopTimes.get(i.tripAtStopTime.stop_sequence);
        return stopTime.departure_time;
    }

    static class Parameters {
        private final List<StopWithTimeDelta> accessStations;
        private final List<StopWithTimeDelta> egressStations;
        private final Instant profileStartTime;
        private Duration profileLength;
        private final Predicate<GTFSFeed.StopTimesForTripWithTripPatternKey> tripFilter;
        private final double betaAccessTime;
        private final double betaEgressTime;
        private final double betaTransfers;

        Parameters(List<StopWithTimeDelta> accessStations, List<StopWithTimeDelta> egressStations, Instant profileStartTime, Duration profileLength, Predicate<GTFSFeed.StopTimesForTripWithTripPatternKey> tripFilter, double betaAccessTime, double betaEgressTime, double betaTransfers) {
            this.accessStations = accessStations;
            this.egressStations = egressStations;
            this.profileStartTime = profileStartTime;
            this.profileLength = profileLength;
            this.tripFilter = tripFilter;
            this.betaAccessTime = betaAccessTime;
            this.betaEgressTime = betaEgressTime;
            this.betaTransfers = betaTransfers;
        }

        public List<StopWithTimeDelta> getAccessStations() {
            return accessStations;
        }

        public List<StopWithTimeDelta> getEgressStations() {
            return egressStations;
        }

        public Instant getProfileStartTime() {
            return profileStartTime;
        }

        public Duration getProfileLength() {
            return profileLength;
        }

        public Predicate<GTFSFeed.StopTimesForTripWithTripPatternKey> getTripFilter() {
            return tripFilter;
        }

        public void setProfileLength(Duration profileLength) {
            this.profileLength = profileLength;
        }

        public double getBetaAccessTime() {
            return betaAccessTime;
        }

        public double getBetaEgressTime() {
            return betaEgressTime;
        }

        public double getBetaTransfers() {
            return betaTransfers;
        }
    }
}