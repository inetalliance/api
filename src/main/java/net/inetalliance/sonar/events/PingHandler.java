package net.inetalliance.sonar.events;

import com.callgrove.obj.*;
import net.inetalliance.types.json.*;

import javax.websocket.*;

import static net.inetalliance.util.shell.Shell.*;

public class PingHandler
		implements MessageHandler {
	@Override
	public JsonMap onMessage(final Session session, final JsonMap msg) {
		if (log.isTraceEnabled()) {
			Agent agent = SessionHandler.getAgent(session);
			log.trace("%s pinged us", agent.getLastNameFirstInitial());
		}
		return null;
	}
}
