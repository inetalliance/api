package net.inetalliance.sonar.reporting;

import static com.callgrove.Callgrove.getReportingInterval;
import static java.util.concurrent.TimeUnit.SECONDS;
import static net.inetalliance.potion.Locator.$$;
import static net.inetalliance.potion.Locator.count;
import static net.inetalliance.potion.Locator.forEach;
import static net.inetalliance.sql.Aggregate.SUM;
import static net.inetalliance.sql.OrderBy.Direction.ASCENDING;
import static org.joda.time.DateTimeConstants.MILLIS_PER_DAY;

import com.callgrove.obj.Agent;
import com.callgrove.obj.Call;
import com.callgrove.obj.CallCenter;
import com.callgrove.obj.DailyProductLineVisits;
import com.callgrove.obj.Opportunity;
import com.callgrove.types.ContactType;
import com.callgrove.types.SaleSource;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import net.inetalliance.funky.math.NumberMath;
import net.inetalliance.log.Log;
import net.inetalliance.log.progress.ProgressMeter;
import net.inetalliance.potion.obj.IdPo;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.Currency;
import net.inetalliance.types.Named;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.types.json.JsonString;
import org.joda.time.DateMidnight;
import org.joda.time.DateTime;
import org.joda.time.Interval;

public abstract class Performance<R extends IdPo & Named, G extends IdPo>
    extends CachedGroupingRangeReport<R, G> {

  private static final transient Log log = Log.getInstance(Performance.class);
  private Map<Integer, Set<String>> groupQueues;
  private Map<Integer, Set<String>> rowQueues;

  protected Performance(final String groupParam) {
    super(groupParam);
  }

  @Override
  public void destroy() {
    super.destroy();
    groupQueues.clear();
    rowQueues.clear();
  }

  @Override
  protected int getJobSize(final Agent loggedIn, final int numGroups,
      final DateTime intervalStart) {
    return rowQueues.size();
  }

  // End Servlet methods
  protected JsonMap generate(final EnumSet<SaleSource> sources,
      final EnumSet<ContactType> contactTypes,
      final Agent loggedIn, final ProgressMeter meter, final DateMidnight start,
      final DateMidnight end,
      final Set<G> groups, Collection<CallCenter> callCenters, final Map<String, String[]> extras) {
    final Query<R> allRows = allRows(loggedIn, start.toDateTime());
    final Map<String, Interval> intervals = new TreeMap<>();
    final Interval current = getReportingInterval(start, end);
    intervals.put("current", current);
    final int days = (int) (current.toDurationMillis() / MILLIS_PER_DAY);
    intervals.put("prev", getReportingInterval(start.minusDays(days), end.minusDays(days)));
    intervals
        .put("prev2", getReportingInterval(start.minusDays(2 * days), end.minusDays(2 * days)));
    final int dayOfWeek = current.getStart().getDayOfWeek();
    final int lastYearDayOfWeek = start.minusYears(1).getDayOfWeek();
    final int delta = dayOfWeek - lastYearDayOfWeek;

    final Interval lastYear =
        getReportingInterval(start.minusYears(1).plusDays(delta),
            end.minusYears(1).plusDays(delta));
    intervals.put("last", lastYear);
    final Set<String> groupQueues = new HashSet<>(8);
    for (final G group : groups) {
      groupQueues.addAll(Performance.this.groupQueues.get(group.id));
    }
    final JsonList list = new JsonList(count(allRows));
    final Map<String, Integer> totalCalls = new HashMap<>();
    final Map<String, Integer> silentCalls = new HashMap<>();
    final Map<String, Integer> totalVisits = new HashMap<>();
    final Map<String, Currency> totalRevenue = new HashMap<>();
    final Map<String, Integer> totalOpps = new HashMap<>();
    final Query<Opportunity> groupSales = oppsWithGroup(groups).and(Opportunity.isSold);
    Query<Opportunity> oppQuery = Opportunity.withSources(sources)
        .and(Opportunity.withContactTypes(contactTypes));

    if (!callCenters.isEmpty()) {
      oppQuery = oppQuery.and(Opportunity.withCallCenterIn(callCenters));
    }

    final Query<Opportunity> finalOppQuery = oppQuery;
    forEach(allRows.orderBy("name", ASCENDING), r -> {
      meter.setLabel(r.getName());
      final JsonMap row = new JsonMap();
      final Set<String> queues;
      final Set<String> rQueues = rowQueues.get(r.id);
      if (rQueues == null || rQueues.isEmpty()) {
        log.debug("No queues for row %s", r.getName());
        queues = Set.of();
      } else {
        queues = new HashSet<>(rQueues);
        queues.retainAll(groupQueues);
        if (queues.isEmpty()) {
          log.debug("No retained queues for row %s", r.getName());
        }
      }

      list.add(row);
      final Query<Call> callWithQueue =
          Call.isQueue.and(queues.isEmpty() ? Query.none(Call.class) : Call.withQueueIn(queues))
              .and(Call.withDurationGreaterThan(30, SECONDS));
      final Query<Call> callWithQueueAndSource =
          sources.isEmpty() ? callWithQueue : callWithQueue.and(Call.withSourceIn(sources));
      final Query<DailyProductLineVisits> groupVisits = visitsWithGroup(groups)
          .and(visitsWithRow(r));
      final Query<Opportunity> rowSales = groupSales.and(oppsWithRow(r));
      row.$("name", r.getName());
      row.$("id", getId(r));
      final JsonMap calls = new JsonMap();
      row.$("calls", calls);
      final JsonMap silent = new JsonMap();
      row.$("silent", silent);
      final JsonMap visits = new JsonMap();
      row.$("visits", visits);
      final JsonMap revenue = new JsonMap();
      row.$("revenue", revenue);
      final JsonMap opps = new JsonMap();
      row.$("opps", opps);
      addExtra(row, intervals, r, groups);
      for (final Map.Entry<String, Interval> entry : intervals.entrySet()) {
        final String key = entry.getKey();
        final Interval interval = entry.getValue();
        update(totalCalls, calls, key,
            count(callWithQueueAndSource.and(Call.inInterval(interval)).and(Call.isNotSilent)),
            NumberMath.INTEGER);
        update(silentCalls, silent, key,
            count(callWithQueueAndSource.and(Call.inInterval(interval)).and(Call.isSilent)),
            NumberMath.INTEGER);
        update(totalVisits, visits, key,
            $$(groupVisits.and(DailyProductLineVisits.inInterval(interval)), SUM, Integer.class,
                "visits"),
            NumberMath.INTEGER);
        final Query<Opportunity> sub1 = finalOppQuery.and(Opportunity.soldInInterval(interval));
        final Query<Opportunity> and = rowSales.and(sub1);
        update(totalRevenue, revenue, key, $$(and, SUM, Currency.class, "amount"), Currency.MATH);
        update(totalOpps, opps, key, count(and), NumberMath.INTEGER);
      }
      meter.increment();
    });
    final JsonMap totals = new JsonMap();
    addTotals(totals, "calls", totalCalls);
    addTotals(totals, "silent", silentCalls);
    addTotals(totals, "visits", totalVisits);
    addTotals(totals, "revenue", totalRevenue);
    addTotals(totals, "opps", totalOpps);
    final JsonMap json = new JsonMap().$("rows", list)
        .$("total", totals)
        .$("lastAdjustment", delta)
        .$("labels", (JsonList) groups.stream()
            .map(this::getGroupLabel)
            .map(JsonString::new)
            .collect(Collectors.toCollection(JsonList::new)));
    addExtra(json, intervals, groups);
    return json;
  }

  // Begin Servlet methods
  @Override
  public void init(final ServletConfig config)
      throws ServletException {
    super.init(config);
    log.info("Initalizing row queues for %s", getClass().getSimpleName());
    this.rowQueues = new HashMap<>();
    addRowQueues(rowQueues);
    log.info("Initalizing group queues for %s", getClass().getSimpleName());
    this.groupQueues = new HashMap<>();
    addGroupQueues(groupQueues);
  }

  protected abstract void addRowQueues(final Map<Integer, Set<String>> rowQueues);

  protected abstract void addGroupQueues(final Map<Integer, Set<String>> groupQueues);

  protected abstract Query<Opportunity> oppsWithGroup(final Set<G> groups);

  protected abstract Query<DailyProductLineVisits> visitsWithGroup(final Set<G> groups);

  protected abstract Query<DailyProductLineVisits> visitsWithRow(final R r);

  protected abstract Query<Opportunity> oppsWithRow(final R row);

  protected void addExtra(final JsonMap row, final Map<String, Interval> interval, final R r,
      final Set<G> groups) {

  }

  private <N extends Number & Comparable<N>> void update(final Map<String, N> totals,
      final JsonMap map,
      final String key, final N value, final NumberMath<N> calc) {
    totals.put(key, calc.add(totals.getOrDefault(key, calc.zero()), value));
    put(map, key, value);
  }

  private <N extends Number> void addTotals(final JsonMap totals, final String key,
      final Map<String, N> data) {
    final JsonMap map = new JsonMap();
    for (final Map.Entry<String, N> entry : data.entrySet()) {
      put(map, entry.getKey(), entry.getValue());
    }
    totals.put(key, map);
  }

  protected void addExtra(final JsonMap json, final Map<String, Interval> intervals,
      final Set<G> groups) {
  }

  private <N extends Number> void put(final JsonMap map, final String key, final N n) {
    if (n instanceof Currency) {
      map.put(key, n.doubleValue());
    } else if (n instanceof Integer) {
      map.put(key, (Integer) n);
    } else if (n != null) {
      map.put(key, n.doubleValue());
    }
  }
}
