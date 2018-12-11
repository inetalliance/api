package net.inetalliance.sonar;

import com.callgrove.obj.Agent;
import com.callgrove.obj.CallCenter;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.json.JsonString;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import java.util.Collection;

import static net.inetalliance.sql.OrderBy.Direction.*;

@WebServlet("/api/callCenterAgents")
public class CallCenterAgents extends SelectMembers<CallCenter, Agent> {
	public CallCenterAgents() {
		super(CallCenter.class, Agent.class);
	}

	@Override
	protected JsonString toId(final Agent member) {
		return new JsonString(member.key);
	}

	@Override
	protected String getLabel(final Agent member) {
		return member.getLastNameFirstInitial();
	}

	@Override
	protected Collection<Agent> getMembers(final CallCenter group) {
		return Locator.$$(Agent.Q.withCallCenter(group).and(Agent.Q.active));
	}

	@Override
	public Query<CallCenter> all(final Class<CallCenter> callCenterClass, final HttpServletRequest httpServletRequest) {
		return CallCenter.Q.withSalesAgent.orderBy("name", ASCENDING);
	}
}
