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
package com.graphhopper;

import com.bedatadriven.jackson.datatype.jts.JtsModule;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graphhopper.config.CHProfile;
import com.graphhopper.config.LMProfile;
import com.graphhopper.config.Profile;
import com.graphhopper.jackson.Jackson;
import com.graphhopper.reader.dem.*;
import com.graphhopper.reader.osm.OSMReader;
import com.graphhopper.reader.osm.PrepareDeadEnds;
import com.graphhopper.reader.osm.RestrictionTagParser;
import com.graphhopper.reader.osm.conditional.DateRangeParser;
import com.graphhopper.routing.*;
import com.graphhopper.routing.ch.CHPreparationHandler;
import com.graphhopper.routing.ch.PrepareContractionHierarchies;
import com.graphhopper.routing.ev.*;
import com.graphhopper.routing.lm.LMConfig;
import com.graphhopper.routing.lm.LMPreparationHandler;
import com.graphhopper.routing.lm.LandmarkStorage;
import com.graphhopper.routing.lm.PrepareLandmarks;
import com.graphhopper.routing.subnetwork.PrepareRoutingSubnetworks;
import com.graphhopper.routing.subnetwork.PrepareRoutingSubnetworks.PrepareJob;
import com.graphhopper.routing.util.*;
import com.graphhopper.routing.util.countryrules.CountryRuleFactory;
import com.graphhopper.routing.util.parsers.*;
import com.graphhopper.routing.weighting.AbstractAdjustedWeighting;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.routing.weighting.custom.CustomWeighting;
import com.graphhopper.storage.*;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.LocationIndexTree;
import com.graphhopper.util.*;
import com.graphhopper.util.Parameters.Landmark;
import com.graphhopper.util.Parameters.Routing;
import com.graphhopper.util.details.PathDetailsBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.util.*;
import java.util.stream.Collectors;

import static com.graphhopper.util.GHUtility.readCountries;
import static com.graphhopper.util.Helper.*;
import static com.graphhopper.util.Parameters.Algorithms.RoundTrip;

/**
 * Easy to use access point to configure import and (offline) routing.
 *
 * @author Peter Karich
 */
public class GraphHopper {
    private static final Logger logger = LoggerFactory.getLogger(GraphHopper.class);
    private MaxSpeedCalculator maxSpeedCalculator;
    private final Map<String, Profile> profilesByName = new LinkedHashMap<>();
    private final String fileLockName = "gh.lock";
    // utils
    private final TranslationMap trMap = new TranslationMap().doImport();
    boolean removeZipped = true;
    // for country rules:
    private CountryRuleFactory countryRuleFactory = null;
    // for custom areas:
    private String customAreasDirectory = "";
    // for graph:
    private BaseGraph baseGraph;
    private StorableProperties properties;
    private EncodingManager encodingManager;
    private OSMParsers osmParsers;
    private int defaultSegmentSize = -1;
    private String ghLocation = "";
    private DAType dataAccessDefaultType = DAType.RAM_STORE;
    private final LinkedHashMap<String, String> dataAccessConfig = new LinkedHashMap<>();
    private boolean sortGraph = false;
    private boolean elevation = false;
    private LockFactory lockFactory = new NativeFSLockFactory();
    private boolean allowWrites = true;
    private boolean fullyLoaded = false;
    private final OSMReaderConfig osmReaderConfig = new OSMReaderConfig();
    // for routing
    private final RouterConfig routerConfig = new RouterConfig();
    // for index
    private LocationIndex locationIndex;
    private int preciseIndexResolution = 300;
    private int maxRegionSearch = 4;
    // subnetworks
    private int minNetworkSize = 200;
    private int subnetworksThreads = 1;
    // residential areas
    private double residentialAreaRadius = 400;
    private double residentialAreaSensitivity = 6000;
    private double cityAreaRadius = 1500;
    private double cityAreaSensitivity = 1000;
    private int urbanDensityCalculationThreads = 0;

    // preparation handlers
    private final LMPreparationHandler lmPreparationHandler = new LMPreparationHandler();
    private final CHPreparationHandler chPreparationHandler = new CHPreparationHandler();
    private Map<String, RoutingCHGraph> chGraphs = Collections.emptyMap();
    private Map<String, LandmarkStorage> landmarks = Collections.emptyMap();

    // for data reader
    private String osmFile;
    private ElevationProvider eleProvider = ElevationProvider.NOOP;
    private VehicleEncodedValuesFactory vehicleEncodedValuesFactory = new DefaultVehicleEncodedValuesFactory();
    private VehicleTagParserFactory vehicleTagParserFactory = new DefaultVehicleTagParserFactory();
    private EncodedValueFactory encodedValueFactory = new DefaultEncodedValueFactory();
    private TagParserFactory tagParserFactory = new DefaultTagParserFactory();
    private PathDetailsBuilderFactory pathBuilderFactory = new PathDetailsBuilderFactory();

    private String dateRangeParserString = "";
    private String encodedValuesString = "";
    private String vehiclesString = "";

    public GraphHopper setEncodedValuesString(String encodedValuesString) {
        this.encodedValuesString = encodedValuesString;
        return this;
    }

    public String getEncodedValuesString() {
        return encodedValuesString;
    }

    public GraphHopper setVehiclesString(String vehiclesString) {
        this.vehiclesString = vehiclesString;
        return this;
    }

    public String getVehiclesString() {
        return vehiclesString;
    }

    public EncodingManager getEncodingManager() {
        if (encodingManager == null)
            throw new IllegalStateException("EncodingManager not yet built");
        return encodingManager;
    }

    public OSMParsers getOSMParsers() {
        if (osmParsers == null)
            throw new IllegalStateException("OSMParsers not yet built");
        return osmParsers;
    }

    public ElevationProvider getElevationProvider() {
        return eleProvider;
    }

    public GraphHopper setElevationProvider(ElevationProvider eleProvider) {
        if (eleProvider == null || eleProvider == ElevationProvider.NOOP)
            setElevation(false);
        else
            setElevation(true);
        this.eleProvider = eleProvider;
        return this;
    }

    public GraphHopper setPathDetailsBuilderFactory(PathDetailsBuilderFactory pathBuilderFactory) {
        this.pathBuilderFactory = pathBuilderFactory;
        return this;
    }

    public PathDetailsBuilderFactory getPathDetailsBuilderFactory() {
        return pathBuilderFactory;
    }

    /**
     * Precise location resolution index means also more space (disc/RAM) could be consumed and
     * probably slower query times, which would be e.g. not suitable for Android. The resolution
     * specifies the tile width (in meter).
     */
    public GraphHopper setPreciseIndexResolution(int precision) {
        ensureNotLoaded();
        preciseIndexResolution = precision;
        return this;
    }

    public GraphHopper setMinNetworkSize(int minNetworkSize) {
        ensureNotLoaded();
        this.minNetworkSize = minNetworkSize;
        return this;
    }

    /**
     * Configures the urban density classification. Each edge will be classified as 'rural','residential' or 'city', {@link UrbanDensity}
     *
     * @param residentialAreaRadius      in meters. The higher this value the longer the calculation will take and the bigger the area for
     *                                   which the road density used to identify residential areas is calculated.
     * @param residentialAreaSensitivity Use this to find a trade-off between too many roads being classified as residential (too high
     *                                   values) and not enough roads being classified as residential (too small values)
     * @param cityAreaRadius             in meters. The higher this value the longer the calculation will take and the bigger the area for
     *                                   which the road density used to identify city areas is calculated. Set this to zero
     *                                   to skip the city classification.
     * @param cityAreaSensitivity        Use this to find a trade-off between too many roads being classified as city (too high values)
     *                                   and not enough roads being classified as city (too small values)
     * @param threads                    the number of threads used for the calculation. If this is zero the urban density
     *                                   calculation is skipped entirely
     */
    public GraphHopper setUrbanDensityCalculation(double residentialAreaRadius, double residentialAreaSensitivity,
                                                  double cityAreaRadius, double cityAreaSensitivity, int threads) {
        ensureNotLoaded();
        this.residentialAreaRadius = residentialAreaRadius;
        this.residentialAreaSensitivity = residentialAreaSensitivity;
        this.cityAreaRadius = cityAreaRadius;
        this.cityAreaSensitivity = cityAreaSensitivity;
        this.urbanDensityCalculationThreads = threads;
        return this;
    }

    /**
     * Only valid option for in-memory graph and if you e.g. want to disable store on flush for unit
     * tests. Specify storeOnFlush to true if you want that existing data will be loaded FROM disc
     * and all in-memory data will be flushed TO disc after flush is called e.g. while OSM import.
     *
     * @param storeOnFlush true by default
     */
    public GraphHopper setStoreOnFlush(boolean storeOnFlush) {
        ensureNotLoaded();
        if (storeOnFlush)
            dataAccessDefaultType = DAType.RAM_STORE;
        else
            dataAccessDefaultType = DAType.RAM;
        return this;
    }

    /**
     * Sets the routing profiles that shall be supported by this GraphHopper instance. The (and only the) given profiles
     * can be used for routing without preparation and for CH/LM preparation.
     * <p>
     * Here is an example how to setup two CH profiles and one LM profile (via the Java API)
     *
     * <pre>
     * {@code
     *   hopper.setProfiles(
     *     new Profile("my_car").setVehicle("car"),
     *     new Profile("your_bike").setVehicle("bike")
     *   );
     *   hopper.getCHPreparationHandler().setCHProfiles(
     *     new CHProfile("my_car"),
     *     new CHProfile("your_bike")
     *   );
     *   hopper.getLMPreparationHandler().setLMProfiles(
     *     new LMProfile("your_bike")
     *   );
     * }
     * </pre>
     * <p>
     * See also https://github.com/graphhopper/graphhopper/pull/1922.
     *
     * @see CHPreparationHandler#setCHProfiles
     * @see LMPreparationHandler#setLMProfiles
     */
    public GraphHopper setProfiles(Profile... profiles) {
        return setProfiles(Arrays.asList(profiles));
    }

