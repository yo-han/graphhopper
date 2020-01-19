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
package com.graphhopper.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.graphhopper.*;
import com.graphhopper.http.WebHelper;
import com.graphhopper.jackson.Jackson;
import com.graphhopper.routing.util.CustomRequest;
import com.graphhopper.routing.util.HintsMap;
import com.graphhopper.util.Constants;
import com.graphhopper.util.InstructionList;
import com.graphhopper.util.StopWatch;
import com.graphhopper.util.gpx.GpxFromInstructions;
import com.graphhopper.util.shapes.GHPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.graphhopper.util.Parameters.Details.PATH_DETAILS;
import static com.graphhopper.util.Parameters.Routing.*;

/**
 * Resource to use GraphHopper in a remote client application like mobile or browser. Note: If type
 * is json it returns the points in GeoJson array format [longitude,latitude] unlike the format "lat,lon"
 * used for the request. See the full API response format in docs/web/api-doc.md
 *
 * @author Peter Karich
 */
@Path("route")
public class RouteResource {

    private static final Logger logger = LoggerFactory.getLogger(RouteResource.class);

    private final GraphHopperAPI graphHopper;
    private final Boolean hasElevation;
    private final ObjectMapper yamlOM;

    @Inject
    public RouteResource(GraphHopperAPI graphHopper, @Named("hasElevation") Boolean hasElevation) {
        this.graphHopper = graphHopper;
        this.hasElevation = hasElevation;
        this.yamlOM = Jackson.initObjectMapper(new ObjectMapper(new YAMLFactory()));
    }

