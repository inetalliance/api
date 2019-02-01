package net.inetalliance.sonar.events;

import net.inetalliance.types.json.JsonMap;

import javax.websocket.Session;

public interface MessageHandler {

	JsonMap onMessage(final Session session, final JsonMap msg);

	default JsonMap onConnect(final Session session) {
		return null;
	}

	default void destroy() { }

}
