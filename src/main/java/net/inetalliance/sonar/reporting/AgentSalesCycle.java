package net.inetalliance.sonar.reporting;

import com.callgrove.obj.*;
import jakarta.servlet.annotation.WebServlet;
import lombok.val;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static net.inetalliance.potion.Locator.forEach;
import static net.inetalliance.potion.query.Query.all;

@WebServlet("/reporting/reports/agentSalesCycle")
public class AgentSalesCycle
        extends SalesCycle<ProductLine, Agent> {

    private final Info<Agent> info;

    public AgentSalesCycle() {
        super("agent");
        info = Info.$(Agent.class);
    }

    @Override
    protected Query<Call> callWithRow(final ProductLine row) {
        val queues = new HashSet<String>(8);
        forEach(all(Queue.class), arg -> {
            if (row.equals(arg.getProductLine())) {
                queues.add(arg.key);
            }
        });
        return Call.withQueueIn(queues);
    }

    @Override
    protected Query<Opportunity> inGroups(final Set<Agent> groups) {
        return Opportunity.withAgentIn(groups);
    }

    @Override
    protected String getLabel(final ProductLine row) {
        return row.getName();
    }

    @Override
    protected Query<Opportunity> withRow(final ProductLine row) {
        return Opportunity.withProductLine(row);
    }

    @Override
    protected Query<DailyPerformance> performanceForGroups(final Set<Agent> groups) {
        return DailyPerformance.withAgentIn(groups);
    }

    @Override
    protected Query<DailyPerformance> performanceWithRow(final ProductLine row) {
        return DailyPerformance.withProductLine(row);
    }

    @Override
    protected String getGroupLabel(final Agent group) {
        return group.getLastNameFirstInitial();
    }

    @Override
    protected String getId(final ProductLine row) {
        return row.getId().toString();
    }

    @Override
    protected Query<ProductLine> allRows(final Set<Agent> groups, final Agent loggedIn,
                                         final LocalDateTime intervalStart) {
        return Query.all(ProductLine.class);
    }

    @Override
    protected Agent getGroup(final String[] params, final String key) {
        return info.lookup(key);
    }
}
