package net.inetalliance.sonar.reporting;

import javax.websocket.Session;
import net.inetalliance.types.json.JsonMap;

public interface MessageHandler {

  JsonMap onMessage(final Session session, final JsonMap msg);

  default JsonMap onConnect(final Session session) {
    return null;
  }

  default void destroy() {
  }

}
