package net.inetalliance.sonar.reports;

import com.callgrove.obj.Agent;
import com.callgrove.obj.Opportunity;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.json.JsonMap;

import javax.servlet.annotation.WebServlet;

@WebServlet("/api/agentRevenue")
public class AgentRevenue
		extends Revenue<Agent> {

	@Override
	protected JsonMap addRowInfo(final JsonMap json, final Agent agent) {
		return json.$("agent", agent.key);
	}

	@Override
	protected Query<Agent> allRows(final Agent loggedIn) {
		return loggedIn.getViewableAgentsQuery().and(Agent.Q.sales.and(Agent.Q.locked.negate()));
	}

	@Override
	protected String getId(final Agent agent) {
		return agent.key;
	}

	@Override
	protected Query<Opportunity> oppsForRow(final Agent row) {
		return Opportunity.Q.withAgent(row);
	}
}
