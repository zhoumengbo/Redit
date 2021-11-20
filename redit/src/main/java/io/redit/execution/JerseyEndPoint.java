/*
 * MIT License
 *
 * Copyright (c) 2021 SATE-Lab
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.redit.execution;

import javax.servlet.ServletContext;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Path("/")
public class JerseyEndPoint {

    @Context
    ServletContext context;

    EventService eventService;

    private EventService getEventService() {
        if (eventService == null) {
            eventService = (EventService) context.getAttribute("io.redit.EventService");
        }
        return eventService;
    }

    @GET
    @Path("/dependencies/{name}")
    public Response checkEventDependencies(@PathParam("name") String eventName, @QueryParam("includeEvent") Integer eventInclusion) {
        if (getEventService().areDependenciesMet(eventName, eventInclusion)) {
            return Response.status(Response.Status.OK).build();
        } else {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }

    @GET
    @Path("/blockDependencies/{name}")
    public Response checkEventBlockDependencies(@PathParam("name") String eventName) {
        if (getEventService().areBlockDependenciesMet(eventName)) {
            return Response.status(Response.Status.OK).build();
        } else {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }

    @GET
    @Path("/events/{name}")
    public Response checkEventReceipt(@PathParam("name") String eventName) {
        if (getEventService().hasEventReceived(eventName)) {
            return Response.status(Response.Status.OK).build();
        } else {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }

    @POST
    @Path("/events")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response receiveEvent(Event event) {
        getEventService().receiveEvent(event.getName());
        return Response.status(Response.Status.OK).build();
    }

    public static class Event {
        String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
