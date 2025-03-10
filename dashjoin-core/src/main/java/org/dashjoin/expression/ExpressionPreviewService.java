package org.dashjoin.expression;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.SecurityContext;
import org.dashjoin.expression.ExpressionService.ExpressionAndData;
import org.dashjoin.mapping.ETL;
import org.dashjoin.service.Services;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * REST API for expression evaluation in read only mode
 */
@Path(Services.REST_PREFIX + "expression-preview")
@Produces({MediaType.APPLICATION_JSON})
@Consumes({MediaType.APPLICATION_JSON})
public class ExpressionPreviewService {

  public static class ExpressionCursorData extends ExpressionAndData {
    public boolean foreach;
  }

  @Inject
  ExpressionService expression;

  @POST
  @Path("/")
  @Operation(summary = "evaluates the expression with the data context")
  @APIResponse(description = "evaluation result")
  public Object resolve(@Context SecurityContext sc,
      @Parameter(description = "expression and context to evaluate") ExpressionCursorData e)
      throws Exception {
    if (e.foreach) {
      try {
        ETL.context.set(new org.dashjoin.mapping.ETL.Context());
        JsonNode node = expression.jsonata(sc, e.expression, ExpressionService.o2j(e.data), true);
        ETL.context.get().producerDone();
        if (ETL.context.get().queue.size() > 10) {
          List<Object> res = new ArrayList<>();
          Iterator<Object> iter = ETL.context.get().queue.iterator();
          for (int i = 0; i < 10; i++)
            res.add(iter.next());
          return res;
        } else if (ETL.context.get().queue.isEmpty()) {
          // no queue, use result directly
          Object o = ExpressionService.j2o(node);
          @SuppressWarnings("unchecked")
          List<Object> list = o instanceof List ? (List<Object>) o : Arrays.asList(o);
          if (list.size() > 10)
            return list.subList(0, 10);
          else
            return list;
        } else
          return ETL.context.get().queue;
      } finally {
        ETL.context.set(null);
      }
    } else
      return expression.jsonata(sc, e.expression, ExpressionService.o2j(e.data), true);
  }
}
