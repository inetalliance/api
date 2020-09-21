package net.inetalliance.sonar.reporting;

import com.callgrove.obj.*;
import com.callgrove.types.ContactType;
import com.callgrove.types.SaleSource;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.angular.exception.UnauthorizedException;
import net.inetalliance.funky.Funky;
import net.inetalliance.funky.StringFun;
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
import org.joda.time.DateTime;
import org.joda.time.Interval;

import javax.servlet.annotation.WebServlet;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.callgrove.Callgrove.getReportingInterval;
import static com.callgrove.obj.Call.isOutbound;
import static com.callgrove.obj.Call.isQueue;
import static com.callgrove.obj.Opportunity.*;
import static java.util.stream.Collectors.toSet;
import static net.inetalliance.log.Log.getInstance;
import static net.inetalliance.potion.Locator.*;
import static net.inetalliance.sql.Aggregate.SUM;

@WebServlet({"/reporting/reports/productLineClosing"})
public class ProductLineClosing
    extends CachedGroupingRangeReport<Agent, Site> {

  private static final transient Log log = getInstance(ProductLineClosing.class);
  private final Info<Site> info;

  public ProductLineClosing() {
    super("site", "productLine", "uniqueCid", "noTransfers");
    info = Info.$(Site.class);
  }

  static Set<String> getQueues(final Agent loggedIn, final ProductLine productLine,
      final Collection<Site> sites) {
    final Set<String> allForProductLine = Startup.productLineQueues.computeIfAbsent(productLine.id, i->new HashSet<>());
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
  protected String getGroupLabel(final Site group) {
    return group.getName();
  }

  @Override
  protected String getId(final Agent row) {
    return row.key;
  }

  @Override
  protected Query<Agent> allRows(final Set<Site> groups, final Agent loggedIn,
      final DateTime intervalStart) {
    return loggedIn.getViewableAgentsQuery(false).and(Agent.activeAfter(intervalStart))
        .and(Agent.isSales);
  }

  @Override
  protected Site getGroup(final String[] params, final String key) {
    return info.lookup(key);
  }

  @Override
  protected int getJobSize(final Agent loggedIn, final Set<Site> groups,
      final Interval intervalStart) {
    return count(allRows(groups, loggedIn, intervalStart.getStart()));
  }

  @Override
  protected JsonMap generate(final EnumSet<SaleSource> sources,
      final EnumSet<ContactType> contactTypes,
      final Agent loggedIn, final ProgressMeter meter, final DateMidnight start,
      final DateMidnight end,
      final Set<Site> sites, Collection<CallCenter> callCenters,
      final Map<String, String[]> extras) {
    if (loggedIn == null || !(loggedIn.isManager() || loggedIn.isTeamLeader())) {
      log.warning("%s tried to access closing report data",
          loggedIn == null ? "Nobody?" : loggedIn.key);
      throw new UnauthorizedException();
    }
    final String[] productLineIds = extras.get("productLine");
    if (productLineIds.length == 0 || StringFun
        .isEmpty(productLineIds[0])) {
      throw new BadRequestException("Must specify product line via ?productLine=");
    }
    final Set<ProductLine> productLines = Arrays.stream(productLineIds)
        .map(id -> Locator.$(new ProductLine(Integer.valueOf(id))))
        .collect(toSet());
    if (productLines.isEmpty()) {
      throw new NotFoundException("Could not find product lines with ids %s",
          Arrays.toString(productLineIds));
    }
    boolean uniqueCid = Boolean.valueOf(getSingleExtra(extras, "uniqueCid", "false"));
    boolean noTransfers = Boolean.valueOf(getSingleExtra(extras, "noTransfers", "false"));

    final Interval interval = getReportingInterval(start, end);

    final Set<String> queues =
        productLines.stream().map(pl -> getQueues(loggedIn, pl, sites)).flatMap(Funky::stream)
            .collect(toSet());

    final Info<DailyPerformance> info = Info.$(DailyPerformance.class);
    final Query<Call> callQuery = Call.inInterval(interval).and(Call.withQueueIn(queues));
    Query<Opportunity> oppQuery = soldInInterval(interval)
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
    final JsonList rows = new JsonList();
    final AtomicInteger totalCalls = new AtomicInteger(0);
    final AtomicInteger totalAgents = new AtomicInteger(0);
    final Map<Integer, AtomicInteger> callCenterCount = new HashMap<>();
    final Map<Integer, DailyPerformance> callCenterTotals = new HashMap<>();
    final Query<Opportunity> finalOppQuery = oppQuery;
    Locator.forEach(allRows(sites, loggedIn, interval.getStart()), agent -> {
      meter.increment(agent.getLastNameFirstInitial());
      final Query<Call> agentCallQuery = callQuery.and(Call.withBlame(agent));
      final DailyPerformance agentTotal = new DailyPerformance();
      Query<Call> queueCallCountQuery = callQuery.and(Call.withBlame(agent)).and(isQueue);
      if (!sources.isEmpty()) {
        queueCallCountQuery = queueCallCountQuery.and(Call.withSourceIn(sources));
      }
      if (noTransfers) {
        queueCallCountQuery = AgentClosing.noTransfers(queueCallCountQuery);
      }
      agentTotal.setQueueCalls(
          uniqueCid ? countDistinct(queueCallCountQuery, "callerId_number")
              : count(queueCallCountQuery));
      final Query<Call> outboundQuery = callQuery.and(Call.withAgent(agent).and(isOutbound));
      if (uniqueCid) {
        agentTotal.setOutboundCalls(
            countDistinct(outboundQuery.join(Segment.class, "call"), "segment.callerid_number"));

      } else {
        agentTotal.setOutboundCalls(count(outboundQuery));
      }
      agentTotal.setDumps(count(agentCallQuery.and(isQueue).and(Call.isDumped)));
      int closes = 0;
      Currency sales = Currency.ZERO;
      for (ProductLine productLine : productLines) {

        final Query<Opportunity> agentOppQuery = finalOppQuery.and(withProductLine(productLine))
            .and(Opportunity.withAgent(agent))
            .and(withAmountGreaterThan(
                productLine.getLowestReasonableAmount()));
        closes += count(agentOppQuery);
        sales = sales.add($$(agentOppQuery, SUM, Currency.class, "amount"));
      }
      agentTotal.setCloses(closes);
      agentTotal.setSales(sales);
      if (agentTotal.getCloses() > 0 || agentTotal.getQueueCalls() > 0) {
        callCenterTotals.computeIfAbsent(agent.getCallCenter().id, k -> new DailyPerformance())
            .add(agentTotal);
        callCenterCount.computeIfAbsent(agent.getCallCenter().id, k -> new AtomicInteger(0))
            .incrementAndGet();
        totalAgents.incrementAndGet();
        totalCalls.addAndGet(agentTotal.getQueueCalls());
        rows.add(
            info.toJson(agentTotal).$("label", agent.getLastNameFirstInitial()).$("id", agent.key));
      }

    });
    for (final CallCenter callCenter : loggedIn.getViewableCallCenters()) {
      final DailyPerformance callCenterTotal =
          callCenterTotals.computeIfAbsent(callCenter.id, k -> new DailyPerformance());
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