    public GraphHopper setProfiles(List<Profile> profiles) {
        if (!profilesByName.isEmpty())
            throw new IllegalArgumentException("Cannot initialize profiles multiple times");
        if (encodingManager != null)
            throw new IllegalArgumentException("Cannot set profiles after EncodingManager was built");
        for (Profile profile : profiles) {
            Profile previous = this.profilesByName.put(profile.getName(), profile);
            if (previous != null)
                throw new IllegalArgumentException("Profile names must be unique. Duplicate name: '" + profile.getName() + "'");
        }
        return this;
    }

    public List<Profile> getProfiles() {
        return new ArrayList<>(profilesByName.values());
    }

    /**
     * Returns the profile for the given profile name, or null if it does not exist
     */
    public Profile getProfile(String profileName) {
        return profilesByName.get(profileName);
    }

    /**
     * @return true if storing and fetching elevation data is enabled. Default is false
     */
    public boolean hasElevation() {
        return elevation;
    }

    /**
     * Enable storing and fetching elevation data. Default is false
     */
    public GraphHopper setElevation(boolean includeElevation) {
        this.elevation = includeElevation;
        return this;
    }

    public String getGraphHopperLocation() {
        return ghLocation;
    }

    /**
     * Sets the graphhopper folder.
     */
    public GraphHopper setGraphHopperLocation(String ghLocation) {
        ensureNotLoaded();
        if (ghLocation == null)
            throw new IllegalArgumentException("graphhopper location cannot be null");

        this.ghLocation = ghLocation;
        return this;
    }

    public String getOSMFile() {
        return osmFile;
    }

    /**
     * This file can be an osm xml (.osm), a compressed xml (.osm.zip or .osm.gz) or a protobuf file
     * (.pbf).
     */
    public GraphHopper setOSMFile(String osmFile) {
        ensureNotLoaded();
        if (isEmpty(osmFile))
            throw new IllegalArgumentException("OSM file cannot be empty.");

        this.osmFile = osmFile;
        return this;
    }

    /**
     * The underlying graph used in algorithms.
     *
     * @throws IllegalStateException if graph is not instantiated.
     */
    public BaseGraph getBaseGraph() {
        if (baseGraph == null)
            throw new IllegalStateException("GraphHopper storage not initialized");

        return baseGraph;
    }

    public void setBaseGraph(BaseGraph baseGraph) {
        this.baseGraph = baseGraph;
        setFullyLoaded();
    }

    public StorableProperties getProperties() {
        return properties;
    }

    /**
     * @return a mapping between profile names and according CH preparations. The map will be empty before loading
     * or import.
     */
    public Map<String, RoutingCHGraph> getCHGraphs() {
        return chGraphs;
    }

    /**
     * @return a mapping between profile names and according landmark preparations. The map will be empty before loading
     * or import.
     */
    public Map<String, LandmarkStorage> getLandmarks() {
        return landmarks;
    }

    /**
     * The location index created from the graph.
     *
     * @throws IllegalStateException if index is not initialized
     */
    public LocationIndex getLocationIndex() {
        if (locationIndex == null)
            throw new IllegalStateException("LocationIndex not initialized");

        return locationIndex;
    }

    protected void setLocationIndex(LocationIndex locationIndex) {
        this.locationIndex = locationIndex;
    }

    /**
     * Sorts the graph which requires more RAM while import. See #12
     */
    public GraphHopper setSortGraph(boolean sortGraph) {
        ensureNotLoaded();
        this.sortGraph = sortGraph;
        return this;
    }

    public boolean isAllowWrites() {
        return allowWrites;
    }

    /**
     * Specifies if it is allowed for GraphHopper to write. E.g. for read only filesystems it is not
     * possible to create a lock file and so we can avoid write locks.
     */
    public GraphHopper setAllowWrites(boolean allowWrites) {
        this.allowWrites = allowWrites;
        return this;
    }

    public TranslationMap getTranslationMap() {
        return trMap;
    }

    public GraphHopper setVehicleEncodedValuesFactory(VehicleEncodedValuesFactory factory) {
        this.vehicleEncodedValuesFactory = factory;
        return this;
    }

    public EncodedValueFactory getEncodedValueFactory() {
        return this.encodedValueFactory;
    }

    public GraphHopper setEncodedValueFactory(EncodedValueFactory factory) {
        this.encodedValueFactory = factory;
        return this;
    }

    public VehicleTagParserFactory getVehicleTagParserFactory() {
        return this.vehicleTagParserFactory;
    }

    public GraphHopper setVehicleTagParserFactory(VehicleTagParserFactory factory) {
        this.vehicleTagParserFactory = factory;
        return this;
    }

    public TagParserFactory getTagParserFactory() {
        return this.tagParserFactory;
    }

    public GraphHopper setTagParserFactory(TagParserFactory factory) {
        this.tagParserFactory = factory;
        return this;
    }

    public GraphHopper setCustomAreasDirectory(String customAreasDirectory) {
        this.customAreasDirectory = customAreasDirectory;
        return this;
    }

    public String getCustomAreasDirectory() {
        return this.customAreasDirectory;
    }

    /**
     * Sets the factory used to create country rules. Use `null` to disable country rules
     */
    public GraphHopper setCountryRuleFactory(CountryRuleFactory countryRuleFactory) {
        this.countryRuleFactory = countryRuleFactory;
        return this;
    }

    public CountryRuleFactory getCountryRuleFactory() {
        return this.countryRuleFactory;
    }

