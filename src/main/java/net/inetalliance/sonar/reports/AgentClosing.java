package net.inetalliance.sonar.reports;

import com.callgrove.obj.Agent;
import com.callgrove.obj.Call;
import com.callgrove.obj.DailyPerformance;
import com.callgrove.obj.Opportunity;
import com.callgrove.obj.ProductLine;
import com.callgrove.obj.Queue;
import com.callgrove.obj.Site;
import com.callgrove.types.ContactType;
import com.callgrove.types.SaleSource;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.angular.exception.UnauthorizedException;
import net.inetalliance.funky.functors.F1;
import net.inetalliance.funky.functors.P1;
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
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.callgrove.obj.Call.Q.*;
import static com.callgrove.obj.Opportunity.Q.*;
import static net.inetalliance.funky.functors.types.str.StringFun.*;
import static net.inetalliance.log.Log.*;
import static net.inetalliance.potion.Locator.*;
import static net.inetalliance.sql.Aggregate.*;

@WebServlet({"/api/agentClosing", "/reporting/reports/agentClosing"})
public class AgentClosing
  extends CachedGroupingRangeReport<Agent, Site> {

  private static final transient Log log = getInstance(AgentClosing.class);

  private static final F1<String, Site> lookup = Info.$(Site.class).lookup;

  public AgentClosing() {
    super("site", "agent");
  }

  @Override
  protected F1<String, Site> getGroupLookup(final String[] params) {
    return lookup;
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
    if (empty.$(agentKey)) {
      throw new BadRequestException("Must specify agent via ?agent=");
    }
    final Agent agent = $(new Agent(agentKey));
    if (agent == null) {
      throw new NotFoundException("Could not find agent with key %s", agentKey);
    }

    final Interval interval = getReportingInterval(start, end);

    final Set<String> queues = Queue.F.key.copy(Locator.$A(Queue.class));
    ProductLineClosing.retainVisible(loggedIn, sites, queues);

    final Info<DailyPerformance> info = Info.$(DailyPerformance.class);
    final Query<Call> callQuery = Call.Q.inInterval(interval);
    final Query<Opportunity> oppQuery =
      soldInInterval(interval)
        .and(Opportunity.Q.withAgent(agent))
        .and(Opportunity.Q.withSources(sources))
        .and(Opportunity.Q.withContactTypes(contactTypes))
        .and(sites == null || sites.isEmpty() ? Query.all(Opportunity.class) :
          Opportunity.Q.withSiteIn(sites));
    final JsonList rows = new JsonList();
    Locator.forEach(Query.all(ProductLine.class), new P1<ProductLine>() {
      @Override
      public void $(final ProductLine productLine) {

        final AtomicInteger n = new AtomicInteger(0);
        final Set<String> productLineQueues = new HashSet<>(queues);
        productLineQueues.retainAll(Startup.productLineQueues.get(productLine.id));
        final Query<Call> productLineCallQuery = productLineQueues.isEmpty()
          ? Query.none(Call.class)
          : callQuery.and(Call.Q.withQueueIn(productLineQueues));
        final DailyPerformance productLineTotal = new DailyPerformance();
        productLineTotal.setQueueCalls(count(productLineCallQuery
          .and(queue)
          .and(Call.Q.withSourceIn(sources))
          .and(Call.Q.withBlame(agent))));
        productLineTotal.setOutboundCalls(count(productLineCallQuery
          .and(outbound)
          .and(Call.Q.withAgent(agent))));
        productLineTotal.setDumps(count(productLineCallQuery.and(queue).and(Call.Q.dumped).and(
          Call.Q.withBlame(agent))));
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
      }
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
