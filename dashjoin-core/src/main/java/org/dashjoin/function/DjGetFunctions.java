package org.dashjoin.function;

import java.util.List;
import org.dashjoin.service.Manage.FunctionVersion;

public class DjGetFunctions extends AbstractFunction<Void, List<FunctionVersion>> {

  @Override
  public List<FunctionVersion> run(Void arg) throws Exception {
    return expressionService.getManage().getFunctions();
  }

  @Override
  public Class<Void> getArgumentClass() {
    return Void.class;
  }

  @Override
  public String getID() {
    return "djGetFunctions";
  }

  @Override
  public String getType() {
    return "read";
  }
}
