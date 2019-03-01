package net.inetalliance.sonar.reporting;

import com.callgrove.obj.*;
import net.inetalliance.potion.query.*;
import net.inetalliance.types.json.*;

import javax.servlet.annotation.*;

@WebServlet("/reporting/reports/agentRevenue")
public class AgentRevenue
		extends Revenue<Agent> {

	@Override
	protected JsonMap addRowInfo(final JsonMap json, final Agent agent) {
		return json.$("agent", agent.key);
	}

	@Override
	protected Query<Opportunity> oppsForRow(final Agent row) {
		return Opportunity.withAgent(row);
	}

	@Override
	protected String getId(final Agent agent) {
		return agent.key;
	}

	@Override
	protected Query<Agent> allRows(final Agent loggedIn) {
		return loggedIn.getViewableAgentsQuery().and(Agent.isSales.and(Agent.isLocked.negate()));
	}
}
