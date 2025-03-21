/*
COPYRIGHT STATUS:
Dec 1st 2001, Fermi National Accelerator Laboratory (FNAL) documents and
software are sponsored by the U.S. Department of Energy under Contract No.
DE-AC02-76CH03000. Therefore, the U.S. Government retains a  world-wide
non-exclusive, royalty-free license to publish or reproduce these documents
and software for U.S. Government purposes.  All documents and software
available from this server are protected under the U.S. and Foreign
Copyright Laws, and FNAL reserves all rights.

Distribution of the software available from this server is free of
charge subject to the user following the terms of the Fermitools
Software Legal Information.

Redistribution and/or modification of the software shall be accompanied
by the Fermitools Software Legal Information  (including the copyright
notice).

The user is asked to feed back problems, benefits, and/or suggestions
about the software to the Fermilab Software Providers.

Neither the name of Fermilab, the  URA, nor the names of the contributors
may be used to endorse or promote products derived from this software
without specific prior written permission.

DISCLAIMER OF LIABILITY (BSD):

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED  WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED  WARRANTIES OF MERCHANTABILITY AND FITNESS
FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL FERMILAB,
OR THE URA, OR THE U.S. DEPARTMENT of ENERGY, OR CONTRIBUTORS BE LIABLE
FOR  ANY  DIRECT, INDIRECT,  INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES  (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT
OF SUBSTITUTE  GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY  OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT  OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE  POSSIBILITY OF SUCH DAMAGE.

Liabilities of the Government:

This software is provided by URA, independent from its Prime Contract
with the U.S. Department of Energy. URA is acting independently from
the Government and in its own private capacity and is not acting on
behalf of the U.S. Government, nor as its contractor nor its agent.
Correspondingly, it is understood and agreed that the U.S. Government
has no connection to this software and in no manner whatsoever shall
be liable for nor assume any responsibility or obligation for any claim,
cost, or damages arising out of or resulting from the use of the software
available from this server.

Export Control:

All documents and software available from this server are subject to U.S.
export control laws.  Anyone downloading information from this server is
obligated to secure any necessary Government licenses before exporting
documents or software obtained from this server.
 */
package org.dcache.restful.resources.bulk;

import static org.dcache.http.AuthenticationHandler.getLoginAttributes;
import static org.dcache.restful.util.HttpServletRequests.getUserRootAwareTargetPrefix;
import static org.dcache.restful.util.JSONUtils.newBadRequestException;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import diskCacheV111.util.FsPath;
import diskCacheV111.util.PnfsHandler;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.swagger.annotations.Authorization;
import io.swagger.annotations.Example;
import io.swagger.annotations.ExampleProperty;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Named;
import javax.security.auth.Subject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.PATCH;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.dcache.auth.attributes.LoginAttributes;
import org.dcache.auth.attributes.Restriction;
import org.dcache.auth.attributes.Restrictions;
import org.dcache.cells.CellStub;
import org.dcache.http.PathMapper;
import org.dcache.restful.util.HandlerBuilders;
import org.dcache.restful.util.RequestUser;
import org.dcache.restful.util.bulk.BulkServiceCommunicator;
import org.dcache.services.bulk.BulkArchivedRequestInfo;
import org.dcache.services.bulk.BulkArchivedRequestInfoMessage;
import org.dcache.services.bulk.BulkArchivedSummaryFilter;
import org.dcache.services.bulk.BulkArchivedSummaryInfo;
import org.dcache.services.bulk.BulkArchivedSummaryInfoMessage;
import org.dcache.services.bulk.BulkRequest;
import org.dcache.services.bulk.BulkRequest.Depth;
import org.dcache.services.bulk.BulkRequestCancelMessage;
import org.dcache.services.bulk.BulkRequestClearMessage;
import org.dcache.services.bulk.BulkRequestInfo;
import org.dcache.services.bulk.BulkRequestListMessage;
import org.dcache.services.bulk.BulkRequestMessage;
import org.dcache.services.bulk.BulkRequestStatus;
import org.dcache.services.bulk.BulkRequestStatusMessage;
import org.dcache.services.bulk.BulkRequestSummary;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

/**
 * <p>RESTful API to the BulkService.</p>
 *
 * @version v1.0
 */
@Component
@Api(value = "bulk-requests", authorizations = {@Authorization("basicAuth")})
@Path("/bulk-requests")
public final class BulkResources {

    @Context
    private HttpServletRequest request;

    @Inject
    private BulkServiceCommunicator service;

    @Inject
    private PathMapper pathMapper;

    @Inject
    @Named("pnfs-stub")
    private CellStub pnfsmanager;

