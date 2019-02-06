package net.inetalliance.sonar.reporting;

import com.callgrove.obj.Agent;
import net.inetalliance.angular.events.Events;
import net.inetalliance.potion.Locator;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;

import javax.websocket.Session;
import java.util.concurrent.ConcurrentHashMap;

import static net.inetalliance.angular.events.Events.*;
import static net.inetalliance.util.shell.Shell.*;

public class SessionHandler
	implements javax.websocket.MessageHandler.Whole<String> {
	private static final ConcurrentHashMap<String, MessageHandler> handlers = new ConcurrentHashMap<>();

	private static MessageHandler getHandler(final String type) {
		return handlers.computeIfAbsent(type, key -> (session, msg) -> {
			log.warning("received message of unknown type %s: %s", key, Json.pretty(msg));
			return null;
		});
	}

	private final Session session;

	public SessionHandler(final Session session) {
		this.session = session;
		handlers.forEach((type, handler) -> send(session, type, handler.onConnect(session)));
	}

	static Agent getAgent(final Session session) {
		return Locator.$(new Agent(Events.getUser(session).getPhone()));
	}

	@Override
	public void onMessage(final String message) {
		final JsonMap msg = JsonMap.parse(message);
		final String type = msg.get("type");
		send(session, type, getHandler(type).onMessage(session, msg.getMap("msg")));

	}

	static void init() {
		Events.init();
		handlers.put("ping", new PingHandler());

	}

	public static void destroy() {
		handlers.values().forEach(MessageHandler::destroy);
		Events.destroy();
	}
}