    /**
     * Reads the configuration from a {@link GraphHopperConfig} object which can be manually filled, or more typically
     * is read from `config.yml`.
     * <p>
     * Important note: Calling this method overwrites the configuration done in some of the setter methods of this class,
     * so generally it is advised to either use this method to configure GraphHopper or the different setter methods,
     * but not both. Unfortunately, this still does not cover all cases and sometimes you have to use both, but then you
     * should make sure there are no conflicts. If you need both it might also help to call the init before calling the
     * setters, because this way the init method won't apply defaults to configuration options you already chose using
     * the setters.
     */
    public GraphHopper init(GraphHopperConfig ghConfig) {
        ensureNotLoaded();
        // disabling_allowed config options were removed for GH 3.0
        if (ghConfig.has("routing.ch.disabling_allowed"))
            throw new IllegalArgumentException("The 'routing.ch.disabling_allowed' configuration option is no longer supported");
        if (ghConfig.has("routing.lm.disabling_allowed"))
            throw new IllegalArgumentException("The 'routing.lm.disabling_allowed' configuration option is no longer supported");
        if (ghConfig.has("osmreader.osm"))
            throw new IllegalArgumentException("Instead of osmreader.osm use datareader.file, for other changes see CHANGELOG.md");

        String tmpOsmFile = ghConfig.getString("datareader.file", "");
        if (!isEmpty(tmpOsmFile))
            osmFile = tmpOsmFile;

        String graphHopperFolder = ghConfig.getString("graph.location", "");
        if (isEmpty(graphHopperFolder) && isEmpty(ghLocation)) {
            if (isEmpty(osmFile))
                throw new IllegalArgumentException("If no graph.location is provided you need to specify an OSM file.");

            graphHopperFolder = pruneFileEnd(osmFile) + "-gh";
        }
        ghLocation = graphHopperFolder;

        countryRuleFactory = ghConfig.getBool("country_rules.enabled", false) ? new CountryRuleFactory() : null;
        customAreasDirectory = ghConfig.getString("custom_areas.directory", customAreasDirectory);

        defaultSegmentSize = ghConfig.getInt("graph.dataaccess.segment_size", defaultSegmentSize);

        String daTypeString = ghConfig.getString("graph.dataaccess.default_type", ghConfig.getString("graph.dataaccess", "RAM_STORE"));
        dataAccessDefaultType = DAType.fromString(daTypeString);
        for (Map.Entry<String, Object> entry : ghConfig.asPMap().toMap().entrySet()) {
            if (entry.getKey().startsWith("graph.dataaccess.type."))
                dataAccessConfig.put(entry.getKey().substring("graph.dataaccess.type.".length()), entry.getValue().toString());
            if (entry.getKey().startsWith("graph.dataaccess.mmap.preload."))
                dataAccessConfig.put(entry.getKey().substring("graph.dataaccess.mmap.".length()), entry.getValue().toString());
        }

        if (ghConfig.getBool("max_speed_calculator.enabled", false))
            maxSpeedCalculator = new MaxSpeedCalculator(MaxSpeedCalculator.createLegalDefaultSpeeds());

        sortGraph = ghConfig.getBool("graph.do_sort", sortGraph);
        removeZipped = ghConfig.getBool("graph.remove_zipped", removeZipped);

        if (!ghConfig.getString("spatial_rules.location", "").isEmpty())
            throw new IllegalArgumentException("spatial_rules.location has been deprecated. Please use custom_areas.directory instead and read the documentation for custom areas.");
        if (!ghConfig.getString("spatial_rules.borders_directory", "").isEmpty())
            throw new IllegalArgumentException("spatial_rules.borders_directory has been deprecated. Please use custom_areas.directory instead and read the documentation for custom areas.");
        // todo: maybe introduce custom_areas.max_bbox if this is needed later
        if (!ghConfig.getString("spatial_rules.max_bbox", "").isEmpty())
            throw new IllegalArgumentException("spatial_rules.max_bbox has been deprecated. There is no replacement, all custom areas will be considered.");

        String customAreasDirectory = ghConfig.getString("custom_areas.directory", "");
        JsonFeatureCollection globalAreas = GraphHopper.resolveCustomAreas(customAreasDirectory);
        String customModelFolder = ghConfig.getString("custom_models.directory", ghConfig.getString("custom_model_folder", ""));
        setProfiles(GraphHopper.resolveCustomModelFiles(customModelFolder, ghConfig.getProfiles(), globalAreas));

        if (ghConfig.has("graph.vehicles") && ghConfig.has("graph.flag_encoders"))
            throw new IllegalArgumentException("Remove graph.flag_encoders as it cannot be used in parallel with graph.vehicles");
        if (ghConfig.has("graph.flag_encoders"))
            logger.warn("The option graph.flag_encoders is deprecated and will be removed. Replace with graph.vehicles");
        vehiclesString = ghConfig.getString("graph.vehicles", ghConfig.getString("graph.flag_encoders", vehiclesString));

        encodedValuesString = ghConfig.getString("graph.encoded_values", encodedValuesString);
        dateRangeParserString = ghConfig.getString("datareader.date_range_parser_day", dateRangeParserString);

        if (ghConfig.getString("graph.locktype", "native").equals("simple"))
            lockFactory = new SimpleFSLockFactory();
        else
            lockFactory = new NativeFSLockFactory();

        // elevation
        if (ghConfig.has("graph.elevation.smoothing"))
            throw new IllegalArgumentException("Use 'graph.elevation.edge_smoothing: moving_average' or the new 'graph.elevation.edge_smoothing: ramer'. See #2634.");
        osmReaderConfig.setElevationSmoothing(ghConfig.getString("graph.elevation.edge_smoothing", osmReaderConfig.getElevationSmoothing()));
        osmReaderConfig.setSmoothElevationAverageWindowSize(ghConfig.getDouble("graph.elevation.edge_smoothing.moving_average.window_size", osmReaderConfig.getSmoothElevationAverageWindowSize()));
        osmReaderConfig.setElevationSmoothingRamerMax(ghConfig.getInt("graph.elevation.edge_smoothing.ramer.max_elevation", osmReaderConfig.getElevationSmoothingRamerMax()));
        osmReaderConfig.setLongEdgeSamplingDistance(ghConfig.getDouble("graph.elevation.long_edge_sampling_distance", osmReaderConfig.getLongEdgeSamplingDistance()));
        osmReaderConfig.setElevationMaxWayPointDistance(ghConfig.getDouble("graph.elevation.way_point_max_distance", osmReaderConfig.getElevationMaxWayPointDistance()));
        routerConfig.setElevationWayPointMaxDistance(ghConfig.getDouble("graph.elevation.way_point_max_distance", routerConfig.getElevationWayPointMaxDistance()));
        ElevationProvider elevationProvider = createElevationProvider(ghConfig);
        setElevationProvider(elevationProvider);

        if (osmReaderConfig.getLongEdgeSamplingDistance() < Double.MAX_VALUE && !elevationProvider.canInterpolate())
            logger.warn("Long edge sampling enabled, but bilinear interpolation disabled. See #1953");

        // optimizable prepare
        minNetworkSize = ghConfig.getInt("prepare.min_network_size", minNetworkSize);
        subnetworksThreads = ghConfig.getInt("prepare.subnetworks.threads", subnetworksThreads);

        // prepare CH&LM
        chPreparationHandler.init(ghConfig);
        lmPreparationHandler.init(ghConfig);

        // osm import
        // We do a few checks for import.osm.ignored_highways to prevent configuration errors when migrating from an older
        // GH version.
        if (!ghConfig.has("import.osm.ignored_highways"))
            throw new IllegalArgumentException("Missing 'import.osm.ignored_highways'. Not using this parameter can decrease performance, see config-example.yml for more details");
        String ignoredHighwaysString = ghConfig.getString("import.osm.ignored_highways", "");
        if ((ignoredHighwaysString.contains("footway") || ignoredHighwaysString.contains("path")) && ghConfig.getProfiles().stream().map(Profile::getName).anyMatch(p -> p.contains("foot") || p.contains("hike") || p.contains("wheelchair")))
            throw new IllegalArgumentException("You should not use import.osm.ignored_highways=footway or =path in conjunction with pedestrian profiles. This is probably an error in your configuration.");
        if ((ignoredHighwaysString.contains("cycleway") || ignoredHighwaysString.contains("path")) && ghConfig.getProfiles().stream().map(Profile::getName).anyMatch(p -> p.contains("mtb") || p.contains("bike")))
            throw new IllegalArgumentException("You should not use import.osm.ignored_highways=cycleway or =path in conjunction with bicycle profiles. This is probably an error in your configuration");

        osmReaderConfig.setIgnoredHighways(Arrays.stream(ghConfig.getString("import.osm.ignored_highways", String.join(",", osmReaderConfig.getIgnoredHighways()))
                .split(",")).map(String::trim).collect(Collectors.toList()));
        osmReaderConfig.setParseWayNames(ghConfig.getBool("datareader.instructions", osmReaderConfig.isParseWayNames()));
        osmReaderConfig.setPreferredLanguage(ghConfig.getString("datareader.preferred_language", osmReaderConfig.getPreferredLanguage()));
        osmReaderConfig.setMaxWayPointDistance(ghConfig.getDouble(Routing.INIT_WAY_POINT_MAX_DISTANCE, osmReaderConfig.getMaxWayPointDistance()));
        osmReaderConfig.setWorkerThreads(ghConfig.getInt("datareader.worker_threads", osmReaderConfig.getWorkerThreads()));

        // index
        preciseIndexResolution = ghConfig.getInt("index.high_resolution", preciseIndexResolution);
        maxRegionSearch = ghConfig.getInt("index.max_region_search", maxRegionSearch);

        // urban density calculation
        residentialAreaRadius = ghConfig.getDouble("graph.urban_density.residential_radius", residentialAreaRadius);
        residentialAreaSensitivity = ghConfig.getDouble("graph.urban_density.residential_sensitivity", residentialAreaSensitivity);
        cityAreaRadius = ghConfig.getDouble("graph.urban_density.city_radius", cityAreaRadius);
        cityAreaSensitivity = ghConfig.getDouble("graph.urban_density.city_sensitivity", cityAreaSensitivity);
        urbanDensityCalculationThreads = ghConfig.getInt("graph.urban_density.threads", urbanDensityCalculationThreads);

        // routing
        routerConfig.setMaxVisitedNodes(ghConfig.getInt(Routing.INIT_MAX_VISITED_NODES, routerConfig.getMaxVisitedNodes()));
        routerConfig.setTimeoutMillis(ghConfig.getLong(Routing.INIT_TIMEOUT_MS, routerConfig.getTimeoutMillis()));
        routerConfig.setMaxRoundTripRetries(ghConfig.getInt(RoundTrip.INIT_MAX_RETRIES, routerConfig.getMaxRoundTripRetries()));
        routerConfig.setNonChMaxWaypointDistance(ghConfig.getInt(Parameters.NON_CH.MAX_NON_CH_POINT_DISTANCE, routerConfig.getNonChMaxWaypointDistance()));
        routerConfig.setInstructionsEnabled(ghConfig.getBool(Routing.INIT_INSTRUCTIONS, routerConfig.isInstructionsEnabled()));
        int activeLandmarkCount = ghConfig.getInt(Landmark.ACTIVE_COUNT_DEFAULT, Math.min(8, lmPreparationHandler.getLandmarks()));
        if (activeLandmarkCount > lmPreparationHandler.getLandmarks())
            throw new IllegalArgumentException("Default value for active landmarks " + activeLandmarkCount
                    + " should be less or equal to landmark count of " + lmPreparationHandler.getLandmarks());
        routerConfig.setActiveLandmarkCount(activeLandmarkCount);

        return this;
    }

    protected EncodingManager buildEncodingManager(Map<String, String> vehiclesByName, List<String> encodedValueStrings,
                                                   boolean withUrbanDensity, boolean withMaxSpeedEst, Collection<Profile> profiles) {
        EncodingManager.Builder emBuilder = new EncodingManager.Builder();
        vehiclesByName.forEach((name, vehicleStr) -> emBuilder.add(vehicleEncodedValuesFactory.createVehicleEncodedValues(name, new PMap(vehicleStr))));
        profiles.forEach(profile -> emBuilder.add(Subnetwork.create(profile.getName())));
        if (withMaxSpeedEst)
            emBuilder.add(MaxSpeedEstimated.create());
        if (withUrbanDensity)
            emBuilder.add(UrbanDensity.create());
        encodedValueStrings.forEach(s -> emBuilder.add(encodedValueFactory.create(s, new PMap())));
        return emBuilder.build();
    }