    /**
     * @return List of bulk request summaries that have not been cleared.
     * If the client includes no query string then the response contains all bulk requests
     * that have not been cleared.  If the client specified a query string then the response
     * contains all bulk requests that match the query string arguments and have not been cleared.
     * <p>
     * @status A comma-separated list of non-repeating elements, each of which is one of: queued,
     * started, completed, cancelled.
     * @owner A comma-separated list of owners to match; unspecified returns all requests.
     */
    @GET
    @ApiOperation("Get the summary info of current bulk operations.  If nextId != -1 "
          + "retry using the offset = nextId to fetch more requests.")
    @ApiResponses({
          @ApiResponse(code = 400, message = "Bad request"),
          @ApiResponse(code = 500, message = "Internal Server Error")
    })
    @Produces(MediaType.APPLICATION_JSON)
    public List<BulkRequestSummary> getRequests(
          @ApiParam("A comma-separated list of non-repeating elements, "
                + "each of which is one of: queued, started, completed, "
                + "cancelled.")
          @QueryParam("status") String status,
          @ApiParam("A comma-separated list of owners to match; unspecified returns all requests.")
          @QueryParam("owner") String owner,
          @ApiParam("Offset for the request list (max length = 10K).")
          @DefaultValue("0")
          @QueryParam("offset") long offset,
          @ApiParam("A path to match (as parent or full path); unspecified returns all requests.")
          @QueryParam("path") String path) {

        final Set<BulkRequestStatus> statusSet;
        if (Strings.emptyToNull(status) != null) {
            statusSet = new HashSet<>();
            Splitter.on(",").split(status).forEach(o -> statusSet.add(BulkRequestStatus.valueOf(o)));
        } else {
            statusSet = null;
        }

        final Set<String> ownerSet;
        if (Strings.emptyToNull(owner) != null) {
            ownerSet = new HashSet<>();
            Splitter.on(",").split(owner).forEach(ownerSet::add);
        } else {
            ownerSet = null;
        }

        BulkRequestListMessage message = new BulkRequestListMessage(statusSet, ownerSet, path, offset);
        message = service.send(message);

        return message.getRequests();
    }

    /**
     * Submit a bulk request.  See {@link BulkRequest}.
     * <p>
     * NOTE:  users logged in with the admin role will be submitting the request as ROOT (0:0).
     *
     * @return response which includes a location HTTP response header with a value that is the
     * absolute URL for the resource associated with this bulk request.
     */
    @POST
    @ApiOperation(value = "Submit a bulk request.")
    @ApiResponses({
          @ApiResponse(code = 201, message = "Created"),
          @ApiResponse(code = 400, message = "Bad request"),
          @ApiResponse(code = 401, message = "Unauthorized"),
          @ApiResponse(code = 403, message = "Forbidden"),
          @ApiResponse(code = 429, message = "Too many requests"),
          @ApiResponse(code = 500, message = "Internal Server Error")
    })
    @Consumes({MediaType.APPLICATION_JSON})
    @Produces(MediaType.APPLICATION_JSON)
    public Response submit(
          @ApiParam(value = "Description of the request, which defines the following:\n\n"
                + "**target** - Array of file paths.  Required.\n"
                + "**targetPrefix** - String path prefix, applied to all targets. Optional.\n"
                + "**activity** - String, name of the activity (PIN, UNPIN, DELETE, UPDATE_QOS). Required.\n"
                + "**cancelOnFailure** - Boolean. Optional, defaults to false.\n"
                + "**clearOnSuccess** - Boolean, Optional, defaults to false.\n"
                + "**clearOnFailure** - Boolean, Optional, defaults to false.\n"
                + "**expandDirectories** - String (NONE, TARGETS, ALL). Optional, defaults to NONE\n"
                + "**arguments** - Object (map) of name:value pairs. Optional, specific to activity.", required = true)
                String requestPayload) {
        Subject subject = getSubject();
        Restriction restriction = getRestriction();
        PnfsHandler handler = HandlerBuilders.unrestrictedPnfsHandler(pnfsmanager);
        FsPath userRoot = LoginAttributes.getUserRoot(getLoginAttributes(request));
        FsPath rootPath = pathMapper.effectiveRoot(userRoot, ForbiddenException::new);
        BulkRequest request = toBulkRequest(requestPayload, this.request, handler, rootPath);

        /*
         *  Frontend sets the URL.  The backend service provides the UUID.
         */

        request.setUrlPrefix(this.request.getRequestURL().toString());

        BulkRequestMessage message = new BulkRequestMessage(request, restriction);
        message.setSubject(subject);
        message = service.send(message);

        return Response.status(Response.Status.CREATED)
              .header("request-url", message.getRequestUrl())
              .type(MediaType.APPLICATION_JSON)
              .build();
    }

