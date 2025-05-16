package net.inetalliance.sonar.api;

import com.callgrove.obj.Agent;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import lombok.val;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sonar.ListableModel;
import net.inetalliance.types.json.Json;

import static net.inetalliance.sql.OrderBy.Direction.ASCENDING;

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
    val agent = Startup.getAgent(request);
      var query = Query.all(type);
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
    return info.toJson(agent)
        .$("lastNameFirstInitial", agent.getLastNameFirstInitial())
        .$("firstNameLastInitial", agent.getFirstNameLastInitial());
  }
}
