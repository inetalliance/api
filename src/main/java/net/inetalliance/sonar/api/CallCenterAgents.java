package net.inetalliance.sonar.api;

import static net.inetalliance.sql.OrderBy.Direction.ASCENDING;

import com.callgrove.obj.Agent;
import com.callgrove.obj.CallCenter;
import java.util.Collection;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.json.JsonString;

@WebServlet("/api/callCenterAgents")
public class CallCenterAgents
    extends SelectMembers<CallCenter, Agent> {

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
    return Locator.$$(Agent.withCallCenter(group).and(Agent.isActive));
  }

  @Override
  public Query<CallCenter> all(final Class<CallCenter> callCenterClass,
      final HttpServletRequest httpServletRequest) {
    return CallCenter.withSalesAgent.orderBy("name", ASCENDING);
  }
}
