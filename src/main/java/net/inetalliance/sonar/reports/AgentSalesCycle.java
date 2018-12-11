package net.inetalliance.sonar.reports;

import com.callgrove.obj.*;
import net.inetalliance.funky.functors.F1;
import net.inetalliance.funky.functors.P1;
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

	private static final F1<String, Agent> lookup = Info.$(Agent.class).lookup;

	public AgentSalesCycle() {
		super("agent");
	}

	@Override
	protected Query<ProductLine> allRows(final Agent loggedIn) {
		return Query.all(ProductLine.class);
	}

	@Override
	protected Query<Call> callWithRow(final ProductLine row) {
		final Set<String> queues = new HashSet<>(8);
		forEach(all(Queue.class), new P1<Queue>() {
			@Override
			public void $(final Queue arg) {
				if (row.equals(arg.getProductLine())) {
					queues.add(arg.key);
				}
			}
		});
		return Call.Q.withQueueIn(queues);
	}

	@Override
	protected F1<String, Agent> getGroupLookup(final String[] params) {
		return lookup;
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
		return Opportunity.Q.withAgentIn(groups);
	}

	@Override
	protected Query<DailyPerformance> performanceForGroups(final Set<Agent> groups) {
		return DailyPerformance.Q.withAgentIn(groups);
	}

	@Override
	protected Query<DailyPerformance> performanceWithRow(final ProductLine row) {
		return DailyPerformance.Q.withProductLine(row);
	}

	@Override
	protected Query<Opportunity> withRow(final ProductLine row) {
		return Opportunity.Q.withProductLine(row);
	}
}
