package net.inetalliance.sonar.reporting;

import com.callgrove.obj.Agent;
import net.inetalliance.types.json.JsonMap;

import javax.websocket.Session;

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
