package net.inetalliance.sonar.reporting;

import com.callgrove.obj.*;
import com.callgrove.types.*;
import net.inetalliance.angular.exception.*;
import net.inetalliance.funky.*;
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
import static net.inetalliance.log.Log.*;
import static net.inetalliance.potion.Locator.*;
import static net.inetalliance.sql.Aggregate.*;

@WebServlet({"/reporting/reports/productLineClosing"})
public class ProductLineClosing
		extends CachedGroupingRangeReport<Agent, Site> {

	private static final transient Log log = getInstance(ProductLineClosing.class);
	private final Info<Site> info;

	public ProductLineClosing() {
		super("site", "productLine", "uniqueCid");
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
		final String productLineId = extras.get("productLine");
		if (StringFun.isEmpty(productLineId)) {
			throw new BadRequestException("Must specify product line via ?productLine=");
		}
		final ProductLine productLine = $(new ProductLine(Integer.valueOf(productLineId)));
		if (productLine == null) {
			throw new NotFoundException("Could not find product line with id %s", productLineId);
		}
		boolean uniqueCid = Boolean.valueOf(extras.getOrDefault("uniqueCid", "false"));

		final Interval interval = getReportingInterval(start, end);

		Set<String> queues = getQueues(loggedIn, productLine, sites);

		final Info<DailyPerformance> info = Info.$(DailyPerformance.class);
		final Query<Call> callQuery = Call.inInterval(interval).and(Call.withQueueIn(queues));
		final Query<Opportunity> oppQuery = soldInInterval(interval).and(withProductLine(productLine))
		                                                            .and(sources.isEmpty()
				                                                                 ? isOnline.negate()
				                                                                 : Opportunity.withSources(sources))
		                                                            .and(Opportunity.withContactTypes(contactTypes))
		                                                            .and(sites == null || sites.isEmpty() ? Query.all(
				                                                            Opportunity.class) :
				                                                            Opportunity.withSiteIn(sites));
		final JsonList rows = new JsonList();
		final AtomicInteger totalCalls = new AtomicInteger(0);
		final AtomicInteger totalAgents = new AtomicInteger(0);
		final Map<Integer, AtomicInteger> callCenterCount = new HashMap<>();
		final Map<Integer, DailyPerformance> callCenterTotals = new HashMap<>();
		Locator.forEach(allRows(loggedIn), agent -> {
			meter.increment(agent.getLastNameFirstInitial());
			final Query<Call> agentCallQuery = callQuery.and(Call.withBlame(agent));
			final DailyPerformance agentTotal = new DailyPerformance();
			Query<Call> queueCallCountQuery = callQuery.and(Call.withBlame(agent)).and(isQueue);
			if (!sources.isEmpty()) {
				queueCallCountQuery = queueCallCountQuery.and(Call.withSourceIn(sources));
			}
			agentTotal.setQueueCalls(
					uniqueCid ? countDistinct(queueCallCountQuery, "callerId_number") : count(queueCallCountQuery));
			final Query<Call> outboundQuery = callQuery.and(Call.withAgent(agent).and(isOutbound));
			if (uniqueCid) {
				agentTotal.setOutboundCalls(
						countDistinct(outboundQuery.join(Segment.class, "call"), "segment.callerid_number"));

			} else {
				agentTotal.setOutboundCalls(count(outboundQuery));
			}
			agentTotal.setDumps(count(agentCallQuery.and(isQueue).and(Call.isDumped)));
			final Query<Opportunity> agentOppQuery = oppQuery.and(Opportunity.withAgent(agent))
			                                                 .and(withAmountGreaterThan(
					                                                 productLine.getLowestReasonableAmount()));
			agentTotal.setCloses(count(agentOppQuery));
			agentTotal.setSales($$(agentOppQuery, SUM, Currency.class, "amount"));
			if (agentTotal.getCloses() > 0 || agentTotal.getQueueCalls() > 0) {
				callCenterTotals.computeIfAbsent(agent.getCallCenter().id, k -> new DailyPerformance()).add(agentTotal);
				callCenterCount.computeIfAbsent(agent.getCallCenter().id, k -> new AtomicInteger(0)).incrementAndGet();
				totalAgents.incrementAndGet();
				totalCalls.addAndGet(agentTotal.getQueueCalls());
				rows.add(info.toJson(agentTotal).$("label", agent.getLastNameFirstInitial()).$("id", agent.key));
			}

		});
		for (final CallCenter callCenter : loggedIn.getViewableCallCenters()) {
			final DailyPerformance callCenterTotal =
					callCenterTotals.computeIfAbsent(callCenter.id, k -> new DailyPerformance());
			if (callCenterTotal.getCloses() > 0 && callCenterTotal.getQueueCalls() > 0) {
				rows.add(info.toJson(callCenterTotal)
				             .$("callCenter", callCenterCount.getOrDefault(callCenter.id, new AtomicInteger(0)).get())
				             .$("label", callCenter.getName()));
			}
		}
		return new JsonMap().$("rows", rows)
		                    .$("total", new JsonMap().$("agents", totalAgents.get()).$("queueCalls", totalCalls.get()));

	}

	static Set<String> getQueues(final Agent loggedIn, final ProductLine productLine, final Collection<Site> sites) {
		final Set<String> allForProductLine = Startup.productLineQueues.get(productLine.id);
		final Set<String> queues = new HashSet<>(allForProductLine);
		retainVisible(loggedIn, sites, queues);
		return queues;

	}

	public static void retainVisible(Agent loggedIn, Collection<Site> sites, Set<String> queues) {
		final Set<Site> visibleSites = loggedIn.getVisibleSites();
		if (sites != null && !sites.isEmpty()) {
			final Set<String> siteQueues = new HashSet<>(sites.size() << 2);
			for (final Site site : sites) {
				if (!visibleSites.contains(site)) {
					log.warning("%s tried to access closing data for site %d", loggedIn.key, site.id);
					throw new UnauthorizedException();
				}
				site.getQueues().forEach(q -> siteQueues.add(q.key));
			}
			queues.retainAll(siteQueues);
		}
	}
}
