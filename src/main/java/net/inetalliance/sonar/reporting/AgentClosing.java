package net.inetalliance.sonar.reporting;

import com.callgrove.obj.*;
import com.callgrove.types.ContactType;
import com.callgrove.types.SaleSource;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.angular.exception.UnauthorizedException;
import net.inetalliance.log.Log;
import net.inetalliance.log.progress.ProgressMeter;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sonar.api.Startup;
import net.inetalliance.types.Currency;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.DateMidnight;
import org.joda.time.Interval;

import javax.servlet.annotation.WebServlet;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.callgrove.Callgrove.*;
import static com.callgrove.obj.Call.*;
import static com.callgrove.obj.Opportunity.*;
import static java.util.stream.Collectors.*;
import static net.inetalliance.funky.StringFun.*;
import static net.inetalliance.log.Log.*;
import static net.inetalliance.potion.Locator.*;
import static net.inetalliance.sql.Aggregate.*;

@WebServlet({"/reporting/reports/agentClosing"})
public class AgentClosing
	extends CachedGroupingRangeReport<Agent, Site> {

	private static final transient Log log = getInstance(AgentClosing.class);
	private final Info<Site> info;

	public AgentClosing() {
		super("site", "agent");
		info = Info.$(Site.class);
	}

	@Override
	protected Site getGroup(final String[] params, final String key) {
		return info.lookup(key);
	}

	@Override
	protected JsonMap generate(final EnumSet<SaleSource> sources,
		final EnumSet<ContactType> contactTypes,
		final Agent loggedIn, final ProgressMeter meter,
		final DateMidnight start, final DateMidnight end,
		final Set<Site> sites,
		final Map<String, String> extras) {
		if (loggedIn == null || !(loggedIn.isManager() || loggedIn.isTeamLeader())) {
			log.warning("%s tried to access closing report data",
				loggedIn == null ? "Nobody?" : loggedIn.key);
			throw new UnauthorizedException();
		}
		final String agentKey = extras.get("agent");
		if (isEmpty(agentKey)) {
			throw new BadRequestException("Must specify agent via ?agent=");
		}
		final Agent agent = $(new Agent(agentKey));
		if (agent == null) {
			throw new NotFoundException("Could not find agent with key %s", agentKey);
		}

		final Interval interval = getReportingInterval(start, end);

		final Set<String> queues = Locator.$A(Queue.class).stream().map(q -> q.key).collect(toSet());
		ProductLineClosing.retainVisible(loggedIn, sites, queues);

		final Info<DailyPerformance> info = Info.$(DailyPerformance.class);
		final Query<Call> callQuery = Call.inInterval(interval);
		final Query<Opportunity> oppQuery =
			soldInInterval(interval)
				.and(Opportunity.withAgent(agent))
				.and(Opportunity.withSources(sources))
				.and(Opportunity.withContactTypes(contactTypes))
				.and(sites == null || sites.isEmpty() ? Query.all(Opportunity.class) :
					Opportunity.withSiteIn(sites));
		final JsonList rows = new JsonList();
		Locator.forEach(Query.all(ProductLine.class), productLine -> {

			final AtomicInteger n = new AtomicInteger(0);
			final Set<String> productLineQueues = new HashSet<>(queues);
			productLineQueues.retainAll(Startup.productLineQueues.getOrDefault(productLine.id, Set.of()));
			final Query<Call> productLineCallQuery = productLineQueues.isEmpty()
				? Query.none(Call.class)
				: callQuery.and(Call.withQueueIn(productLineQueues));
			final DailyPerformance productLineTotal = new DailyPerformance();
			productLineTotal.setQueueCalls(count(productLineCallQuery
				.and(isQueue)
				.and(Call.withSourceIn(sources))
				.and(Call.withBlame(agent))));
			productLineTotal.setOutboundCalls(count(productLineCallQuery
				.and(isOutbound)
				.and(Call.withAgent(agent))));
			productLineTotal.setDumps(count(productLineCallQuery.and(isQueue).and(Call.isDumped).and(
				Call.withBlame(agent))));
			final Query<Opportunity> agentOppQuery = oppQuery.and(
				withProductLine(productLine).and(
					withAmountGreaterThan(productLine.getLowestReasonableAmount())));
			productLineTotal.setCloses(count(agentOppQuery));
			productLineTotal.setSales($$(agentOppQuery, SUM, Currency.class, "amount"));
			if (productLineTotal.getCloses() > 0 || productLineTotal.getQueueCalls() > 0) {
				n.incrementAndGet();
				rows.add(info.toJson(productLineTotal)
					.$("label", productLine.getName())
					.$("id", productLine.id));
			}
			meter.increment(productLine.getName());
		});
		return new JsonMap()
			.$("rows", rows);

	}

	@Override
	protected String getGroupLabel(final Site group) {
		return group.getName();
	}

	@Override
	protected String getId(final Agent row) {
		return row.key;
	}

	@Override
	protected int getJobSize(final Agent loggedIn, final int numGroups) {
		return count(allRows(loggedIn));
	}

	@Override
	protected Query<Agent> allRows(final Agent loggedIn) {
		return loggedIn.getViewableAgentsQuery(false);
	}
}
