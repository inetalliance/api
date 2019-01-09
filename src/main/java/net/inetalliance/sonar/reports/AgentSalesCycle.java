package net.inetalliance.sonar.reports;

import com.callgrove.obj.*;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;

import javax.servlet.annotation.WebServlet;
import java.util.HashSet;
import java.util.Set;

import static net.inetalliance.potion.Locator.forEach;
import static net.inetalliance.potion.query.Query.all;

@WebServlet("/api/agentSalesCycle")
public class AgentSalesCycle
	extends SalesCycle<ProductLine, Agent> {


	private final Info<Agent> info;

	public AgentSalesCycle() {
		super("agent");
		info = Info.$(Agent.class);
	}

	@Override
	protected Query<ProductLine> allRows(final Agent loggedIn) {
		return Query.all(ProductLine.class);
	}

	@Override
	protected Query<Call> callWithRow(final ProductLine row) {
		final Set<String> queues = new HashSet<>(8);
		forEach(all(Queue.class), arg -> {
			if (row.equals(arg.getProductLine())) {
				queues.add(arg.key);
			}
		});
		return Call.withQueueIn(queues);
	}

	@Override
	protected Agent getGroup(final String[] params, final String key) {
		return info.lookup(key);
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
	protected String getLabel(final ProductLine row) {
		return row.getName();
	}

	@Override
	protected Query<Opportunity> inGroups(final Set<Agent> groups) {
		return Opportunity.withAgentIn(groups);
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
	protected Query<Opportunity> withRow(final ProductLine row) {
		return Opportunity.withProductLine(row);
	}
}