    /**
     * Get status information for an individual request.
     * <p>
     * NOTE: users logged in with the admin role can obtain info on any request.
     *
     * @param id of the request.
     * @return Object which describes the status of the request. See {@link BulkRequestInfo} for the
     * data fields.
     */
    @GET
    @ApiOperation("Get the status information for an individual bulk request.")
    @ApiResponses({
          @ApiResponse(code = 400, message = "Bad request"),
          @ApiResponse(code = 401, message = "Unauthorized"),
          @ApiResponse(code = 403, message = "Forbidden"),
          @ApiResponse(code = 404, message = "Not found"),
          @ApiResponse(code = 500, message = "Internal Server Error")
    })
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public BulkRequestInfo getBulkRequestStatus(@ApiParam("The unique id of the request.")
    @PathParam("id") String id,
          @ApiParam("Offset for the target list (max length = 10K).")
          @DefaultValue("0")
          @QueryParam("offset") long offset) {
        Subject subject = getSubject();
        Restriction restriction = getRestriction();

        BulkRequestStatusMessage message = new BulkRequestStatusMessage(id, restriction);
        message.setSubject(subject);
        message.setOffset(offset);
        message = service.send(message);
        return message.getInfo();
    }

    @GET
    @ApiOperation("List the status information for an individual bulk request matching"
          + " the query parameters (if any).")
    @ApiResponses({
          @ApiResponse(code = 400, message = "Bad request"),
          @ApiResponse(code = 401, message = "Unauthorized"),
          @ApiResponse(code = 403, message = "Forbidden"),
          @ApiResponse(code = 404, message = "Not found"),
          @ApiResponse(code = 500, message = "Internal Server Error")
    })
    @Path("/archived")
    @Produces(MediaType.APPLICATION_JSON)
    public List<BulkArchivedSummaryInfo> getArchivedSummaryList(
          @ApiParam("A datetime string formatted as 'yyyy/MM/dd-HH:mm:ss'.")
          @QueryParam("before") String before,
          @ApiParam("A datetime string formatted as 'yyyy/MM/dd-HH:mm:ss'.")
          @QueryParam("after") String after,
          @ApiParam("A comma-separated list of non-repeating elements, "
                + "each of which is an activity type.")
          @QueryParam("activity") String activity,
          @ApiParam("A comma-separated list of non-repeating elements, "
          + "each of which is one of: completed, cancelled.")
          @QueryParam("status") String status,
          @ApiParam("A comma-separated list of owners to match; unspecified returns all requests.")
          @QueryParam("owner") String owner,
          @ApiParam("Max number of entries to return for the request list (default = 5K).")
          @DefaultValue("5000")
          @QueryParam("limit") int limit) {
        BulkArchivedSummaryFilter filter = new BulkArchivedSummaryFilter();
        filter.setAfter(after);
        filter.setBefore(before);
        filter.setLimit(limit);
        if (activity != null) {
            filter.setActvity(Arrays.stream(activity.split("[,]")).collect(Collectors.toSet()));
        }
        if (status != null) {
            filter.setStatus(Arrays.stream(status.split("[,]")).collect(Collectors.toSet()));
        }
        if (owner != null) {
            filter.setOwner(Arrays.stream(owner.split("[,]")).collect(Collectors.toSet()));
        }
        Subject subject = getSubject();
        Restriction restriction = getRestriction();
        BulkArchivedSummaryInfoMessage message = new BulkArchivedSummaryInfoMessage(filter, restriction);
        message.setSubject(subject);
        message = service.send(message);
        return message.getInfo();
    }

    /**
     * Get archived information for a request.
     * <p>
     * NOTE: users logged in with the admin role can obtain info on any request.
     *
     * @param id of the request.
     * @return Object which describes the request. See {@link BulkArchivedRequestInfo} for the
     * data fields.
     */
    @GET
    @ApiOperation("Get the information for a bulk request which has been archived.")
    @ApiResponses({
          @ApiResponse(code = 400, message = "Bad request"),
          @ApiResponse(code = 401, message = "Unauthorized"),
          @ApiResponse(code = 403, message = "Forbidden"),
          @ApiResponse(code = 404, message = "Not found"),
          @ApiResponse(code = 500, message = "Internal Server Error")
    })
    @Path("/archived/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public BulkArchivedRequestInfo getArchivedRequestInfo(@ApiParam("The unique id of the request.")
    @PathParam("id") String id) {
        Subject subject = getSubject();
        Restriction restriction = getRestriction();
        BulkArchivedRequestInfoMessage message = new BulkArchivedRequestInfoMessage(id, restriction);
        message.setSubject(subject);
        message = service.send(message);
        return message.getInfo();
    }