    protected OSMParsers buildOSMParsers(Map<String, String> vehiclesByName, List<String> encodedValueStrings,
                                         List<String> ignoredHighways, String dateRangeParserString) {
        OSMParsers osmParsers = new OSMParsers();
        ignoredHighways.forEach(osmParsers::addIgnoredHighway);
        for (String s : encodedValueStrings) {
            TagParser tagParser = tagParserFactory.create(encodingManager, s, new PMap());
            if (tagParser != null)
                osmParsers.addWayTagParser(tagParser);
        }

        // this needs to be in sync with the default EVs added in EncodingManager.Builder#build. ideally I would like to remove
        // all these defaults and just use the config as the single source of truth
        if (!encodedValueStrings.contains(Roundabout.KEY))
            osmParsers.addWayTagParser(new OSMRoundaboutParser(encodingManager.getBooleanEncodedValue(Roundabout.KEY)));
        if (!encodedValueStrings.contains(RoadClass.KEY))
            osmParsers.addWayTagParser(new OSMRoadClassParser(encodingManager.getEnumEncodedValue(RoadClass.KEY, RoadClass.class)));
        if (!encodedValueStrings.contains(RoadClassLink.KEY))
            osmParsers.addWayTagParser(new OSMRoadClassLinkParser(encodingManager.getBooleanEncodedValue(RoadClassLink.KEY)));
        if (!encodedValueStrings.contains(RoadEnvironment.KEY))
            osmParsers.addWayTagParser(new OSMRoadEnvironmentParser(encodingManager.getEnumEncodedValue(RoadEnvironment.KEY, RoadEnvironment.class)));
        if (!encodedValueStrings.contains(MaxSpeed.KEY))
            osmParsers.addWayTagParser(new OSMMaxSpeedParser(encodingManager.getDecimalEncodedValue(MaxSpeed.KEY)));
        if (!encodedValueStrings.contains(RoadAccess.KEY))
            osmParsers.addWayTagParser(new OSMRoadAccessParser(encodingManager.getEnumEncodedValue(RoadAccess.KEY, RoadAccess.class), OSMRoadAccessParser.toOSMRestrictions(TransportationMode.CAR)));
        if (!encodedValueStrings.contains(FerrySpeed.KEY))
            osmParsers.addWayTagParser(new FerrySpeedCalculator(encodingManager.getDecimalEncodedValue(FerrySpeed.KEY)));
        if (encodingManager.hasEncodedValue(AverageSlope.KEY) || encodingManager.hasEncodedValue(MaxSlope.KEY)) {
            if (!encodingManager.hasEncodedValue(AverageSlope.KEY) || !encodingManager.hasEncodedValue(MaxSlope.KEY))
                throw new IllegalArgumentException("Enable both, average_slope and max_slope");
            osmParsers.addWayTagParser(new SlopeCalculator(encodingManager.getDecimalEncodedValue(MaxSlope.KEY),
                    encodingManager.getDecimalEncodedValue(AverageSlope.KEY)));
        }

        if (maxSpeedCalculator != null) {
            if (!encodingManager.hasEncodedValue(Country.KEY))
                throw new IllegalArgumentException("max_speed_calculator needs country");
            if (!encodingManager.hasEncodedValue(UrbanDensity.KEY))
                throw new IllegalArgumentException("max_speed_calculator needs urban_density");
            osmParsers.addWayTagParser(maxSpeedCalculator.getParser());
        }

        if (encodingManager.hasEncodedValue(Curvature.KEY))
            osmParsers.addWayTagParser(new CurvatureCalculator(encodingManager.getDecimalEncodedValue(Curvature.KEY)));

        DateRangeParser dateRangeParser = DateRangeParser.createInstance(dateRangeParserString);
        Set<String> added = new HashSet<>();
        vehiclesByName.forEach((name, vehicleStr) -> {
            VehicleTagParsers vehicleTagParsers = vehicleTagParserFactory.createParsers(encodingManager, name,
                    new PMap(vehicleStr).putObject("date_range_parser", dateRangeParser));
            if (vehicleTagParsers == null)
                return;
            vehicleTagParsers.getTagParsers().forEach(tagParser -> {
                if (tagParser == null) return;
                if (tagParser instanceof BikeCommonAccessParser) {
                    if (encodingManager.hasEncodedValue(BikeNetwork.KEY) && added.add(BikeNetwork.KEY))
                        osmParsers.addRelationTagParser(relConfig -> new OSMBikeNetworkTagParser(encodingManager.getEnumEncodedValue(BikeNetwork.KEY, RouteNetwork.class), relConfig));
                    if (encodingManager.hasEncodedValue(Smoothness.KEY) && added.add(Smoothness.KEY))
                        osmParsers.addWayTagParser(new OSMSmoothnessParser(encodingManager.getEnumEncodedValue(Smoothness.KEY, Smoothness.class)));
                } else if (tagParser instanceof FootAccessParser) {
                    if (encodingManager.hasEncodedValue(FootNetwork.KEY) && added.add(FootNetwork.KEY))
                        osmParsers.addRelationTagParser(relConfig -> new OSMFootNetworkTagParser(encodingManager.getEnumEncodedValue(FootNetwork.KEY, RouteNetwork.class), relConfig));
                }
                String turnRestrictionKey = TurnRestriction.key(new PMap(vehicleStr).getString("name", name));
                if (encodingManager.hasTurnEncodedValue(turnRestrictionKey)
                        // need to make sure we do not add the same restriction parsers multiple times
                        && osmParsers.getRestrictionTagParsers().stream().noneMatch(r -> r.getTurnRestrictionEnc().getName().equals(turnRestrictionKey))) {
                    List<String> restrictions = tagParser instanceof AbstractAccessParser
                            ? ((AbstractAccessParser) tagParser).getRestrictions()
                            : OSMRoadAccessParser.toOSMRestrictions(TransportationMode.valueOf(new PMap(vehicleStr).getString("transportation_mode", "VEHICLE")));
                    osmParsers.addRestrictionTagParser(new RestrictionTagParser(restrictions, encodingManager.getTurnBooleanEncodedValue(turnRestrictionKey)));
                }
            });
            vehicleTagParsers.getTagParsers().forEach(tagParser -> {
                if (tagParser == null) return;
                osmParsers.addWayTagParser(tagParser);

                if (tagParser instanceof BikeCommonAccessParser && encodingManager.hasEncodedValue(GetOffBike.KEY) && added.add(GetOffBike.KEY))
                    osmParsers.addWayTagParser(new OSMGetOffBikeParser(encodingManager.getBooleanEncodedValue(GetOffBike.KEY), ((BikeCommonAccessParser) tagParser).getAccessEnc()));
            });
        });
        return osmParsers;
    }

