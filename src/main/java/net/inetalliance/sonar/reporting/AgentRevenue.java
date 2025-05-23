package net.inetalliance.sonar.reporting;

import com.callgrove.obj.Agent;
import com.callgrove.obj.Opportunity;
import jakarta.servlet.annotation.WebServlet;
import net.inetalliance.beejax.messages.Category;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.json.JsonMap;

import java.time.LocalDateTime;
import java.util.Set;

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
    protected Query<Agent> allRows(final Set<Category> groups, final Agent loggedIn,
                                   final LocalDateTime intervalStart) {
        return loggedIn.getViewableAgentsQuery().and(Agent.activeAfter(intervalStart))
                .and(Agent.isSales);
    }
}
