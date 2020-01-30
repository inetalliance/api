package net.inetalliance.sonar.reporting;

import com.callgrove.obj.Agent;
import com.callgrove.obj.Call;
import com.callgrove.obj.CallCenter;
import com.callgrove.obj.ProductLine;
import com.callgrove.obj.Segment;
import com.callgrove.obj.Site;
import com.callgrove.types.ContactType;
import com.callgrove.types.SaleSource;
import net.inetalliance.angular.exception.UnauthorizedException;
import net.inetalliance.funky.Funky;
import net.inetalliance.log.Log;
import net.inetalliance.log.progress.ProgressMeter;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.types.json.JsonString;
import org.joda.time.DateMidnight;
import org.joda.time.DateTime;
import org.joda.time.Interval;

import javax.servlet.annotation.WebServlet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.callgrove.Callgrove.*;
import static java.util.stream.Collectors.*;
import static net.inetalliance.log.Log.*;
import static net.inetalliance.potion.Locator.*;
import static net.inetalliance.sonar.reporting.ProductLineClosing.*;

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
                           final Interval interval) {
    var queueInInterval = Call.isQueue.and(Call.inInterval(interval));
    return count(groups.isEmpty() ? queueInInterval :
      queueInInterval.and(Call.withSiteIn(groups)));
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

    var callQuery = Call.isQueue.and(Call.inInterval(interval));

    if (!productLines.isEmpty()) {
      callQuery = callQuery.and(Call.withQueueIn(
        productLines.stream()
          .map(pl -> getQueues(loggedIn, pl, sites))
          .flatMap(Funky::stream)
          .collect(toSet())));
    }
    if (!sites.isEmpty()) {
      callQuery = callQuery.and(Call.withSiteIn(sites));
    }

    final Query<Call> finalCallQuery = callQuery;

    final JsonList rows = new JsonList();
    final AtomicInteger totalCalls = new AtomicInteger(0);
    Query<Agent> agentQuery = allRows(sites, loggedIn, interval.getStart());
    if (!callCenters.isEmpty()) {
      agentQuery = agentQuery.and(Agent.withCallCenters(callCenters));
    }
    var allAgents = Locator.$$(agentQuery);

    var misses = new HashMap<String, Integer>();
    var total = new HashMap<String, Integer>();

    forEach(callQuery.and(Call.missed), call -> {
      meter.increment();
      forEach(Segment.withCall(call), segment -> {
        final Agent agent = segment.getAgent();
        if (allAgents.contains(agent)) {
          misses.put(agent.key, misses.getOrDefault(agent.key, 0) + 1);
        }
      });
    });

    var missesBusinessHours = new HashMap<String, Integer>();
    forEach(callQuery.and(Call.missed).and(Call.isBusinessHours), call -> {
      meter.increment();
      forEach(Segment.withCall(call), segment -> {
        final Agent agent = segment.getAgent();
        if (allAgents.contains(agent)) {
          missesBusinessHours.put(agent.key,
            missesBusinessHours.getOrDefault(agent.key, 0) + 1);
        }
      });
    });
    allAgents.forEach(a -> {
      total.put(a.key, Locator.count(finalCallQuery.and(Call.withAgent(a).and(Call.isAnswered))));
    });
    allAgents.forEach(agent -> rows.add(new JsonMap()
      .$("key", agent.key)
      .$("agent", agent.getFullName())
      .$("missedCalls", misses.getOrDefault(agent.key, 0))
      .$("missedCallsBusinessHours", missesBusinessHours.getOrDefault(agent.key, 0))
      .$("total", total.getOrDefault(agent.key, 0))));

    return new JsonMap()
      .$("rows", rows)
      .$("times",
        $$(finalCallQuery.and(Call.missed))
          .stream()
          .map(Call::getDate)
          .map(Json.jsDateTimeFormat::print)
          .map(JsonString::new)
          .collect(JsonList.collect))
      .$("total", totalCalls.get());

  }

}
