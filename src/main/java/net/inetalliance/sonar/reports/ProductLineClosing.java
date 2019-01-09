package net.inetalliance.sonar.reports;

import com.callgrove.obj.*;
import com.callgrove.types.ContactType;
import com.callgrove.types.SaleSource;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.angular.exception.UnauthorizedException;
import net.inetalliance.funky.StringFun;
import net.inetalliance.log.Log;
import net.inetalliance.log.progress.ProgressMeter;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sonar.Startup;
import net.inetalliance.types.Currency;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.DateMidnight;
import org.joda.time.Interval;

import javax.servlet.annotation.WebServlet;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.callgrove.obj.Call.isOutbound;
import static com.callgrove.obj.Call.isQueue;
import static com.callgrove.obj.Opportunity.*;
import static net.inetalliance.log.Log.getInstance;
import static net.inetalliance.potion.Locator.*;
import static net.inetalliance.sql.Aggregate.SUM;

@WebServlet({"/api/productLineClosing", "/reporting/reports/productLineClosing"})
public class ProductLineClosing
	extends CachedGroupingRangeReport<Agent, Site> {

	private static final transient Log log = getInstance(ProductLineClosing.class);
	private final Info<Site> info;


	public ProductLineClosing() {
		super("site", "productLine");
		info = Info.$(Site.class);
	}

	@Override
	protected Site getGroup(final String[] params, final String key) {
		return info.lookup(key);
	}

	static Set<String> getQueues(final Agent loggedIn, final ProductLine productLine,
	                             final Collection<Site> sites) {
		final Set<String> allForProductLine = Startup.productLineQueues.get(productLine.id);
		final Set<String> queues = new HashSet<>(allForProductLine);
		retainVisible(loggedIn, sites, queues);
		return queues;

	}

	static void retainVisible(Agent loggedIn, Collection<Site> sites, Set<String> queues) {
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
		final String productLineId = extras.get("productLine");
		if (StringFun.isEmpty(productLineId)) {
			throw new BadRequestException("Must specify product line via ?productLine=");
		}
		final ProductLine productLine = $(new ProductLine(Integer.valueOf(productLineId)));
		if (productLine == null) {
			throw new NotFoundException("Could not find product line with id %s", productLineId);
		}

		final Interval interval = getReportingInterval(start, end);

		Set<String> queues = getQueues(loggedIn, productLine, sites);

		final Info<DailyPerformance> info = Info.$(DailyPerformance.class);
		final Query<Call> callQuery =
			Call.inInterval(interval)
				.and(Call.withQueueIn(queues));
		final Query<Opportunity> oppQuery =
			soldInInterval(interval)
				.and(withProductLine(productLine))
				.and(Opportunity.withSources(sources))
				.and(Opportunity.withContactTypes(contactTypes))
				.and(sites == null || sites.isEmpty() ? Query.all(Opportunity.class) :
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
			agentTotal.setQueueCalls(count(queueCallCountQuery));
			agentTotal.setOutboundCalls(count(callQuery.and(Call.withAgent(agent).and(isOutbound))));
			agentTotal.setDumps(count(agentCallQuery.and(isQueue).and(Call.isDumped)));
			final Query<Opportunity> agentOppQuery = oppQuery.and(Opportunity.withAgent(agent)).and
				(withAmountGreaterThan(productLine.getLowestReasonableAmount()));
			agentTotal.setCloses(count(agentOppQuery));
			agentTotal.setSales($$(agentOppQuery, SUM, Currency.class, "amount"));
			if (agentTotal.getCloses() > 0 || agentTotal.getQueueCalls() > 0) {
				callCenterTotals.getOrDefault(agent.getCallCenter().id, new DailyPerformance()).add(agentTotal);
				callCenterCount.getOrDefault(agent.getCallCenter().id, new AtomicInteger(0)).incrementAndGet();
				totalAgents.incrementAndGet();
				totalCalls.addAndGet(agentTotal.getQueueCalls());
				rows.add(info.toJson(agentTotal)
					.$("label", agent.getLastNameFirstInitial())
					.$("id", agent.key));
			}

		});
		for (final CallCenter callCenter : loggedIn.getViewableCallCenters()) {
			final DailyPerformance callCenterTotal = callCenterTotals.get(callCenter.id);
			if (callCenterTotal.getCloses() > 0 && callCenterTotal.getQueueCalls() > 0) {
				rows.add(info.toJson(callCenterTotal)
					.$("callCenter", callCenterCount.getOrDefault(callCenter.id,new AtomicInteger(0)).get())
					.$("label", callCenter.getName()));
			}
		}
		return new JsonMap()
			.$("rows", rows)
			.$("total", new JsonMap()
				.$("agents", totalAgents.get())
				.$("queueCalls", totalCalls.get()));

	}

	@Override
	protected Query<Agent> allRows(final Agent loggedIn) {
		return loggedIn.getViewableAgentsQuery(false);
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
}
