package net.inetalliance.sonar.reporting;

import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.core.NumberMath;
import com.callgrove.obj.*;
import com.callgrove.types.ContactType;
import com.callgrove.types.SaleSource;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import lombok.val;
import net.inetalliance.potion.obj.IdPo;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sql.DateTimeInterval;
import net.inetalliance.types.Currency;
import net.inetalliance.types.Named;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.types.json.JsonString;
import net.inetalliance.util.ProgressMeter;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static com.callgrove.Callgrove.getReportingInterval;
import static java.util.concurrent.TimeUnit.SECONDS;
import static net.inetalliance.potion.Locator.*;
import static net.inetalliance.sql.Aggregate.SUM;
import static net.inetalliance.sql.OrderBy.Direction.ASCENDING;

public abstract class Performance<R extends IdPo & Named, G extends IdPo>
        extends CachedGroupingRangeReport<R, G> {

    private static final Log log = new Log();
    private Map<Integer, Set<String>> groupQueues;
    private Map<Integer, Set<String>> rowQueues;

    Performance(final String groupParam) {
        super(groupParam, "adjust");
    }

    @Override
    public void destroy() {
        super.destroy();
        groupQueues.clear();
        rowQueues.clear();
    }

    @Override
    protected int getJobSize(final Agent loggedIn, final Set<G> groups,
                             final DateTimeInterval intervalStart) {
        return rowQueues.size();
    }

    // End Servlet methods
    protected JsonMap generate(final Set<SaleSource> sources,
                               final Set<ContactType> contactTypes,
                               final Agent loggedIn, final ProgressMeter meter, final LocalDate start,
                               final LocalDate end,
                               final Set<G> groups, Collection<CallCenter> callCenters, final Map<String, String[]> extras) {
        val allRows = allRows(groups, loggedIn, start.atStartOfDay());
        final Map<String, DateTimeInterval> intervals = new TreeMap<>();
        val current = getReportingInterval(start, end);
        intervals.put("current", current);
        val days = current.daysBetween();
        intervals.put("prev", getReportingInterval(start.minusDays(days), end.minusDays(days)));
        intervals.put("prev2", getReportingInterval(start.minusDays(2L * days),
                end.minusDays(2L * days)));
        boolean adjust = Boolean.parseBoolean(getSingleExtra(extras, "adjust", "false"));
        final DateTimeInterval lastYear;
        final int delta;
        if (adjust) {
            final int dayOfWeek = current.start().getDayOfWeek().getValue();
            final int lastYearDayOfWeek = start.minusYears(1).getDayOfWeek().getValue();
            delta = dayOfWeek - lastYearDayOfWeek;
            lastYear =
                    getReportingInterval(start.minusYears(1).plusDays(delta),
                            end.minusYears(1).plusDays(delta));

        } else {
            delta = 0;
            lastYear = new DateTimeInterval(current.start().minusYears(1), current.end().minusYears(1));
        }
        intervals.put("last", lastYear);
        final Set<String> groupQueues = new HashSet<>(8);
        for (val group : groups) {
            groupQueues.addAll(Performance.this.groupQueues.get(group.id));
        }
        val list = new JsonList(count(allRows));
        final Map<String, Integer> totalCalls = new HashMap<>();
        final Map<String, Integer> silentCalls = new HashMap<>();
        final Map<String, Integer> totalVisits = new HashMap<>();
        final Map<String, Currency> totalRevenue = new HashMap<>();
        final Map<String, Integer> totalOpps = new HashMap<>();
        val groupSales = oppsWithGroup(groups).and(Opportunity.isSold);
        var oppQuery = Opportunity.withContactTypes(contactTypes);

        if (!sources.isEmpty()) {
            oppQuery = oppQuery.and(Opportunity.withSources(sources)
                    .and(Opportunity.withContactTypes(contactTypes)));
        }

        if (!callCenters.isEmpty()) {
            oppQuery = oppQuery.and(Opportunity.withCallCenterIn(callCenters));
        }

        val finalOppQuery = oppQuery;
        forEach(allRows.orderBy("name", ASCENDING), r -> {
            meter.setLabel(r.getName());
            val row = new JsonMap();
            final Set<String> queues;
            val rQueues = rowQueues.get(r.id);
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
            val callWithQueue =
                    Call.isQueue.and(queues.isEmpty() ? Query.none(Call.class) : Call.withQueueIn(queues))
                            .and(Call.withDurationGreaterThan(30, SECONDS));
            val callWithQueueAndSource =
                    sources.isEmpty() ? callWithQueue : callWithQueue.and(Call.withSourceIn(sources));
            val groupVisits = visitsWithGroup(groups)
                    .and(visitsWithRow(r));
            val rowSales = groupSales.and(oppsWithRow(r));
            row.$("name", r.getName());
            row.$("id", getId(r));
            val calls = new JsonMap();
            row.$("calls", calls);
            val silent = new JsonMap();
            row.$("silent", silent);
            val visits = new JsonMap();
            row.$("visits", visits);
            val revenue = new JsonMap();
            row.$("revenue", revenue);
            val opps = new JsonMap();
            row.$("opps", opps);
            addExtra(row, intervals, r, groups);
            for (val entry : intervals.entrySet()) {
                val key = entry.getKey();
                val interval = entry.getValue();
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
                val sub1 = finalOppQuery.and(Opportunity.soldInInterval(interval));
                val and = rowSales.and(sub1);
                update(totalRevenue, revenue, key, $$(and, SUM, Currency.class, "amount"), Currency.math);
                update(totalOpps, opps, key, count(and), NumberMath.INTEGER);
            }
            meter.increment();
        });
        val totals = new JsonMap();
        addTotals(totals, "calls", totalCalls);
        addTotals(totals, "silent", silentCalls);
        addTotals(totals, "visits", totalVisits);
        addTotals(totals, "revenue", totalRevenue);
        addTotals(totals, "opps", totalOpps);
        val json = new JsonMap().$("rows", list)
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
        log.info("Initializing row queues for %s", getClass().getSimpleName());
        this.rowQueues = new HashMap<>();
        addRowQueues(rowQueues);
        log.info("Initializing group queues for %s", getClass().getSimpleName());
        this.groupQueues = new HashMap<>();
        addGroupQueues(groupQueues);
    }

    protected abstract void addRowQueues(final Map<Integer, Set<String>> rowQueues);

    protected abstract void addGroupQueues(final Map<Integer, Set<String>> groupQueues);

    protected abstract Query<Opportunity> oppsWithGroup(final Set<G> groups);

    protected abstract Query<DailyProductLineVisits> visitsWithGroup(final Set<G> groups);

    protected abstract Query<DailyProductLineVisits> visitsWithRow(final R r);

    protected abstract Query<Opportunity> oppsWithRow(final R row);

    protected void addExtra(final JsonMap row, final Map<String, DateTimeInterval> interval, final R r,
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
        val map = new JsonMap();
        for (val entry : data.entrySet()) {
            put(map, entry.getKey(), entry.getValue());
        }
        totals.put(key, map);
    }

    protected void addExtra(final JsonMap json, final Map<String, DateTimeInterval> intervals,
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
