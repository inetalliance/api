package net.inetalliance.sonar.reporting;

import com.callgrove.obj.Queue;
import com.callgrove.obj.*;
import com.callgrove.types.*;
import net.inetalliance.angular.exception.*;
import net.inetalliance.log.*;
import net.inetalliance.log.progress.*;
import net.inetalliance.potion.*;
import net.inetalliance.potion.info.*;
import net.inetalliance.potion.query.*;
import net.inetalliance.sonar.api.*;
import net.inetalliance.types.Currency;
import net.inetalliance.types.json.*;
import org.joda.time.*;

import javax.servlet.annotation.*;
import java.util.*;
import java.util.concurrent.atomic.*;

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
		super("site", "agent", "uniqueCid");
		info = Info.$(Site.class);
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
	protected Query<Agent> allRows(final Agent loggedIn) {
		return loggedIn.getViewableAgentsQuery(false);
	}

	@Override
	protected Site getGroup(final String[] params, final String key) {
		return info.lookup(key);
	}

	@Override
	protected int getJobSize(final Agent loggedIn, final int numGroups) {
		return count(allRows(loggedIn));
	}

	@Override
	protected JsonMap generate(final EnumSet<SaleSource> sources, final EnumSet<ContactType> contactTypes,
			final Agent loggedIn, final ProgressMeter meter, final DateMidnight start, final DateMidnight end,
			final Set<Site> sites, final Map<String, String> extras) {
		if (loggedIn == null || !(loggedIn.isManager() || loggedIn.isTeamLeader())) {
			log.warning("%s tried to access closing report data", loggedIn == null ? "Nobody?" : loggedIn.key);
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

		boolean uniqueCid = Boolean.valueOf(extras.getOrDefault("uniqueCid", "false"));

		final Interval interval = getReportingInterval(start, end);

		final Set<String> queues = Locator.$A(Queue.class).stream().map(q -> q.key).collect(toSet());
		ProductLineClosing.retainVisible(loggedIn, sites, queues);

		final Info<DailyPerformance> info = Info.$(DailyPerformance.class);
		final Query<Call> callQuery = Call.inInterval(interval);
		final Query<Opportunity> oppQuery = soldInInterval(interval).and(Opportunity.withAgent(agent))
		                                                            .and(Opportunity.withSources(sources))
		                                                            .and(Opportunity.withContactTypes(contactTypes))
		                                                            .and(sites == null || sites.isEmpty() ? Query.all(
				                                                            Opportunity.class) :
				                                                            Opportunity.withSiteIn(sites));
		final JsonList rows = new JsonList();
		Locator.forEach(Query.all(ProductLine.class), productLine -> {

			final AtomicInteger n = new AtomicInteger(0);
			final Set<String> productLineQueues = new HashSet<>(queues);
			productLineQueues.retainAll(Startup.productLineQueues.getOrDefault(productLine.id, Set.of()));
			final Query<Call> productLineCallQuery =
					productLineQueues.isEmpty() ? Query.none(Call.class) : callQuery.and(Call.withQueueIn(productLineQueues));
			final DailyPerformance productLineTotal = new DailyPerformance();
			final Query<Call> queueQuery =
					productLineCallQuery.and(isQueue).and(withSourceIn(sources)).and(withBlame(agent));
			productLineTotal.setQueueCalls(uniqueCid ? countDistinct(queueQuery, "callerId_number") : count(queueQuery));

			final Query<Call> outboundQuery = productLineCallQuery.and(isOutbound).and(Call.withAgent(agent));
			if (uniqueCid) {
				productLineTotal.setOutboundCalls(
						countDistinct(outboundQuery.join(Segment.class, "call"), "segment.callerId_number"));

			} else {
				productLineTotal.setOutboundCalls(count(outboundQuery));
			}
			productLineTotal.setDumps(count(productLineCallQuery.and(isQueue).and(Call.isDumped).and(Call.withBlame(agent))));
			final Query<Opportunity> agentOppQuery = oppQuery.and(
					withProductLine(productLine).and(withAmountGreaterThan(productLine.getLowestReasonableAmount())));
			productLineTotal.setCloses(count(agentOppQuery));
			productLineTotal.setSales($$(agentOppQuery, SUM, Currency.class, "amount"));
			if (productLineTotal.getCloses() > 0 || productLineTotal.getQueueCalls() > 0) {
				n.incrementAndGet();
				rows.add(info.toJson(productLineTotal).$("label", productLine.getName()).$("id", productLine.id));
			}
			meter.increment(productLine.getName());
		});
		return new JsonMap().$("rows", rows);

	}
}
