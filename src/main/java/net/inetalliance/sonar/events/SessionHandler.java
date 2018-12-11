package net.inetalliance.sonar.events;

import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;

import javax.websocket.Session;
import java.util.concurrent.ConcurrentHashMap;

import static net.inetalliance.sonar.events.Events.send;
import static net.inetalliance.util.shell.Shell.log;

class SessionHandler implements javax.websocket.MessageHandler.Whole<String> {
	private static final ConcurrentHashMap<String, MessageHandler> handlers = new ConcurrentHashMap<>();

	private static MessageHandler getHandler(final String type) {
		return handlers.computeIfAbsent(type, key -> (session, msg) -> {
			log.warning("received message of unknown type %s: %s", key, Json.F.pretty.$(msg));
			return null;
		});
	}


	private final Session session;

	SessionHandler(final Session session) {
		this.session = session;
		handlers.forEach((type, handler) -> send(session, type, handler.onConnect(session)));
	}

	@Override
	public void onMessage(final String message) {
		final JsonMap msg = JsonMap.parse(message);
		final String type = msg.get("type");
		send(session, type, getHandler(type).onMessage(session, msg.getMap("msg")));

	}

	public static void init() {
		handlers.put("hud", new HudHandler());
		handlers.put("status", new StatusHandler());
		handlers.put("progress", new ProgressHandler());
		handlers.put("reminder", new ReminderHandler());
		handlers.put("ping", new PingHandler());

	}

	public static void destroy() {
		handlers.values().forEach(MessageHandler::destroy);
	}
}
