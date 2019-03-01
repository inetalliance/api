package net.inetalliance.sonar.api;

import com.callgrove.obj.*;
import net.inetalliance.potion.info.*;
import net.inetalliance.potion.query.*;
import net.inetalliance.sonar.*;
import net.inetalliance.types.json.*;

import javax.servlet.annotation.*;
import javax.servlet.http.*;

import static net.inetalliance.sql.OrderBy.Direction.*;

@WebServlet("/api/agent/*")
public class Agents
		extends ListableModel<Agent> {

	private final Info<Agent> info;

	public Agents() {
		super(Agent.class);
		this.info = Info.$(Agent.class);
	}

	@Override
	public Query<Agent> all(final Class<Agent> type, final HttpServletRequest request) {
		final Agent agent = Startup.getAgent(request);
		Query<Agent> query = Query.all(type);
		if (request.getParameter("visible") != null) {
			query = agent == null ? Query.none(type) : agent.getViewableAgentsQuery();
		}
		if (request.getParameter("active") != null) {
			query = query.and(Agent.isActive);
		}
		if (request.getParameter("sales") != null) {
			query = query.and(Agent.isSales);
		}
		return query.orderBy("lastName", ASCENDING).orderBy("firstName", ASCENDING);
	}

	@Override
	public Json toJson(final HttpServletRequest request, final Agent agent) {
		return info.toJson(agent).$("lastNameFirstInitial", agent.getLastNameFirstInitial());
	}
}
