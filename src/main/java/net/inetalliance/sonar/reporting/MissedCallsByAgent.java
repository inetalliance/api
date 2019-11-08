package net.inetalliance.sonar.reporting;

import com.callgrove.obj.Agent;
import com.callgrove.obj.Call;
import com.callgrove.obj.CallCenter;
import com.callgrove.obj.ProductLine;
import com.callgrove.obj.Site;
import com.callgrove.types.ContactType;
import com.callgrove.types.SaleSource;
import net.inetalliance.angular.exception.UnauthorizedException;
import net.inetalliance.log.Log;
import net.inetalliance.log.progress.ProgressMeter;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.DateMidnight;
import org.joda.time.DateTime;
import org.joda.time.Interval;

import javax.servlet.annotation.WebServlet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.callgrove.Callgrove.*;
import static java.util.stream.Collectors.*;
import static net.inetalliance.log.Log.*;
import static net.inetalliance.potion.Locator.*;

@WebServlet({"/reporting/reports/missedCallsByAgent"})
public class MissedCallsByAgent
  extends CachedGroupingRangeReport<Agent, Site> {

  private static final transient Log log = getInstance(MissedCallsByAgent.class);
  private final Info<Site> info;

  public MissedCallsByAgent() {
    super("site", "productLine");
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
                           final DateTime intervalStart) {
    return count(allRows(groups, loggedIn, intervalStart));
  }

  @Override
  protected JsonMap generate(final EnumSet<SaleSource> sources,
                             final EnumSet<ContactType> contactTypes,
                             final Agent loggedIn, final ProgressMeter meter,
                             final DateMidnight start,
                             final DateMidnight end,
                             final Set<Site> sites, Collection<CallCenter> callCenters,
                             final Map<String, String[]> extras) {
    if (loggedIn == null || !(loggedIn.isManager() || loggedIn.isTeamLeader())) {
      log.warning("%s tried to access closing report data",
        loggedIn == null ? "Nobody?" : loggedIn.key);
      throw new UnauthorizedException();
    }
    final String[] productLineIds = extras.get("productLine");
    final Set<ProductLine> productLines = productLineIds == null ?
      Collections.emptySet() : Arrays.stream(productLineIds)
      .map(id -> Locator.$(new ProductLine(Integer.valueOf(id))))
      .collect(toSet());

    final Interval interval = getReportingInterval(start, end);

    Query<Call> callQuery = Call.inInterval(interval)
      .and(Call.withOpportunity());

    if (!productLines.isEmpty()) {
      callQuery = callQuery.and(
        Query.and(Call.class,
          productLines.stream().map(Call::withProductLine).collect(toList())));
    }
    final Query<Call> finalCallQuery = callQuery;

    final JsonList rows = new JsonList();
    final AtomicInteger totalCalls = new AtomicInteger(0);
    Query<Agent> agentQuery = allRows(sites, loggedIn, interval.getStart());
    if (!callCenters.isEmpty()) {
      agentQuery = agentQuery.and(Agent.withCallCenters(callCenters));
    }
    Locator.forEach(agentQuery,
      agent -> {
        meter.increment(agent.getFirstNameLastInitial());
        final int count = count(finalCallQuery.and(Call.withAgent(agent)));
        if (count > 0) {
          totalCalls.getAndAdd(count);
          rows.add(new JsonMap()
            .$("agent", agent.getFirstNameLastInitial())
            .$("calls", count));
        }
      });
    return new JsonMap()
      .$("rows", rows)
      .$("total", totalCalls.get());

  }
}
