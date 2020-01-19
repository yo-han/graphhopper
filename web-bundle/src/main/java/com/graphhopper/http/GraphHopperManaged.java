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

package com.graphhopper.http;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.graphhopper.GraphHopper;
import com.graphhopper.jackson.Jackson;
import com.graphhopper.json.geo.JsonFeatureCollection;
import com.graphhopper.reader.gtfs.GraphHopperGtfs;
import com.graphhopper.reader.osm.GraphHopperOSM;
import com.graphhopper.routing.lm.LandmarkStorage;
import com.graphhopper.routing.util.CustomModel;
import com.graphhopper.routing.util.spatialrules.SpatialRuleLookupHelper;
import com.graphhopper.util.CmdArgs;
import com.graphhopper.util.Helper;
import com.graphhopper.util.Parameters;
import com.graphhopper.util.shapes.BBox;
import io.dropwizard.lifecycle.Managed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Arrays;
import java.util.Map;

import static com.graphhopper.util.Helper.UTF_CS;

public class GraphHopperManaged implements Managed {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final GraphHopper graphHopper;

    public GraphHopperManaged(CmdArgs configuration, ObjectMapper objectMapper) {
        ObjectMapper localObjectMapper = objectMapper.copy();
        localObjectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        String splitAreaLocation = configuration.get(Parameters.Landmark.PREPARE + "split_area_location", "");
        JsonFeatureCollection landmarkSplittingFeatureCollection;
        try (Reader reader = splitAreaLocation.isEmpty() ? new InputStreamReader(LandmarkStorage.class.getResource("map.geo.json").openStream(), UTF_CS) : new InputStreamReader(new FileInputStream(splitAreaLocation), UTF_CS)) {
            landmarkSplittingFeatureCollection = localObjectMapper.readValue(reader, JsonFeatureCollection.class);
        } catch (IOException e1) {
            logger.error("Problem while reading border map GeoJSON. Skipping this.", e1);
            landmarkSplittingFeatureCollection = null;
        }
        if (configuration.has("gtfs.file")) {
            graphHopper = new GraphHopperGtfs(configuration);
        } else {
            graphHopper = new GraphHopperOSM(landmarkSplittingFeatureCollection).forServer();
        }
        String spatialRuleLocation = configuration.get("spatial_rules.location", "");
        if (!spatialRuleLocation.isEmpty()) {
            final BBox maxBounds = BBox.parseBBoxString(configuration.get("spatial_rules.max_bbox", "-180, 180, -90, 90"));
            try (final InputStreamReader reader = new InputStreamReader(new FileInputStream(spatialRuleLocation), UTF_CS)) {
                JsonFeatureCollection jsonFeatureCollection = localObjectMapper.readValue(reader, JsonFeatureCollection.class);
                SpatialRuleLookupHelper.buildAndInjectSpatialRuleIntoGH(graphHopper, maxBounds, jsonFeatureCollection);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        String customModelLocation = configuration.get("graph.custom_models.directory", "");
        if (!customModelLocation.isEmpty()) {
            ObjectMapper yamlOM = Jackson.initObjectMapper(new ObjectMapper(new YAMLFactory()));
            for (Map.Entry<String, File> entry : Helper.listFiles(new File(customModelLocation), Arrays.asList("yaml", "yml"))) {
                try {
                    CustomModel customModel = yamlOM.readValue(entry.getValue(), CustomModel.class);
                    graphHopper.putCustomModel(entry.getKey(), customModel);
                } catch (Exception ex) {
                    throw new RuntimeException("Cannot custom_model from " + entry.getValue(), ex);
                }
            }
        }

        graphHopper.init(configuration);
    }

    @Override
    public void start() {
        graphHopper.importOrLoad();
        logger.info("loaded graph at:" + graphHopper.getGraphHopperLocation()
                + ", data_reader_file:" + graphHopper.getDataReaderFile()
                + ", encoded values:" + graphHopper.getEncodingManager().toEncodedValuesAsString()
                + ", " + graphHopper.getGraphHopperStorage().toDetailsString());
    }

    public GraphHopper getGraphHopper() {
        return graphHopper;
    }

    @Override
    public void stop() {
        graphHopper.close();
    }


}
