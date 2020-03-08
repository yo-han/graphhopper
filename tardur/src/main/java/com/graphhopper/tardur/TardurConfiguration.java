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

package com.graphhopper.tardur;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.gtfs.dropwizard.RealtimeBundleConfiguration;
import com.graphhopper.gtfs.dropwizard.RealtimeConfiguration;
import com.graphhopper.http.GraphHopperBundleConfiguration;
import io.dropwizard.Configuration;
import io.dropwizard.bundles.assets.AssetsBundleConfiguration;
import io.dropwizard.bundles.assets.AssetsConfiguration;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.util.Map;

public class TardurConfiguration extends Configuration implements GraphHopperBundleConfiguration, RealtimeBundleConfiguration, AssetsBundleConfiguration {

    @NotNull
    @JsonProperty
    private final GraphHopperConfig graphhopper = new GraphHopperConfig();

    @Valid
    @JsonProperty
    private final AssetsConfiguration assets = AssetsConfiguration.builder().build();

    @JsonProperty
    private final RealtimeConfiguration gtfsRealtime = new RealtimeConfiguration();

    public TardurConfiguration() {
    }

    @Override
    public GraphHopperConfig getGraphHopperConfiguration() {
        return graphhopper;
    }

    private Map<String, Map<String, String>> viewRendererConfiguration;

    @JsonProperty("viewRendererConfiguration")
    public Map<String, Map<String, String>> getViewRendererConfiguration() {
        return viewRendererConfiguration;
    }

    @JsonProperty("viewRendererConfiguration")
    public void setViewRendererConfiguration(Map<String, Map<String, String>> viewRendererConfiguration) {
        this.viewRendererConfiguration = viewRendererConfiguration;
    }

    @Override
    public AssetsConfiguration getAssetsConfiguration() {
        return assets;
    }

    @Override
    public RealtimeConfiguration gtfsrealtime() {
        return gtfsRealtime;
    }
}