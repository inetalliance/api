package net.inetalliance.sonar.reporting;

import com.ameriglide.phenix.core.Iterables;
import com.ameriglide.phenix.core.Log;
import com.callgrove.obj.*;
import com.callgrove.types.ContactType;
import com.callgrove.types.SaleSource;
import jakarta.servlet.annotation.WebServlet;
import lombok.val;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.angular.exception.UnauthorizedException;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sonar.api.Startup;
import net.inetalliance.sql.DateTimeInterval;
import net.inetalliance.types.Currency;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.util.ProgressMeter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.ameriglide.phenix.core.Strings.isEmpty;
import static com.callgrove.Callgrove.getReportingInterval;
import static com.callgrove.obj.Call.isOutbound;
import static com.callgrove.obj.Call.isQueue;
import static com.callgrove.obj.Opportunity.*;
import static java.util.stream.Collectors.toSet;
import static net.inetalliance.potion.Locator.*;
import static net.inetalliance.sql.Aggregate.SUM;

@WebServlet({"/reporting/reports/productLineClosing"})
public class ProductLineClosing
        extends CachedGroupingRangeReport<Agent, Site> {

    private static final Log log = new Log();
    private final Info<Site> info;

    public ProductLineClosing() {
        super("site", "productLine", "uniqueCid", "noTransfers");
        info = Info.$(Site.class);
    }

    static Set<String> getQueues(final Agent loggedIn, final ProductLine productLine,
                                 final Collection<Site> sites) {
        val allForProductLine = Startup.productLineQueues.computeIfAbsent(productLine.id, p -> new HashSet<>());
        final Set<String> queues = new HashSet<>(allForProductLine);
        retainVisible(loggedIn, sites, queues);
        return queues;

    }

    static void retainVisible(Agent loggedIn, Collection<Site> sites, Set<String> queues) {
        val visibleSites = loggedIn.getVisibleSites();
        if (sites != null && !sites.isEmpty()) {
            val siteQueues = new HashSet<String>(sites.size() << 2);
            for (val site : sites) {
                if (!visibleSites.contains(site)) {
                    log.warn(() -> "%s tried to access closing data for site %d".formatted(loggedIn.key, site.id));
                    throw new UnauthorizedException();
                }
                site.getQueues().forEach(q -> siteQueues.add(q.key));
            }
            queues.retainAll(siteQueues);
        }
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
    protected Query<Agent> allRows(final Set<Site> groups, final Agent loggedIn,
                                   final LocalDateTime intervalStart) {
        return loggedIn.getViewableAgentsQuery(false).and(Agent.activeAfter(intervalStart))
                .and(Agent.isSales);
    }

    @Override
    protected Site getGroup(final String[] params, final String key) {
        return info.lookup(key);
    }

    @Override
    protected int getJobSize(final Agent loggedIn, final Set<Site> groups,
                             final DateTimeInterval intervalStart) {
        return count(allRows(groups, loggedIn, intervalStart.start()));
    }

    @Override
    protected JsonMap generate(final Set<SaleSource> sources,
                               final Set<ContactType> contactTypes,
                               final Agent loggedIn, final ProgressMeter meter, final LocalDate start,
                               final LocalDate end,
                               final Set<Site> sites, Collection<CallCenter> callCenters,
                               final Map<String, String[]> extras) {
        if (loggedIn == null || !(loggedIn.isManager() || loggedIn.isTeamLeader())) {
            log.warn(() -> "%s tried to access closing report data".formatted(
                    loggedIn == null ? "Nobody?" : loggedIn.key));
            throw new UnauthorizedException();
        }
        val productLineIds = extras.get("productLine");
        if (productLineIds.length == 0 || isEmpty(productLineIds[0])) {
            throw new BadRequestException("Must specify product line via ?productLine=");
        }
        val productLines = Arrays.stream(productLineIds)
                .map(id -> Locator.$(new ProductLine(Integer.valueOf(id))))
                .collect(toSet());
        if (productLines.isEmpty()) {
            throw new NotFoundException("Could not find product lines with ids %s",
                    Arrays.toString(productLineIds));
        }
        var uniqueCid = Boolean.parseBoolean(getSingleExtra(extras, "uniqueCid", "false"));
        var noTransfers = Boolean.parseBoolean(getSingleExtra(extras, "noTransfers", "false"));

        var interval = getReportingInterval(start, end);

        val queues =
                productLines.stream().map(pl -> getQueues(loggedIn, pl, sites)).flatMap(Iterables::stream)
                        .collect(toSet());

        val info = Info.$(DailyPerformance.class);
        val callQuery = Call.inInterval(interval).and(Call.withQueueIn(queues));
        var oppQuery = soldInInterval(interval)
                .and(sources.isEmpty()
                        ? isOnline.negate()
                        : Opportunity.withSources(sources))
                .and(Opportunity.withContactTypes(contactTypes))
                .and(sites == null || sites.isEmpty() ? Query.all(
                        Opportunity.class) :
                        Opportunity.withSiteIn(sites));
        if (!callCenters.isEmpty()) {
            oppQuery = oppQuery.and(Opportunity.withCallCenterIn(callCenters));
        }
        val rows = new JsonList();
        val totalCalls = new AtomicInteger(0);
        val totalAgents = new AtomicInteger(0);
        final Map<Integer, AtomicInteger> callCenterCount = new HashMap<>();
        final Map<Integer, DailyPerformance> callCenterTotals = new HashMap<>();
        val finalOppQuery = oppQuery;
        Locator.forEach(allRows(sites, loggedIn, interval.start()), agent -> {
            meter.increment(agent.getLastNameFirstInitial());
            val agentCallQuery = callQuery.and(Call.withBlame(agent));
            val agentTotal = new DailyPerformance();
            var queueCallCountQuery = callQuery.and(Call.withBlame(agent)).and(isQueue);
            if (!sources.isEmpty()) {
                queueCallCountQuery = queueCallCountQuery.and(Call.withSourceIn(sources));
            }
            if (noTransfers) {
                queueCallCountQuery = AgentClosing.noTransfers(queueCallCountQuery);
            }
            agentTotal.setQueueCalls(
                    uniqueCid ? countDistinct(queueCallCountQuery, "callerId_number")
                            : count(queueCallCountQuery));
            val outboundQuery = callQuery.and(Call.withAgent(agent).and(isOutbound));
            if (uniqueCid) {
                agentTotal.setOutboundCalls(
                        countDistinct(outboundQuery.join(Segment.class, "call"), "segment.callerid_number"));

            } else {
                agentTotal.setOutboundCalls(count(outboundQuery));
            }
            agentTotal.setDumps(count(agentCallQuery.and(isQueue).and(Call.isDumped)));
            var closes = 0;
            var sales = Currency.ZERO;
            for (var productLine : productLines) {

                val agentOppQuery = finalOppQuery.and(withProductLine(productLine))
                        .and(Opportunity.withAgent(agent))
                        .and(withAmountGreaterThan(
                                productLine.getLowestReasonableAmount()));
                closes += count(agentOppQuery);
                sales = sales.add($$(agentOppQuery, SUM, Currency.class, "amount"));
            }
            agentTotal.setCloses(closes);
            agentTotal.setSales(sales);
            if (agentTotal.getCloses() > 0 || agentTotal.getQueueCalls() > 0) {
                callCenterTotals.computeIfAbsent(agent.getCallCenter().id, i -> new DailyPerformance())
                        .add(agentTotal);
                callCenterCount.computeIfAbsent(agent.getCallCenter().id, i -> new AtomicInteger(0))
                        .incrementAndGet();
                totalAgents.incrementAndGet();
                totalCalls.addAndGet(agentTotal.getQueueCalls());
                rows.add(
                        info.toJson(agentTotal).$("label", agent.getLastNameFirstInitial()).$("id", agent.key));
            }

        });
        for (val callCenter : loggedIn.getViewableCallCenters()) {
            val callCenterTotal =
                    callCenterTotals.computeIfAbsent(callCenter.id, i -> new DailyPerformance());
            if (callCenterTotal.getCloses() > 0 && callCenterTotal.getQueueCalls() > 0) {
                rows.add(info.toJson(callCenterTotal)
                        .$("callCenter",
                                callCenterCount.getOrDefault(callCenter.id, new AtomicInteger(0)).get())
                        .$("label", callCenter.getName()));
            }
        }
        return new JsonMap().$("rows", rows)
                .$("total", new JsonMap().$("agents", totalAgents.get()).$("queueCalls", totalCalls.get()));

    }
}