    public static List<String> getEncodedValueStrings(String encodedValuesStr) {
        return Arrays.stream(encodedValuesStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    public static Map<String, String> getVehiclesByName(String vehiclesStr, Collection<Profile> profiles) {
        Map<String, String> vehiclesMap = new LinkedHashMap<>();
        for (String encoderStr : vehiclesStr.split(",")) {
            String name = encoderStr.split("\\|")[0].trim();
            if (name.isEmpty())
                continue;
            if (vehiclesMap.containsKey(name))
                throw new IllegalArgumentException("Duplicate vehicle: " + name + " in: " + encoderStr);
            vehiclesMap.put(name, encoderStr);
        }
        Map<String, String> vehiclesFromProfiles = new LinkedHashMap<>();
        for (Profile profile : profiles) {
            // if a profile uses a vehicle with turn costs make sure we add that vehicle with turn costs
            String vehicle = profile.getVehicle().trim();
            if (!vehiclesFromProfiles.containsKey(vehicle) || profile.isTurnCosts())
                vehiclesFromProfiles.put(vehicle, vehicle + (profile.isTurnCosts() ? "|turn_costs=true" : ""));
        }
        // vehicles from profiles are only taken into account when they were not given explicitly
        vehiclesFromProfiles.forEach(vehiclesMap::putIfAbsent);
        return vehiclesMap;
    }

    private static ElevationProvider createElevationProvider(GraphHopperConfig ghConfig) {
        String eleProviderStr = toLowerCase(ghConfig.getString("graph.elevation.provider", "noop"));

        if (ghConfig.has("graph.elevation.calcmean"))
            throw new IllegalArgumentException("graph.elevation.calcmean is deprecated, use graph.elevation.interpolate");

        String cacheDirStr = ghConfig.getString("graph.elevation.cache_dir", "");
        if (cacheDirStr.isEmpty() && ghConfig.has("graph.elevation.cachedir"))
            throw new IllegalArgumentException("use graph.elevation.cache_dir not cachedir in configuration");

        ElevationProvider elevationProvider = ElevationProvider.NOOP;
        if (eleProviderStr.equalsIgnoreCase("hgt")) {
            elevationProvider = new HGTProvider(cacheDirStr);
        } else if (eleProviderStr.equalsIgnoreCase("srtm")) {
            elevationProvider = new SRTMProvider(cacheDirStr);
        } else if (eleProviderStr.equalsIgnoreCase("cgiar")) {
            elevationProvider = new CGIARProvider(cacheDirStr);
        } else if (eleProviderStr.equalsIgnoreCase("gmted")) {
            elevationProvider = new GMTEDProvider(cacheDirStr);
        } else if (eleProviderStr.equalsIgnoreCase("srtmgl1")) {
            elevationProvider = new SRTMGL1Provider(cacheDirStr);
        } else if (eleProviderStr.equalsIgnoreCase("multi")) {
            elevationProvider = new MultiSourceElevationProvider(cacheDirStr);
        } else if (eleProviderStr.equalsIgnoreCase("skadi")) {
            elevationProvider = new SkadiProvider(cacheDirStr);
        }

        if (elevationProvider instanceof TileBasedElevationProvider) {
            TileBasedElevationProvider provider = (TileBasedElevationProvider) elevationProvider;

            String baseURL = ghConfig.getString("graph.elevation.base_url", "");
            if (baseURL.isEmpty() && ghConfig.has("graph.elevation.baseurl"))
                throw new IllegalArgumentException("use graph.elevation.base_url not baseurl in configuration");

            DAType elevationDAType = DAType.fromString(ghConfig.getString("graph.elevation.dataaccess", "MMAP"));

            boolean interpolate = ghConfig.has("graph.elevation.interpolate")
                    ? "bilinear".equals(ghConfig.getString("graph.elevation.interpolate", "none"))
                    : ghConfig.getBool("graph.elevation.calc_mean", false);

            boolean removeTempElevationFiles = ghConfig.getBool("graph.elevation.cgiar.clear", true);
            removeTempElevationFiles = ghConfig.getBool("graph.elevation.clear", removeTempElevationFiles);

            provider
                    .setAutoRemoveTemporaryFiles(removeTempElevationFiles)
                    .setInterpolate(interpolate)
                    .setDAType(elevationDAType);
            if (!baseURL.isEmpty())
                provider.setBaseURL(baseURL);
        }
        return elevationProvider;
    }

    private void printInfo() {
        logger.info("version " + Constants.VERSION + "|" + Constants.BUILD_DATE + " (" + Constants.getVersions() + ")");
        if (baseGraph != null)
            logger.info("graph " + getBaseGraphString() + ", details:" + baseGraph.toDetailsString());
    }

    private String getBaseGraphString() {
        return encodingManager
                + "|" + baseGraph.getDirectory().getDefaultType()
                + "|" + baseGraph.getNodeAccess().getDimension() + "D"
                + "|" + (baseGraph.getTurnCostStorage() != null ? baseGraph.getTurnCostStorage() : "no_turn_cost")
                + "|" + getVersionsString();
    }

    private String getVersionsString() {
        return "nodes:" + Constants.VERSION_NODE +
                ",edges:" + Constants.VERSION_EDGE +
                ",geometry:" + Constants.VERSION_GEOMETRY +
                ",location_index:" + Constants.VERSION_LOCATION_IDX +
                ",string_index:" + Constants.VERSION_KV_STORAGE +
                ",nodesCH:" + Constants.VERSION_NODE_CH +
                ",shortcuts:" + Constants.VERSION_SHORTCUT;
    }

    /**
     * Imports provided data from disc and creates graph. Depending on the settings the resulting
     * graph will be stored to disc so on a second call this method will only load the graph from
     * disc which is usually a lot faster.
     */
    public GraphHopper importOrLoad() {
        if (!load()) {
            printInfo();
            process(false);
        } else {
            printInfo();
        }
        return this;
    }

    /**
     * Imports and processes data, storing it to disk when complete.
     */
    public void importAndClose() {
        if (!load()) {
            printInfo();
            process(true);
        } else {
            printInfo();
            logger.info("Graph already imported into " + ghLocation);
        }
        close();
    }

    /**
     * Creates the graph from OSM data.
     */
    protected void process(boolean closeEarly) {
        GHDirectory directory = new GHDirectory(ghLocation, dataAccessDefaultType);
        directory.configure(dataAccessConfig);
        boolean withUrbanDensity = urbanDensityCalculationThreads > 0;
        boolean withMaxSpeedEstimation = maxSpeedCalculator != null;
        Map<String, String> vehiclesByName = getVehiclesByName(vehiclesString, profilesByName.values());
        List<String> encodedValueStrings = getEncodedValueStrings(encodedValuesString);
        encodingManager = buildEncodingManager(vehiclesByName, encodedValueStrings, withUrbanDensity,
                withMaxSpeedEstimation, profilesByName.values());
        osmParsers = buildOSMParsers(vehiclesByName, encodedValueStrings, osmReaderConfig.getIgnoredHighways(), dateRangeParserString);
        baseGraph = new BaseGraph.Builder(getEncodingManager())
                .setDir(directory)
                .set3D(hasElevation())
                .withTurnCosts(encodingManager.needsTurnCostsSupport())
                .setSegmentSize(defaultSegmentSize)
                .build();
        properties = new StorableProperties(directory);
        checkProfilesConsistency();

        GHLock lock = null;
        try {
            if (directory.getDefaultType().isStoring()) {
                lockFactory.setLockDir(new File(ghLocation));
                lock = lockFactory.create(fileLockName, true);
                if (!lock.tryLock())
                    throw new RuntimeException("To avoid multiple writers we need to obtain a write lock but it failed. In " + ghLocation, lock.getObtainFailedReason());
            }
            ensureWriteAccess();
            importOSM();
            cleanUp();
            postImport();
            postProcessing(closeEarly);
            flush();
        } finally {
            if (lock != null)
                lock.release();
        }
    }

    protected void postImport() {
        // Important note: To deal with via-way turn restrictions we introduce artificial edges in OSMReader (#2689).
        // These are simply copies of real edges. Any further modifications of the graph edges must take care of keeping
        // the artificial edges in sync with their real counterparts. So if an edge attribute shall be changed this change
        // must also be applied to the corresponding artificial edge.
        if (sortGraph) {
            BaseGraph newGraph = GHUtility.newGraph(baseGraph);
            GHUtility.sortDFS(baseGraph, newGraph);
            logger.info("graph sorted (" + getMemInfo() + ")");
            baseGraph = newGraph;
        }

        if (hasElevation())
            interpolateBridgesTunnelsAndFerries();

        if (maxSpeedCalculator != null) {
            maxSpeedCalculator.fillMaxSpeed(getBaseGraph(), encodingManager);
            maxSpeedCalculator.close();
        }
    }

    protected void importOSM() {
        if (osmFile == null)
            throw new IllegalStateException("Couldn't load from existing folder: " + ghLocation
                    + " but also cannot use file for DataReader as it wasn't specified!");

        List<CustomArea> customAreas = readCountries();
        if (isEmpty(customAreasDirectory)) {
            logger.info("No custom areas are used, custom_areas.directory not given");
        } else {
            logger.info("Creating custom area index, reading custom areas from: '" + customAreasDirectory + "'");
            customAreas.addAll(readCustomAreas());
        }

        AreaIndex<CustomArea> areaIndex = new AreaIndex<>(customAreas);
        if (countryRuleFactory == null || countryRuleFactory.getCountryToRuleMap().isEmpty()) {
            logger.info("No country rules available");
        } else {
            logger.info("Applying rules for the following countries: {}", countryRuleFactory.getCountryToRuleMap().keySet());
        }

        logger.info("start creating graph from " + osmFile);
        OSMReader reader = new OSMReader(baseGraph.getBaseGraph(), osmParsers, osmReaderConfig).setFile(_getOSMFile()).
                setAreaIndex(areaIndex).
                setElevationProvider(eleProvider).
                setCountryRuleFactory(countryRuleFactory);
        logger.info("using " + getBaseGraphString() + ", memory:" + getMemInfo());

        createBaseGraphAndProperties();

        try {
            reader.readGraph();
        } catch (IOException ex) {
            throw new RuntimeException("Cannot read file " + getOSMFile(), ex);
        }
        DateFormat f = createFormatter();
        properties.put("datareader.import.date", f.format(new Date()));
        if (reader.getDataDate() != null)
            properties.put("datareader.data.date", f.format(reader.getDataDate()));

        calculateUrbanDensity();
        writeEncodingManagerToProperties();
    }

    protected void createBaseGraphAndProperties() {
        baseGraph.getDirectory().create();
        baseGraph.create(100);
        properties.create(100);
        if (maxSpeedCalculator != null)
            maxSpeedCalculator.createDataAccessForParser(baseGraph.getDirectory());
    }

    protected void calculateUrbanDensity() {
        if (encodingManager.hasEncodedValue(UrbanDensity.KEY)) {
            EnumEncodedValue<UrbanDensity> urbanDensityEnc = encodingManager.getEnumEncodedValue(UrbanDensity.KEY, UrbanDensity.class);
            if (!encodingManager.hasEncodedValue(RoadClass.KEY))
                throw new IllegalArgumentException("Urban density calculation requires " + RoadClass.KEY);
            if (!encodingManager.hasEncodedValue(RoadClassLink.KEY))
                throw new IllegalArgumentException("Urban density calculation requires " + RoadClassLink.KEY);
            EnumEncodedValue<RoadClass> roadClassEnc = encodingManager.getEnumEncodedValue(RoadClass.KEY, RoadClass.class);
            BooleanEncodedValue roadClassLinkEnc = encodingManager.getBooleanEncodedValue(RoadClassLink.KEY);
            UrbanDensityCalculator.calcUrbanDensity(baseGraph, urbanDensityEnc, roadClassEnc,
                    roadClassLinkEnc, residentialAreaRadius, residentialAreaSensitivity, cityAreaRadius, cityAreaSensitivity, urbanDensityCalculationThreads);
        }
    }

    protected void writeEncodingManagerToProperties() {
        EncodingManager.putEncodingManagerIntoProperties(encodingManager, properties);
    }

    private List<CustomArea> readCustomAreas() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JtsModule());
        final Path bordersDirectory = Paths.get(customAreasDirectory);
        List<JsonFeatureCollection> jsonFeatureCollections = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(bordersDirectory, "*.{geojson,json}")) {
            for (Path borderFile : stream) {
                try (BufferedReader reader = Files.newBufferedReader(borderFile, StandardCharsets.UTF_8)) {
                    JsonFeatureCollection jsonFeatureCollection = objectMapper.readValue(reader, JsonFeatureCollection.class);
                    jsonFeatureCollections.add(jsonFeatureCollection);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return jsonFeatureCollections.stream().flatMap(j -> j.getFeatures().stream())
                .map(CustomArea::fromJsonFeature)
                .collect(Collectors.toList());
    }

    /**
     * Currently we use this for a few tests where the dataReaderFile is loaded from the classpath
     */
    protected File _getOSMFile() {
        return new File(osmFile);
    }

    /**
     * Load from existing graph folder.
     */
    public boolean load() {
        if (isEmpty(ghLocation))
            throw new IllegalStateException("GraphHopperLocation is not specified. Call setGraphHopperLocation or init before");

        if (fullyLoaded)
            throw new IllegalStateException("graph is already successfully loaded");

        File tmpFileOrFolder = new File(ghLocation);
        if (!tmpFileOrFolder.isDirectory() && tmpFileOrFolder.exists()) {
            throw new IllegalArgumentException("GraphHopperLocation cannot be an existing file. Has to be either non-existing or a folder.");
        } else {
            File compressed = new File(ghLocation + ".ghz");
            if (compressed.exists() && !compressed.isDirectory()) {
                try {
                    new Unzipper().unzip(compressed.getAbsolutePath(), ghLocation, removeZipped);
                } catch (IOException ex) {
                    throw new RuntimeException("Couldn't extract file " + compressed.getAbsolutePath()
                            + " to " + ghLocation, ex);
                }
            }
        }

        // todo: this does not really belong here, we abuse the load method to derive the dataAccessDefaultType setting from others
        if (!allowWrites && dataAccessDefaultType.isMMap())
            dataAccessDefaultType = DAType.MMAP_RO;

        if (!new File(ghLocation).exists())
            // there is just nothing to load
            return false;

        GHDirectory directory = new GHDirectory(ghLocation, dataAccessDefaultType);
        directory.configure(dataAccessConfig);
        GHLock lock = null;
        try {
            // create locks only if writes are allowed, if they are not allowed a lock cannot be created
            // (e.g. on a read only filesystem locks would fail)
            if (directory.getDefaultType().isStoring() && isAllowWrites()) {
                lockFactory.setLockDir(new File(ghLocation));
                lock = lockFactory.create(fileLockName, false);
                if (!lock.tryLock())
                    throw new RuntimeException("To avoid reading partial data we need to obtain the read lock but it failed. In " + ghLocation, lock.getObtainFailedReason());
            }
            properties = new StorableProperties(directory);
            if (!properties.loadExisting())
                // the -gh folder exists, but there is no properties file. it might be just empty, so let's act as if
                // the import did not run yet or is not complete for some reason
                return false;
            encodingManager = EncodingManager.fromProperties(properties);
            baseGraph = new BaseGraph.Builder(encodingManager)
                    .setDir(directory)
                    .set3D(hasElevation())
                    .withTurnCosts(encodingManager.needsTurnCostsSupport())
                    .setSegmentSize(defaultSegmentSize)
                    .build();
            baseGraph.loadExisting();
            String storedProfiles = properties.get("profiles");
            String configuredProfiles = getProfilesString();
            if (!storedProfiles.equals(configuredProfiles))
                throw new IllegalStateException("Profiles do not match:"
                        + "\nGraphhopper config: " + configuredProfiles
                        + "\nGraph: " + storedProfiles
                        + "\nChange configuration to match the graph or delete " + baseGraph.getDirectory().getLocation());
            checkProfilesConsistency();

            postProcessing(false);
            directory.loadMMap();
            setFullyLoaded();
            return true;
        } finally {
            if (lock != null)
                lock.release();
        }
    }

    private String getProfilesString() {
        return profilesByName.values().stream().map(p -> p.getName() + "|" + p.getVersion()).collect(Collectors.joining(","));
    }

    private void checkProfilesConsistency() {
        if (profilesByName.isEmpty())
            throw new IllegalArgumentException("There has to be at least one profile");
        EncodingManager encodingManager = getEncodingManager();
        for (Profile profile : profilesByName.values()) {
            if (!encodingManager.getVehicles().contains(profile.getVehicle()))
                throw new IllegalArgumentException("Unknown vehicle '" + profile.getVehicle() + "' in profile: " + profile + ". " +
                        "Available vehicles: " + String.join(",", encodingManager.getVehicles()));
            BooleanEncodedValue turnRestrictionEnc = encodingManager.hasTurnEncodedValue(TurnRestriction.key(profile.getVehicle()))
                    ? encodingManager.getTurnBooleanEncodedValue(TurnRestriction.key(profile.getVehicle()))
                    : null;
            if (profile.isTurnCosts() && turnRestrictionEnc == null) {
                throw new IllegalArgumentException("The profile '" + profile.getName() + "' was configured with " +
                        "'turn_costs=true', but the corresponding vehicle '" + profile.getVehicle() + "' does not support turn costs." +
                        "\nYou need to add `|turn_costs=true` to the vehicle in `graph.vehicles`");
            }
            try {
                createWeighting(profile, new PMap());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Could not create weighting for profile: '" + profile.getName() + "'.\n" +
                        "Profile: " + profile + "\n" +
                        "Error: " + e.getMessage());
            }

            if (CustomWeighting.NAME.equals(profile.getWeighting()) && profile.getCustomModel() == null)
                throw new IllegalArgumentException("custom model for profile '" + profile.getName() + "' was empty");
            if (!CustomWeighting.NAME.equals(profile.getWeighting()) && profile.getCustomModel() != null)
                throw new IllegalArgumentException("profile '" + profile.getName() + "' has a custom model but " +
                        "weighting=" + profile.getWeighting() + " was defined");
        }

        Set<String> chProfileSet = new LinkedHashSet<>(chPreparationHandler.getCHProfiles().size());
        for (CHProfile chProfile : chPreparationHandler.getCHProfiles()) {
            boolean added = chProfileSet.add(chProfile.getProfile());
            if (!added) {
                throw new IllegalArgumentException("Duplicate CH reference to profile '" + chProfile.getProfile() + "'");
            }
            if (!profilesByName.containsKey(chProfile.getProfile())) {
                throw new IllegalArgumentException("CH profile references unknown profile '" + chProfile.getProfile() + "'");
            }
        }
        Map<String, LMProfile> lmProfileMap = new LinkedHashMap<>(lmPreparationHandler.getLMProfiles().size());
        for (LMProfile lmProfile : lmPreparationHandler.getLMProfiles()) {
            LMProfile previous = lmProfileMap.put(lmProfile.getProfile(), lmProfile);
            if (previous != null) {
                throw new IllegalArgumentException("Multiple LM profiles are using the same profile '" + lmProfile.getProfile() + "'");
            }
            if (!profilesByName.containsKey(lmProfile.getProfile())) {
                throw new IllegalArgumentException("LM profile references unknown profile '" + lmProfile.getProfile() + "'");
            }
            if (lmProfile.usesOtherPreparation() && !profilesByName.containsKey(lmProfile.getPreparationProfile())) {
                throw new IllegalArgumentException("LM profile references unknown preparation profile '" + lmProfile.getPreparationProfile() + "'");
            }
        }
        for (LMProfile lmProfile : lmPreparationHandler.getLMProfiles()) {
            if (lmProfile.usesOtherPreparation() && !lmProfileMap.containsKey(lmProfile.getPreparationProfile())) {
                throw new IllegalArgumentException("Unknown LM preparation profile '" + lmProfile.getPreparationProfile() + "' in LM profile '" + lmProfile.getProfile() + "' cannot be used as preparation_profile");
            }
            if (lmProfile.usesOtherPreparation() && lmProfileMap.get(lmProfile.getPreparationProfile()).usesOtherPreparation()) {
                throw new IllegalArgumentException("Cannot use '" + lmProfile.getPreparationProfile() + "' as preparation_profile for LM profile '" + lmProfile.getProfile() + "', because it uses another profile for preparation itself.");
            }
        }
    }

    public final CHPreparationHandler getCHPreparationHandler() {
        return chPreparationHandler;
    }

    private List<CHConfig> createCHConfigs(List<CHProfile> chProfiles) {
        List<CHConfig> chConfigs = new ArrayList<>();
        for (CHProfile chProfile : chProfiles) {
            Profile profile = profilesByName.get(chProfile.getProfile());
            if (profile.isTurnCosts()) {
                chConfigs.add(CHConfig.edgeBased(profile.getName(), createWeighting(profile, new PMap())));
            } else {
                chConfigs.add(CHConfig.nodeBased(profile.getName(), createWeighting(profile, new PMap())));
            }
        }
        return chConfigs;
    }

    public final LMPreparationHandler getLMPreparationHandler() {
        return lmPreparationHandler;
    }

    private List<LMConfig> createLMConfigs(List<LMProfile> lmProfiles) {
        List<LMConfig> lmConfigs = new ArrayList<>();
        for (LMProfile lmProfile : lmProfiles) {
            if (lmProfile.usesOtherPreparation())
                continue;
            Profile profile = profilesByName.get(lmProfile.getProfile());
            // Note that we have to make sure the weighting used for LM preparation does not include turn costs, because
            // the LM preparation is running node-based and the landmark weights will be wrong if there are non-zero
            // turn costs, see discussion in #1960
            // Running the preparation without turn costs is also useful to allow e.g. changing the u_turn_costs per
            // request (we have to use the minimum weight settings (= no turn costs) for the preparation)
            Weighting weighting = createWeighting(profile, new PMap(), true);
            lmConfigs.add(new LMConfig(profile.getName(), weighting));
        }
        return lmConfigs;
    }

    /**
     * Runs both after the import and when loading an existing Graph
     *
     * @param closeEarly release resources as early as possible
     */
    protected void postProcessing(boolean closeEarly) {
        initLocationIndex();
        importPublicTransit();

        if (closeEarly) {
            boolean includesCustomProfiles = profilesByName.values().stream().anyMatch(p -> CustomWeighting.NAME.equals(p.getWeighting()));
            if (!includesCustomProfiles)
                // when there are custom profiles we must not close way geometry or KVStorage, because
                // they might be needed to evaluate the custom weightings for the following preparations
                baseGraph.flushAndCloseGeometryAndNameStorage();
        }

        if (lmPreparationHandler.isEnabled())
            loadOrPrepareLM(closeEarly);

        if (closeEarly)
            // we needed the location index for the LM preparation, but we don't need it for CH
            locationIndex.close();

        if (chPreparationHandler.isEnabled())
            loadOrPrepareCH(closeEarly);
    }

    protected void importPublicTransit() {
    }

    void interpolateBridgesTunnelsAndFerries() {
        if (encodingManager.hasEncodedValue(RoadEnvironment.KEY)) {
            EnumEncodedValue<RoadEnvironment> roadEnvEnc = encodingManager.getEnumEncodedValue(RoadEnvironment.KEY, RoadEnvironment.class);
            StopWatch sw = new StopWatch().start();
            new EdgeElevationInterpolator(baseGraph.getBaseGraph(), roadEnvEnc, RoadEnvironment.TUNNEL).execute();
            float tunnel = sw.stop().getSeconds();
            sw = new StopWatch().start();
            new EdgeElevationInterpolator(baseGraph.getBaseGraph(), roadEnvEnc, RoadEnvironment.BRIDGE).execute();
            float bridge = sw.stop().getSeconds();
            // The SkadiProvider contains bathymetric data. For ferries this can result in bigger elevation changes
            // See #2098 for mor information
            sw = new StopWatch().start();
            new EdgeElevationInterpolator(baseGraph.getBaseGraph(), roadEnvEnc, RoadEnvironment.FERRY).execute();
            logger.info("Bridge interpolation " + (int) bridge + "s, " + "tunnel interpolation " + (int) tunnel + "s, ferry interpolation " + (int) sw.stop().getSeconds() + "s");
        }
    }

    public final Weighting createWeighting(Profile profile, PMap hints) {
        return createWeighting(profile, hints, false);
    }

    public final Weighting createWeighting(Profile profile, PMap hints, boolean disableTurnCosts) {
        return createWeightingFactory().createWeighting(profile, hints, disableTurnCosts);
    }

    protected WeightingFactory createWeightingFactory() {
        return new DefaultWeightingFactory(baseGraph.getBaseGraph(), getEncodingManager());
    }

    public GHResponse route(GHRequest request) {
        return createRouter().route(request);
    }

    private Router createRouter() {
        if (baseGraph == null || !fullyLoaded)
            throw new IllegalStateException("Do a successful call to load or importOrLoad before routing");
        if (baseGraph.isClosed())
            throw new IllegalStateException("You need to create a new GraphHopper instance as it is already closed");
        if (locationIndex == null)
            throw new IllegalStateException("Location index not initialized");

        return doCreateRouter(baseGraph, encodingManager, locationIndex, profilesByName, pathBuilderFactory,
                trMap, routerConfig, createWeightingFactory(), chGraphs, landmarks);
    }

    protected Router doCreateRouter(BaseGraph baseGraph, EncodingManager encodingManager, LocationIndex locationIndex, Map<String, Profile> profilesByName,
                                    PathDetailsBuilderFactory pathBuilderFactory, TranslationMap trMap, RouterConfig routerConfig,
                                    WeightingFactory weightingFactory, Map<String, RoutingCHGraph> chGraphs, Map<String, LandmarkStorage> landmarks) {
        return new Router(baseGraph, encodingManager, locationIndex, profilesByName, pathBuilderFactory,
                trMap, routerConfig, weightingFactory, chGraphs, landmarks
        );
    }

    protected LocationIndex createLocationIndex(Directory dir) {
        LocationIndexTree tmpIndex = new LocationIndexTree(baseGraph, dir);
        tmpIndex.setResolution(preciseIndexResolution);
        tmpIndex.setMaxRegionSearch(maxRegionSearch);
        if (!tmpIndex.loadExisting()) {
            ensureWriteAccess();
            tmpIndex.prepareIndex();
        }

        return tmpIndex;
    }

    /**
     * Initializes the location index after the import is done.
     */
    protected void initLocationIndex() {
        if (locationIndex != null)
            throw new IllegalStateException("Cannot initialize locationIndex twice!");

        locationIndex = createLocationIndex(baseGraph.getDirectory());
    }

    private String getCHProfileVersion(String profile) {
        return properties.get("graph.profiles.ch." + profile + ".version");
    }

    private void setCHProfileVersion(String profile, int version) {
        properties.put("graph.profiles.ch." + profile + ".version", version);
    }

    private String getLMProfileVersion(String profile) {
        return properties.get("graph.profiles.lm." + profile + ".version");
    }

    private void setLMProfileVersion(String profile, int version) {
        properties.put("graph.profiles.lm." + profile + ".version", version);
    }

    protected void loadOrPrepareCH(boolean closeEarly) {
        for (CHProfile profile : chPreparationHandler.getCHProfiles())
            if (!getCHProfileVersion(profile.getProfile()).isEmpty()
                    && !getCHProfileVersion(profile.getProfile()).equals("" + profilesByName.get(profile.getProfile()).getVersion()))
                throw new IllegalArgumentException("CH preparation of " + profile.getProfile() + " already exists in storage and doesn't match configuration");

        // we load ch graphs that already exist and prepare the other ones
        List<CHConfig> chConfigs = createCHConfigs(chPreparationHandler.getCHProfiles());
        Map<String, RoutingCHGraph> loaded = chPreparationHandler.load(baseGraph.getBaseGraph(), chConfigs);
        List<CHConfig> configsToPrepare = chConfigs.stream().filter(c -> !loaded.containsKey(c.getName())).collect(Collectors.toList());
        Map<String, PrepareContractionHierarchies.Result> prepared = prepareCH(closeEarly, configsToPrepare);

        // we map all profile names for which there is CH support to the according CH graphs
        chGraphs = new LinkedHashMap<>();
        for (CHProfile profile : chPreparationHandler.getCHProfiles()) {
            if (loaded.containsKey(profile.getProfile()) && prepared.containsKey(profile.getProfile()))
                throw new IllegalStateException("CH graph should be either loaded or prepared, but not both: " + profile.getProfile());
            else if (prepared.containsKey(profile.getProfile())) {
                setCHProfileVersion(profile.getProfile(), profilesByName.get(profile.getProfile()).getVersion());
                PrepareContractionHierarchies.Result res = prepared.get(profile.getProfile());
                chGraphs.put(profile.getProfile(), RoutingCHGraphImpl.fromGraph(baseGraph.getBaseGraph(), res.getCHStorage(), res.getCHConfig()));
            } else if (loaded.containsKey(profile.getProfile())) {
                chGraphs.put(profile.getProfile(), loaded.get(profile.getProfile()));
            } else
                throw new IllegalStateException("CH graph should be either loaded or prepared: " + profile.getProfile());
        }
    }

    protected Map<String, PrepareContractionHierarchies.Result> prepareCH(boolean closeEarly, List<CHConfig> configsToPrepare) {
        if (!configsToPrepare.isEmpty())
            ensureWriteAccess();
        if (!baseGraph.isFrozen())
            baseGraph.freeze();
        return chPreparationHandler.prepare(baseGraph, properties, configsToPrepare, closeEarly);
    }

    /**
     * For landmarks it is required to always call this method: either it creates the landmark data or it loads it.
     */
    protected void loadOrPrepareLM(boolean closeEarly) {
        for (LMProfile profile : lmPreparationHandler.getLMProfiles())
            if (!getLMProfileVersion(profile.getProfile()).isEmpty()
                    && !getLMProfileVersion(profile.getProfile()).equals("" + profilesByName.get(profile.getProfile()).getVersion()))
                throw new IllegalArgumentException("LM preparation of " + profile.getProfile() + " already exists in storage and doesn't match configuration");

        // we load landmark storages that already exist and prepare the other ones
        List<LMConfig> lmConfigs = createLMConfigs(lmPreparationHandler.getLMProfiles());
        List<LandmarkStorage> loaded = lmPreparationHandler.load(lmConfigs, baseGraph, encodingManager);
        List<LMConfig> loadedConfigs = loaded.stream().map(LandmarkStorage::getLMConfig).collect(Collectors.toList());
        List<LMConfig> configsToPrepare = lmConfigs.stream().filter(c -> !loadedConfigs.contains(c)).collect(Collectors.toList());
        List<PrepareLandmarks> prepared = prepareLM(closeEarly, configsToPrepare);

        // we map all profile names for which there is LM support to the according LM storages
        landmarks = new LinkedHashMap<>();
        for (LMProfile lmp : lmPreparationHandler.getLMProfiles()) {
            // cross-querying
            String prepProfile = lmp.usesOtherPreparation() ? lmp.getPreparationProfile() : lmp.getProfile();
            Optional<LandmarkStorage> loadedLMS = loaded.stream().filter(lms -> lms.getLMConfig().getName().equals(prepProfile)).findFirst();
            Optional<PrepareLandmarks> preparedLMS = prepared.stream().filter(pl -> pl.getLandmarkStorage().getLMConfig().getName().equals(prepProfile)).findFirst();
            if (loadedLMS.isPresent() && preparedLMS.isPresent())
                throw new IllegalStateException("LM should be either loaded or prepared, but not both: " + prepProfile);
            else if (preparedLMS.isPresent()) {
                setLMProfileVersion(lmp.getProfile(), profilesByName.get(lmp.getProfile()).getVersion());
                landmarks.put(lmp.getProfile(), preparedLMS.get().getLandmarkStorage());
            } else
                loadedLMS.ifPresent(landmarkStorage -> landmarks.put(lmp.getProfile(), landmarkStorage));
        }
    }

    protected List<PrepareLandmarks> prepareLM(boolean closeEarly, List<LMConfig> configsToPrepare) {
        if (!configsToPrepare.isEmpty())
            ensureWriteAccess();
        if (!baseGraph.isFrozen())
            baseGraph.freeze();
        return lmPreparationHandler.prepare(configsToPrepare, baseGraph, encodingManager, properties, locationIndex, closeEarly);
    }

    /**
     * Internal method to clean up the graph.
     */
    protected void cleanUp() {
        PrepareRoutingSubnetworks preparation = new PrepareRoutingSubnetworks(baseGraph.getBaseGraph(), buildSubnetworkRemovalJobs());
        preparation.setMinNetworkSize(minNetworkSize);
        preparation.setThreads(subnetworksThreads);
        preparation.doWork();
        properties.put("profiles", getProfilesString());
        logger.info("nodes: " + Helper.nf(baseGraph.getNodes()) + ", edges: " + Helper.nf(baseGraph.getEdges()));

        logger.info("Start marking dead-ends");
        StopWatch sw = StopWatch.started();
        PrepareDeadEnds prepareDeadEnds = new PrepareDeadEnds(baseGraph);
        for (Profile profile : profilesByName.values()) {
            if (profile.isTurnCosts()) {
                BooleanEncodedValue deadEndEnc = encodingManager.getBooleanEncodedValue(DeadEnd.key(profile.getVehicle()));
                BooleanEncodedValue subnetworkEnc = encodingManager.getBooleanEncodedValue(Subnetwork.key(profile.getName()));
                // We disable turn costs for the dead-end search.
                Weighting weighting = createWeighting(profile, new PMap(), true);
                prepareDeadEnds.findDeadEndUTurns(weighting, deadEndEnc, subnetworkEnc);
            }
        }
        logger.info("Finished marking dead-ends, took: " + sw.stop().getSeconds() + "s");
    }



    private List<PrepareJob> buildSubnetworkRemovalJobs() {
        List<PrepareJob> jobs = new ArrayList<>();
        for (Profile profile : profilesByName.values()) {
            Weighting weighting = createWeighting(profile, new PMap());
            Weighting w = new AbstractAdjustedWeighting(weighting) {
                @Override
                public double calcTurnWeight(int inEdge, int viaNode, int outEdge) {
                    // We only want to make sure the graph is fully connected assuming finite u-turn
                    // costs. Here we have to set it to zero explicitly, because otherwise the
                    // u-turn costs would be infinite everywhere except at dead-ends. But we run the
                    // dead-end detection after the subnetwork search.
                    if (inEdge == outEdge) return 0;
                    return weighting.calcTurnWeight(inEdge, viaNode, outEdge);
                }

                @Override
                public String getName() {
                    return weighting.getName();
                }
            };
            jobs.add(new PrepareJob(encodingManager.getBooleanEncodedValue(Subnetwork.key(profile.getName())), w));
        }
        return jobs;
    }

    protected void flush() {
        logger.info("flushing graph " + getBaseGraphString() + ", details:" + baseGraph.toDetailsString() + ", "
                + getMemInfo() + ")");
        baseGraph.flush();
        properties.flush();
        logger.info("flushed graph " + getMemInfo() + ")");
        setFullyLoaded();
    }

    /**
     * Releases all associated resources like memory or files. But it does not remove them. To
     * remove the files created in graphhopperLocation you have to call clean().
     */
    public void close() {
        if (baseGraph != null)
            baseGraph.close();
        if (properties != null)
            properties.close();

        chGraphs.values().forEach(RoutingCHGraph::close);
        landmarks.values().forEach(LandmarkStorage::close);

        if (locationIndex != null)
            locationIndex.close();

        try {
            lockFactory.forceRemove(fileLockName, true);
        } catch (Exception ex) {
            // silently fail e.g. on Windows where we cannot remove an unreleased native lock
        }
    }

    /**
     * Removes the on-disc routing files. Call only after calling close or before importOrLoad or
     * load
     */
    public void clean() {
        if (getGraphHopperLocation().isEmpty())
            throw new IllegalStateException("Cannot clean GraphHopper without specified graphHopperLocation");

        File folder = new File(getGraphHopperLocation());
        removeDir(folder);
    }

    protected void ensureNotLoaded() {
        if (fullyLoaded)
            throw new IllegalStateException("No configuration changes are possible after loading the graph");
    }

    protected void ensureWriteAccess() {
        if (!allowWrites)
            throw new IllegalStateException("Writes are not allowed!");
    }

    private void setFullyLoaded() {
        fullyLoaded = true;
    }

    public boolean getFullyLoaded() {
        return fullyLoaded;
    }

    public RouterConfig getRouterConfig() {
        return routerConfig;
    }

    public OSMReaderConfig getReaderConfig() {
        return osmReaderConfig;
    }

    public static JsonFeatureCollection resolveCustomAreas(String customAreasDirectory) {
        JsonFeatureCollection globalAreas = new JsonFeatureCollection();
        if (!customAreasDirectory.isEmpty()) {
            ObjectMapper mapper = new ObjectMapper().registerModule(new JtsModule());
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(customAreasDirectory), "*.{geojson,json}")) {
                for (Path customAreaFile : stream) {
                    try (BufferedReader reader = Files.newBufferedReader(customAreaFile, StandardCharsets.UTF_8)) {
                        globalAreas.getFeatures().addAll(mapper.readValue(reader, JsonFeatureCollection.class).getFeatures());
                    }
                }
                logger.info("Will make " + globalAreas.getFeatures().size() + " areas available to all custom profiles. Found in " + customAreasDirectory);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return globalAreas;
    }

    public static List<Profile> resolveCustomModelFiles(String customModelFolder, List<Profile> profiles, JsonFeatureCollection globalAreas) {
        ObjectMapper jsonOM = Jackson.newObjectMapper();
        List<Profile> newProfiles = new ArrayList<>();
        for (Profile profile : profiles) {
            if (!CustomWeighting.NAME.equals(profile.getWeighting())) {
                newProfiles.add(profile);
                continue;
            }
            Object cm = profile.getHints().getObject(CustomModel.KEY, null);
            CustomModel customModel;
            if (cm != null) {
                if (!profile.getHints().getObject("custom_model_files", Collections.emptyList()).isEmpty())
                    throw new IllegalArgumentException("Do not use custom_model_files and custom_model together");
                try {
                    // custom_model can be an object tree (read from config) or an object (e.g. from tests)
                    customModel = jsonOM.readValue(jsonOM.writeValueAsBytes(cm), CustomModel.class);
                    newProfiles.add(profile.setCustomModel(customModel));
                } catch (Exception ex) {
                    throw new RuntimeException("Cannot load custom_model from " + cm + " for profile " + profile.getName()
                            + ". If you are trying to load from a file, use 'custom_model_files' instead.", ex);
                }
            } else {
                if (!profile.getHints().getString("custom_model_file", "").isEmpty())
                    throw new IllegalArgumentException("Since 8.0 you must use a custom_model_files array instead of custom_model_file string");
                List<String> customModelFileNames = profile.getHints().getObject("custom_model_files", null);
                if (customModelFileNames == null)
                    throw new IllegalArgumentException("Missing 'custom_model' or 'custom_model_files' field in profile '"
                            + profile.getName() + "'. To use default specify custom_model_files: []");
                if (customModelFileNames.isEmpty()) {
                    newProfiles.add(profile.setCustomModel(customModel = new CustomModel()));
                } else {
                    customModel = new CustomModel();
                    for (String file : customModelFileNames) {
                        if (file.contains(File.separator))
                            throw new IllegalArgumentException("Use custom_models.directory for the custom_model_files parent");
                        if (!file.endsWith(".json"))
                            throw new IllegalArgumentException("Yaml is no longer supported, see #2672. Use JSON with optional comments //");

                        try {
                            String string;
                            // 1. try to load custom model from jar
                            InputStream is = GHUtility.class.getResourceAsStream("/com/graphhopper/custom_models/" + file);
                            if (is != null) {
                                string = readJSONFileWithoutComments(new InputStreamReader(is));
                            } else {
                                // 2. try to load custom model file from external location
                                // dropwizard makes it very hard to find out the folder of config.yml -> use an extra parameter for the folder
                                string = readJSONFileWithoutComments(Paths.get(customModelFolder).
                                        resolve(file).toFile().getAbsolutePath());
                            }
                            customModel = CustomModel.merge(customModel, jsonOM.readValue(string, CustomModel.class));
                        } catch (IOException ex) {
                            throw new RuntimeException("Cannot load custom_model from location " + file + ", profile:" + profile.getName(), ex);
                        }
                    }

                    newProfiles.add(profile.setCustomModel(customModel));
                }
            }

            // we can fill in all areas here as in the created template we include only the areas that are used in
            // statements (see CustomModelParser)
            customModel.addAreas(globalAreas);
        }
        return newProfiles;
    }
}