    /**
     * Currently supports only 'action: cancel'.
     * <p>
     * If the bulk operation is in state started then all dCache activity for this bulk request is
     * stopped.
     * <p>
     * The corresponding bulk request status is updated to cancelled if it is currently queued or
     * started.  It does not change in the status is cancelled or completed.
     * <p>
     * NOTE: users logged in with the admin role can cancel any request.
     *
     * @param id             of the request.
     * @param requestPayload A JSON Object with an 'action' item specifying an action to take.
     * @return response
     */
    @PATCH
    @ApiOperation("Take some action on a bulk request.")
    @ApiResponses({
          @ApiResponse(code = 200, message = "Successful"),
          @ApiResponse(code = 400, message = "Bad request"),
          @ApiResponse(code = 401, message = "Unauthorized"),
          @ApiResponse(code = 403, message = "Forbidden"),
          @ApiResponse(code = 404, message = "Not found"),
          @ApiResponse(code = 500, message = "Internal Server Error")
    })
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response update(@ApiParam("The unique id of the request.")
    @PathParam("id") String id,
          @ApiParam(value = "A JSON Object with an 'action' item specifying an "
                + "action to take.", examples = @Example({
                @ExampleProperty("{\"action\" : "
                      + "\"cancel\" }")}))
                String requestPayload) {
        Subject subject = getSubject();
        Restriction restriction = getRestriction();

        JSONObject reqPayload = new JSONObject(requestPayload);

        if (!reqPayload.has("action")) {
            throw new BadRequestException("no action provided.");
        }

        String action = reqPayload.getString("action");

        if (!"cancel".equalsIgnoreCase(action)) {
            throw new BadRequestException(action + " not supported.");
        }

        BulkRequestCancelMessage message = new BulkRequestCancelMessage(id, restriction);
        message.setSubject(subject);

        if (reqPayload.has("paths")) {
            JSONArray paths = reqPayload.getJSONArray("paths");
            List<String> targetPaths = new ArrayList<>();
            int len = paths.length();
            for (int i = 0; i < len; ++i) {
                targetPaths.add(paths.getString(i));
            }
            message.setTargetPaths(targetPaths);
        }

        service.send(message);
        return Response.ok().build();
    }

    /**
     * If the bulk operation was in state started then all dCache activity triggered by this bulk
     * request is stopped, unless cancelIfRunning is true.
     * <p>
     * The bulk request is cleared.  No further activity will take place for this request.  The
     * server will respond to subsequent GET requests targeting this resource with a 404 (Not Found)
     * status code.
     * <p>
     * NOTE: users logged in with the admin role can clear any request.
     *
     * @param id of the request.
     * @return response
     */
    @DELETE
    @ApiOperation("Clear all resources pertaining to the given bulk request id.")
    @ApiResponses({
          @ApiResponse(code = 204, message = "No content"),
          @ApiResponse(code = 400, message = "Bad request"),
          @ApiResponse(code = 401, message = "Unauthorized"),
          @ApiResponse(code = 403, message = "Forbidden"),
          @ApiResponse(code = 404, message = "Not Found"),
          @ApiResponse(code = 500, message = "Internal Server Error")
    })
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response clearRequest(@ApiParam("The unique id of the request.")
    @PathParam("id") String id, @ApiParam("If request is still being processed, cancel it first.")
    @DefaultValue("false")
    @QueryParam("cancelIfRunning") boolean cancelIfRunning) {
        Subject subject = getSubject();
        Restriction restriction = getRestriction();

        BulkRequestClearMessage message = new BulkRequestClearMessage(id, restriction);
        message.setCancelIfRunning(cancelIfRunning);
        message.setSubject(subject);
        service.send(message);

        return Response.noContent().build();
    }

    public static Subject getSubject() {
        if (RequestUser.isAnonymous()) {
            throw new NotAuthorizedException("User cannot be anonymous.");
        }

        return RequestUser.getSubject();
    }

    public static Restriction getRestriction() {
        if (RequestUser.isAdmin()) {
            return Restrictions.none();
        }

        return RequestUser.getRestriction();
    }