    @GET
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, "application/gpx+xml"})
    public Response doGet(
            @Context HttpServletRequest httpReq,
            @Context UriInfo uriInfo,
            @Context ContainerRequestContext rc,
            @QueryParam(WAY_POINT_MAX_DISTANCE) @DefaultValue("1") double minPathPrecision,
            @QueryParam("point") List<GHPoint> requestPoints,
            @QueryParam("type") @DefaultValue("json") String type,
            @QueryParam(INSTRUCTIONS) @DefaultValue("true") boolean instructions,
            @QueryParam(CALC_POINTS) @DefaultValue("true") boolean calcPoints,
            @QueryParam("elevation") @DefaultValue("false") boolean enableElevation,
            @QueryParam("points_encoded") @DefaultValue("true") boolean pointsEncoded,
            @QueryParam("vehicle") @DefaultValue("car") String vehicleStr,
            @QueryParam("weighting") @DefaultValue("fastest") String weighting,
            @QueryParam("algorithm") @DefaultValue("") String algoStr,
            @QueryParam("locale") @DefaultValue("en") String localeStr,
            @QueryParam(POINT_HINT) List<String> pointHints,
            @QueryParam(CURBSIDE) List<String> curbsides,
            @QueryParam(SNAP_PREVENTION) List<String> snapPreventions,
            @QueryParam(PATH_DETAILS) List<String> pathDetails,
            @QueryParam("heading") List<Double> favoredHeadings,
            @QueryParam("gpx.route") @DefaultValue("true") boolean withRoute /* default to false for the route part in next API version, see #437 */,
            @QueryParam("gpx.track") @DefaultValue("true") boolean withTrack,
            @QueryParam("gpx.waypoints") @DefaultValue("false") boolean withWayPoints,
            @QueryParam("gpx.trackname") @DefaultValue("GraphHopper Track") String trackName,
            @QueryParam("gpx.millis") String timeString) {
        boolean writeGPX = "gpx".equalsIgnoreCase(type);
        instructions = writeGPX || instructions;

        StopWatch sw = new StopWatch().start();

        if (requestPoints.isEmpty())
            throw new IllegalArgumentException("You have to pass at least one point");
        if (enableElevation && !hasElevation)
            throw new IllegalArgumentException("Elevation not supported!");
        if (favoredHeadings.size() > 1 && favoredHeadings.size() != requestPoints.size())
            throw new IllegalArgumentException("The number of 'heading' parameters must be <= 1 "
                    + "or equal to the number of points (" + requestPoints.size() + ")");
        if (pointHints.size() > 0 && pointHints.size() != requestPoints.size())
            throw new IllegalArgumentException("If you pass " + POINT_HINT + ", you need to pass exactly one hint for every point, empty hints will be ignored");
        if (curbsides.size() > 0 && curbsides.size() != requestPoints.size())
            throw new IllegalArgumentException("If you pass " + CURBSIDE + ", you need to pass exactly one curbside for every point, empty curbsides will be ignored");

        GHRequest request;
        if (favoredHeadings.size() > 0) {
            // if only one favored heading is specified take as start heading
            if (favoredHeadings.size() == 1) {
                List<Double> paddedHeadings = new ArrayList<>(Collections.nCopies(requestPoints.size(), Double.NaN));
                paddedHeadings.set(0, favoredHeadings.get(0));
                request = new GHRequest(requestPoints, paddedHeadings);
            } else {
                request = new GHRequest(requestPoints, favoredHeadings);
            }
        } else {
            request = new GHRequest(requestPoints);
        }

        initHints(request.getHints(), uriInfo.getQueryParameters());
        translateTurnCostsParamToEdgeBased(request, uriInfo.getQueryParameters());
        enableEdgeBasedIfThereAreCurbsides(curbsides, request);
        request.setVehicle(vehicleStr).
                setWeighting(weighting).
                setAlgorithm(algoStr).
                setLocale(localeStr).
                setPointHints(pointHints).
                setCurbsides(curbsides).
                setSnapPreventions(snapPreventions).
                setPathDetails(pathDetails).
                getHints().
                put(CALC_POINTS, calcPoints).
                put(INSTRUCTIONS, instructions).
                put(WAY_POINT_MAX_DISTANCE, minPathPrecision);

        GHResponse ghResponse = graphHopper.route(request);

        float took = sw.stop().getSeconds();
        String infoStr = httpReq.getRemoteAddr() + " " + httpReq.getLocale() + " " + httpReq.getHeader("User-Agent");
        String logStr = httpReq.getQueryString() + " " + infoStr + " " + requestPoints + ", took:"
                + took + ", " + algoStr + ", " + weighting + ", " + vehicleStr;

        if (ghResponse.hasErrors()) {
            logger.error(logStr + ", errors:" + ghResponse.getErrors());
            throw new MultiException(ghResponse.getErrors());
        } else {
            logger.info(logStr + ", alternatives: " + ghResponse.getAll().size()
                    + ", distance0: " + ghResponse.getBest().getDistance()
                    + ", weight0: " + ghResponse.getBest().getRouteWeight()
                    + ", time0: " + Math.round(ghResponse.getBest().getTime() / 60000f) + "min"
                    + ", points0: " + ghResponse.getBest().getPoints().getSize()
                    + ", debugInfo: " + ghResponse.getDebugInfo());
            return writeGPX ?
                    gpxSuccessResponseBuilder(ghResponse, timeString, trackName, enableElevation, withRoute, withTrack, withWayPoints, Constants.VERSION).
                            header("X-GH-Took", "" + Math.round(took * 1000)).
                            build()
                    :
                    Response.ok(WebHelper.jsonObject(ghResponse, instructions, calcPoints, enableElevation, pointsEncoded, took)).
                            header("X-GH-Took", "" + Math.round(took * 1000)).
                            build();
        }
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, "application/gpx+xml"})
    public Response doPost(GHRequest request, @Context HttpServletRequest httpReq) {
        if (request == null)
            throw new IllegalArgumentException("Empty request");

        StopWatch sw = new StopWatch().start();
        GHResponse ghResponse = new GHResponse();
        if (request instanceof CustomRequest) {
            if (!(graphHopper instanceof GraphHopper))
                throw new IllegalStateException("CustomRequest requires GraphHopper base class");
            request.setWeighting("custom").getHints().put("ch.disable", true);
            ((GraphHopper) graphHopper).calcPaths(request, ghResponse, ((CustomRequest) request).getModel());
        } else {
            ghResponse = graphHopper.route(request);
        }

        boolean instructions = request.getHints().getBool(INSTRUCTIONS, true);
        boolean writeGPX = "gpx".equalsIgnoreCase(request.getHints().get("type", "json"));
        instructions = writeGPX || instructions;
        boolean enableElevation = request.getHints().getBool("elevation", false);
        boolean calcPoints = request.getHints().getBool(CALC_POINTS, true);
        boolean pointsEncoded = request.getHints().getBool("points_encoded", true);

        // default to false for the route part in next API version, see #437
        boolean withRoute = request.getHints().getBool("gpx.route", true);
        boolean withTrack = request.getHints().getBool("gpx.track", true);
        boolean withWayPoints = request.getHints().getBool("gpx.waypoints", false);
        String trackName = request.getHints().get("gpx.trackname", "GraphHopper Track");
        String timeString = request.getHints().get("gpx.millis", "");
        float took = sw.stop().getSeconds();
        String logStr = (httpReq.getQueryString() == null ? "-" : httpReq.getQueryString())
                + " " + httpReq.getRemoteAddr() + " " + httpReq.getLocale() + " " + httpReq.getHeader("User-Agent")
                + " " + request.getPoints().size() + ", took:" + took + ", " + request.getAlgorithm()
                + ", " + request.getWeighting() + ", " + request.getVehicle();

        if (ghResponse.hasErrors()) {
            logger.error(logStr + ", errors:" + ghResponse.getErrors());
            throw new MultiException(ghResponse.getErrors());
        } else {
            logger.info(logStr + ", alternatives: " + ghResponse.getAll().size()
                    + ", distance0: " + ghResponse.getBest().getDistance()
                    + ", weight0: " + ghResponse.getBest().getRouteWeight()
                    + ", time0: " + Math.round(ghResponse.getBest().getTime() / 60000f) + "min"
                    + ", points0: " + ghResponse.getBest().getPoints().getSize()
                    + ", debugInfo: " + ghResponse.getDebugInfo());
            return writeGPX ?
                    gpxSuccessResponseBuilder(ghResponse, timeString, trackName, enableElevation, withRoute, withTrack, withWayPoints, Constants.VERSION).
                            header("X-GH-Took", "" + Math.round(took * 1000)).
                            build()
                    :
                    Response.ok(WebHelper.jsonObject(ghResponse, instructions, calcPoints, enableElevation, pointsEncoded, took)).
                            header("X-GH-Took", "" + Math.round(took * 1000)).
                            build();
        }
    }

    @POST
    @Consumes({"text/x-yaml", "text/yaml", "application/x-yaml", "application/yaml"})
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, "application/gpx+xml"})
    public Response doPost(String yaml, @Context HttpServletRequest httpReq) {
        CustomRequest customRequest;
        try {
            customRequest = yamlOM.readValue(yaml, CustomRequest.class);
        } catch (Exception ex) {
            // TODO should we really provide this much details to API users?
            throw new IllegalArgumentException("Incorrect YAML: " + ex.getMessage(), ex);
        }
        return doPost(customRequest, httpReq);
    }

    private void enableEdgeBasedIfThereAreCurbsides(List<String> curbsides, GHRequest request) {
        if (!curbsides.isEmpty()) {
            if (!request.getHints().getBool(EDGE_BASED, true)) {
                throw new IllegalArgumentException("Disabling '" + EDGE_BASED + "' when using '" + CURBSIDE + "' is not allowed");
            } else {
                request.getHints().put(EDGE_BASED, true);
            }
        }
    }

    private void translateTurnCostsParamToEdgeBased(GHRequest request, MultivaluedMap<String, String> queryParams) {
        if (queryParams.containsKey(TURN_COSTS)) {
            List<String> turnCosts = queryParams.get(TURN_COSTS);
            if (turnCosts.size() != 1) {
                throw new IllegalArgumentException("You may only specify the turn_costs parameter once");
            }
            request.getHints().put(EDGE_BASED, turnCosts.get(0));
        }
    }

    private static Response.ResponseBuilder gpxSuccessResponseBuilder(GHResponse ghRsp, String timeString, String
            trackName, boolean enableElevation, boolean withRoute, boolean withTrack, boolean withWayPoints, String version) {
        if (ghRsp.getAll().size() > 1) {
            throw new IllegalArgumentException("Alternatives are currently not yet supported for GPX");
        }

        long time = timeString != null ? Long.parseLong(timeString) : System.currentTimeMillis();
        InstructionList instructions = ghRsp.getBest().getInstructions();
        return Response.ok(GpxFromInstructions.createGPX(instructions, trackName, time, enableElevation, withRoute, withTrack, withWayPoints, version, instructions.getTr()), "application/gpx+xml").
                header("Content-Disposition", "attachment;filename=" + "GraphHopper.gpx");
    }

    static void initHints(HintsMap m, MultivaluedMap<String, String> parameterMap) {
        for (Map.Entry<String, List<String>> e : parameterMap.entrySet()) {
            if (e.getValue().size() == 1) {
                m.put(e.getKey(), e.getValue().get(0));
            } else {
                // Do nothing.
                // TODO: this is dangerous: I can only silently swallow
                // the forbidden multiparameter. If I comment-in the line below,
                // I get an exception, because "point" regularly occurs
                // multiple times.
                // I think either unknown parameters (hints) should be allowed
                // to be multiparameters, too, or we shouldn't use them for
                // known parameters either, _or_ known parameters
                // must be filtered before they come to this code point,
                // _or_ we stop passing unknown parameters alltogether..
                //
                // throw new WebApplicationException(String.format("This query parameter (hint) is not allowed to occur multiple times: %s", e.getKey()));
            }
        }
    }

}
