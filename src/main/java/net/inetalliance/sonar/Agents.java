package net.inetalliance.sonar;

import com.callgrove.obj.Agent;
import net.inetalliance.angular.dispatch.Dispatchable;
import net.inetalliance.funky.functors.F1;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;

import static net.inetalliance.sql.OrderBy.Direction.ASCENDING;

@WebServlet({"/api/agent/*", "/reporting/agent/*"})
public class Agents
  extends ListableModel<Agent>
  implements Dispatchable {

  public Agents() {
    super(Agent.class);
  }

  @Override
  public Query<Agent> all(final Class<Agent> type, final HttpServletRequest request) {
    final Agent agent = Startup.getAgent(request);
    Query<Agent> query = Query.all(type);
    if (request.getParameter("visible") != null) {
      query = agent.getViewableAgentsQuery();
    }
    if (request.getParameter("active") != null) {
      query = query.and(Agent.Q.active);
    }
    if (request.getParameter("sales") != null) {
      query = query.and(Agent.Q.sales);
    }
    return query
      .orderBy("lastName", ASCENDING)
      .orderBy("firstName", ASCENDING);
  }

  private final F1<Agent, JsonMap> calc = new F1<Agent, JsonMap>() {
    @Override
    public JsonMap $(final Agent agent) {
      return json.$(agent).$("lastNameFirstInitial",agent.getLastNameFirstInitial());
    }
  };

  @Override
  public F1<Agent, ? extends Json> toJson(final HttpServletRequest request) {
    return calc;
  }
}
