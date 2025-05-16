package net.inetalliance.sonar.reporting;

import com.ameriglide.phenix.core.Log;
import com.callgrove.obj.Agent;
import jakarta.websocket.Session;
import lombok.val;
import net.inetalliance.angular.events.Events;
import net.inetalliance.potion.Locator;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;

import java.util.concurrent.ConcurrentHashMap;

import static net.inetalliance.angular.events.Events.send;

public class SessionHandler
        implements jakarta.websocket.MessageHandler.Whole<String> {
    private static final Log log = new Log();

    private static final ConcurrentHashMap<String, MessageHandler> handlers = new ConcurrentHashMap<>();
    private final Session session;

    public SessionHandler(final Session session) {
        this.session = session;
        handlers.forEach((type, handler) -> send(session, type, handler.onConnect(session)));
    }

    static Agent getAgent(final Session session) {
        return Locator.$(new Agent(Events.getUser(session).getPhone()));
    }

    static void init() {
        handlers.put("ping", new PingHandler());

    }

    public static void destroy() {
        handlers.values().forEach(MessageHandler::destroy);
        Events.destroy();
    }

    private static MessageHandler getHandler(final String type) {
        return handlers.computeIfAbsent(type, key -> (s, msg) -> {
            log.warn(() -> "received message of unknown type %s: %s".formatted(key, Json.pretty(msg)));
            return null;
        });
    }

    @Override
    public void onMessage(final String message) {
        val msg = JsonMap.parse(message);
        val type = msg.get("type");
        send(session, type, getHandler(type).onMessage(session, msg.getMap("msg")));

    }
}
