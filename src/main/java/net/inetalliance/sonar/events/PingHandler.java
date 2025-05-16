package net.inetalliance.sonar.events;

import com.ameriglide.phenix.core.Log;
import jakarta.websocket.Session;
import net.inetalliance.types.json.JsonMap;


public class PingHandler
        implements MessageHandler {
    private static final Log log = new Log();

    @Override
    public JsonMap onMessage(final Session session, final JsonMap msg) {
        log.trace(() -> "%s pinged us".formatted(SessionHandler.getAgent(session).getLastNameFirstInitial()));
        return null;
    }
}
