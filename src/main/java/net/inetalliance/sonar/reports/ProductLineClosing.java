package net.inetalliance.sonar.reports;

import com.callgrove.obj.*;
import com.callgrove.obj.Queue;
import com.callgrove.types.ContactType;
import com.callgrove.types.SaleSource;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.angular.exception.UnauthorizedException;
import net.inetalliance.funky.functors.F1;
import net.inetalliance.funky.functors.P1;
import net.inetalliance.funky.functors.types.str.StringFun;
import net.inetalliance.log.Log;
import net.inetalliance.log.progress.ProgressMeter;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sonar.Startup;
import net.inetalliance.types.Currency;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.types.struct.maps.LazyMap;
import org.joda.time.DateMidnight;
import org.joda.time.Interval;

import javax.servlet.annotation.WebServlet;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.callgrove.obj.Call.Q.outbound;
import static com.callgrove.obj.Call.Q.queue;
import static com.callgrove.obj.Opportunity.Q.*;
import static net.inetalliance.log.Log.getInstance;
import static net.inetalliance.potion.Locator.*;
import static net.inetalliance.sql.Aggregate.SUM;

@WebServlet({"/api/productLineClosing", "/reporting/reports/productLineClosing"})
public class ProductLineClosing
  extends CachedGroupingRangeReport<Agent, Site> {

  private static final transient Log log = getInstance(ProductLineClosing.class);

  private static final F1<String, Site> lookup = Info.$(Site.class).lookup;

  public ProductLineClosing() {
    super("site", "productLine");
  }

  @Override
  protected F1<String, Site> getGroupLookup(final String[] params) {
    return lookup;
  }

  static Set<String> getQueues(final Agent loggedIn, final ProductLine productLine, final Collection<Site> sites) {
    final Set<String> allForProductLine = Startup.productLineQueues.get(productLine.id);
    final Set<String> queues = new HashSet<>(allForProductLine);
    retainVisible(loggedIn, sites, queues);
    return queues;

  }

  static void retainVisible(Agent loggedIn, Collection<Site> sites,  Set<String> queues) {
    final Set<Site> visibleSites = loggedIn.getVisibleSites();
    if (sites != null && !sites.isEmpty()) {
      final Set<String> siteQueues = new HashSet<>(sites.size() << 2);
      for (final Site site : sites) {
        if (!visibleSites.contains(site)) {
          log.warning("%s tried to access closing data for site %d", loggedIn.key, site.id);
          throw new UnauthorizedException();
        }
        siteQueues.addAll(Queue.F.key.map(site.getQueues()));
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
    if (StringFun.empty.$(productLineId)) {
      throw new BadRequestException("Must specify product line via ?productLine=");
    }
    final ProductLine productLine = $(new ProductLine(new Integer(productLineId)));
    if (productLine == null) {
      throw new NotFoundException("Could not find product line with id %s", productLineId);
    }

    final Interval interval = getReportingInterval(start, end);

    Set<String> queues = getQueues(loggedIn, productLine, sites);

    final Info<DailyPerformance> info = Info.$(DailyPerformance.class);
    final Query<Call> callQuery =
      Call.Q.inInterval(interval).and(Call.Q.withQueueIn(queues));
    final Query<Opportunity> oppQuery =
      soldInInterval(interval)
        .and(withProductLine(productLine))
        .and(Opportunity.Q.withSources(sources))
        .and(Opportunity.Q.withContactTypes(contactTypes))
        .and(sites == null || sites.isEmpty() ? Query.all(Opportunity.class) :
          Opportunity.Q.withSiteIn(sites));
    final JsonList rows = new JsonList();
    final AtomicInteger totalCalls = new AtomicInteger(0);
    final AtomicInteger totalAgents = new AtomicInteger(0);
    final Map<Integer, AtomicInteger> callCenterCount =
      new LazyMap<Integer, AtomicInteger>(new TreeMap<>()) {
        @Override
        public AtomicInteger create(final Integer integer) {
          return new AtomicInteger(0);
        }
      };
    final Map<Integer, DailyPerformance> callCenterTotals =
      new LazyMap<Integer, DailyPerformance>(new
        HashMap<>()) {
        @Override
        public DailyPerformance create(final Integer callCenter) {
          return new DailyPerformance();
        }
      };
    Locator.forEach(allRows(loggedIn), new P1<Agent>() {
      @Override
      public void $(final Agent agent) {
        meter.increment(agent.getLastNameFirstInitial());
        final Query<Call> agentCallQuery = callQuery.and(Call.Q.withBlame(agent));
        final DailyPerformance agentTotal = new DailyPerformance();
        agentTotal.setQueueCalls(count(callQuery.and(Call.Q.withBlame(agent)).and(queue)));
        agentTotal.setOutboundCalls(count(callQuery.and(Call.Q.withAgent(agent).and(outbound))));
        agentTotal.setDumps(count(agentCallQuery.and(queue).and(Call.Q.dumped)));
        final Query<Opportunity> agentOppQuery = oppQuery.and(Opportunity.Q.withAgent(agent)).and
          (withAmountGreaterThan(productLine.getLowestReasonableAmount()));
        agentTotal.setCloses(count(agentOppQuery));
        agentTotal.setSales($$(agentOppQuery, SUM, Currency.class, "amount"));
        if (agentTotal.getCloses() > 0 || agentTotal.getQueueCalls() > 0) {
          callCenterTotals.get(agent.getCallCenter().id).add(agentTotal);
          callCenterCount.get(agent.getCallCenter().id).incrementAndGet();
          totalAgents.incrementAndGet();
          totalCalls.addAndGet(agentTotal.getQueueCalls());
          rows.add(info.toJson(agentTotal)
            .$("label", agent.getLastNameFirstInitial())
            .$("id", agent.key));
        }

      }
    });
    for (final CallCenter callCenter : loggedIn.getViewableCallCenters()) {
      final DailyPerformance callCenterTotal = callCenterTotals.get(callCenter.id);
      if (callCenterTotal.getCloses() > 0 && callCenterTotal.getQueueCalls() > 0) {
        rows.add(info.toJson(callCenterTotal)
          .$("callCenter", callCenterCount.get(callCenter.id).get())
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
    return loggedIn.getViewableAgentsQuery();
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
