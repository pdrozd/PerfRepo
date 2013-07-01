/* 
 * Copyright 2013 Red Hat, Inc.
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
package org.jboss.qa.perfrepo.rest;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;

import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.GenericEntity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.apache.log4j.Logger;
import org.jboss.qa.perfrepo.model.Metric;
import org.jboss.qa.perfrepo.model.Test;
import org.jboss.qa.perfrepo.model.TestExecution;
import org.jboss.qa.perfrepo.service.TestService;
import org.jboss.resteasy.annotations.providers.jaxb.Wrapped;

/**
 * REST interface for Test objects.
 * 
 * @author Pavel Drozd (pdrozd@redhat.com)
 * @author Michal Linhard (mlinhard@redhat.com)
 */
@Path("/test")
@RequestScoped
public class TestREST {

   private static Method GET_TEST_METHOD;
   private static Method GET_METRIC_METHOD;
   static {
      try {
         GET_TEST_METHOD = TestREST.class.getMethod("get", Long.class);
         GET_METRIC_METHOD = MetricREST.class.getMethod("get", Long.class);
      } catch (Exception e) {
         e.printStackTrace(System.err);
      }
   }

   @Inject
   private TestService testService;

   @GET
   @Produces(MediaType.TEXT_XML)
   @Path("/{testId}")
   @Logged
   public Response get(@PathParam("testId") Long testId) {
      return Response.ok(testService.getTest(testId)).build();
   }

   @GET
   @Produces(MediaType.TEXT_XML)
   @Path("/all")
   @Logged
   @Wrapped(element = "tests")
   public Response all() {
      return Response.ok(genericEntity(testService.findAllTests(), Test.class)).build();
   }

   @POST
   @Path("/create")
   @Consumes(MediaType.TEXT_XML)
   @Logged
   public Response create(Test test, @Context UriInfo uriInfo) throws Exception {
      Long id = testService.createTest(test).getId();
      return Response.created(uriInfo.getBaseUriBuilder().path(TestREST.class).path(GET_TEST_METHOD).build(id)).entity(id).build();
   }

   @DELETE
   @Path("/{testId}")
   @Logged
   public Response delete(@PathParam("testId") Long testId) throws Exception {
      Test test = new Test();
      test.setId(testId);
      testService.deleteTest(test);
      return Response.noContent().build();
   }

   @POST
   @Path("/{testId}/addMetric")
   @Consumes(MediaType.TEXT_XML)
   @Logged
   public Response addMetric(@PathParam("testId") Long testId, Metric metric, @Context UriInfo uriInfo) {
      Test test = new Test();
      test.setId(testId);
      Long id = testService.addMetric(test, metric).getId();
      return Response.created(uriInfo.getBaseUriBuilder().path(MetricREST.class).path(GET_METRIC_METHOD).build(id)).entity(id).build();
   }

   @GET
   @Produces("text/xml")
   @Path("/{testId}/executions")
   @Logged
   @Wrapped(element = "testExecutions")
   public Response executions(@PathParam("testId") Long testId) {
      return Response.ok(genericEntity(testService.findExecutionsByTest(testId), TestExecution.class)).build();
   }

   // later should be moved to an util class
   static <T> GenericEntity<Collection<T>> genericEntity(Collection<T> entity, Class<T> componentClass) {
      ArrayList<T> collection = new ArrayList<T>(entity);
      GenericEntity<Collection<T>> r = new GenericEntity<Collection<T>>(collection, collection.getClass());
      Logger.getLogger(TestREST.class).info(
            "Creating generid entity: class=" + r.getClass().getName() + ", rawType=" + r.getRawType().getName() + ", type=" + r.getType());
      return r;
   }
}