package org.dashjoin.function;

import org.dashjoin.service.PojoDatabase;

/**
 * expose metadata collection as a function
 */
public class CollectMetadata extends AbstractFunction<String, Void> {

  @Override
  public Void run(String id) throws Exception {
    PojoDatabase config = (PojoDatabase) services.getConfig();
    config.metadataCollection(id);
    return null;
  }

  @Override
  public Class<String> getArgumentClass() {
    return String.class;
  }

  @Override
  public String getID() {
    return "collectMetadata";
  }

  @Override
  public String getType() {
    return "write";
  }
}