    /**
     * Because of an inconsistency between initial documentation of the API and what is actually
     * exposed through the API, we need to support camelCase, snake_case and kebab-case for all the
     * top-level attributes. Hence we must convert to a generic map and fetch the values by hand.
     * <p/>
     * NB:  the argument attributes are all expressed in the document in kebab-case and that is how
     * they are defined in the Bulk service as well.
     */
    @VisibleForTesting
    static BulkRequest toBulkRequest(String requestPayload, HttpServletRequest httpServletRequest, PnfsHandler handler, FsPath rootPath) {
        if (Strings.emptyToNull(requestPayload) == null) {
            throw new BadRequestException("empty request payload.");
        }

        Map map;
        try {
            map = new Gson().fromJson(requestPayload, Map.class);
        } catch (JsonParseException e) {
            throw newBadRequestException(requestPayload, e);
        }

        BulkRequest request = new BulkRequest();

        Map<String, Object> arguments = (Map<String, Object>) map.remove("arguments");
        if (arguments != null) {
            Map<String, String> stringified = new HashMap<>();
            arguments.entrySet().stream()
                  .forEach(e -> stringified.put(e.getKey(), String.valueOf(e.getValue())));
            request.setArguments(stringified);
        }

        List<String> targets = extractTarget(map);
        if (targets.isEmpty()) {
            throw new BadRequestException("request contains no targets.");
        }
        request.setTarget(targets);

        String string = removeEntry(map, String.class, "activity");
        request.setActivity(string);

        string = removeEntry(map, String.class, "target_prefix", "target-prefix",
              "targetPrefix");

        if (httpServletRequest != null) {
            request.setTargetPrefix(getUserRootAwareTargetPrefix(httpServletRequest,
                                                                 string != null ? string : rootPath.toString(),
                                                                 handler));
        } else {
            request.setTargetPrefix(string != null ? string : rootPath.toString());
        }

        string = removeEntry(map, String.class, "expand_directories", "expand-directories",
              "expandDirectories");
        request.setExpandDirectories(
              string == null ? Depth.NONE : Depth.valueOf(string.toUpperCase()));

        Object value = removeEntry(map, Object.class, "clear_on_success", "clear-on-success",
              "clearOnSuccess");
        if (value instanceof Boolean) {
            request.setClearOnSuccess((boolean) value);
        } else {
            request.setClearOnSuccess(Boolean.valueOf(String.valueOf(value)));
        }

        value = removeEntry(map, Object.class, "clear_on_failure", "clear-on-failure",
              "clearOnFailure");
        if (value instanceof Boolean) {
            request.setClearOnFailure((boolean) value);
        } else {
            request.setClearOnFailure(Boolean.valueOf(String.valueOf(value)));
        }

        value = removeEntry(map, Object.class, "cancel_on_failure", "cancel-on-failure",
              "cancelOnFailure");
        if (value instanceof Boolean) {
            request.setCancelOnFailure((boolean) value);
        } else {
            request.setCancelOnFailure(Boolean.valueOf(String.valueOf(value)));
        }

        /*
         *  "prestore" has been deprecated, but we allow it still to be present
         *   in the attributes for backward compatibility.
         */
        removeEntry(map, Object.class, "pre_store", "pre-store", "prestore");

        if (!map.isEmpty()) {
            throw new BadRequestException("unsupported arguments: " + map.keySet());
        }

        return request;
    }

    /*
     *  These checks are for backward compatibility with Bulk 1 (version 7.2), which was,
     *  unfortunately, ambiguously typed.  For Bulk 2 the practice should be to
     *  specify the target as a JSON array.
     */
    private static List<String> extractTarget(Map map) {
        Object target = map.remove("target");
        if (target instanceof String) {
            String stringTarget = (String)target;
            stringTarget = stringTarget.replaceAll("\"", "")
                  .replaceAll("[\\s]", "");
            if (stringTarget.contains("[")) {
                int open = stringTarget.indexOf("[");
                int close = stringTarget.indexOf("]");
                stringTarget = stringTarget.substring(open+1, close);
            }
            return Arrays.stream(stringTarget.split("[,]"))
		.filter(i-> !i.isEmpty())
		.collect(Collectors.toList());
        } else if (target instanceof String[]) {
            return Arrays.stream(((String) target).split("[,]"))
		.map(String::strip)
		.filter(i-> !i.isEmpty())
		.collect(Collectors.toList());
        } else {
            return ((List<String>) target)
		.stream()
		.map(String::strip)
		.filter(i -> !i.isEmpty())
		.collect(Collectors.toList());
        }
    }


    private static <T> T removeEntry(Map map, Class<T> clzz, String... names) {
        T value = null;
        for (String name : names) {
            T v = (T) map.remove(name);
            if (value == null) {
                value = v;
            } else if (v != null) {
                throw new BadRequestException("value for " + name + " defined more than once.");
            }
        }
        return value;
    }
}
