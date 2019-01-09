package net.inetalliance.sonar.api.reports;

import com.callgrove.obj.Agent;
import com.callgrove.obj.Call;
import com.callgrove.obj.DailyProductLineVisits;
import com.callgrove.obj.Opportunity;
import com.callgrove.types.ContactType;
import com.callgrove.types.SaleSource;
import net.inetalliance.funky.math.NumberMath;
import net.inetalliance.log.Log;
import net.inetalliance.log.progress.ProgressMeter;
import net.inetalliance.potion.obj.IdPo;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sonar.reports.CachedGroupingRangeReport;
import net.inetalliance.types.Currency;
import net.inetalliance.types.Named;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.types.json.JsonString;
import org.joda.time.DateMidnight;
import org.joda.time.Interval;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.concurrent.TimeUnit.SECONDS;
import static net.inetalliance.potion.Locator.*;
import static net.inetalliance.sql.Aggregate.SUM;
import static net.inetalliance.sql.OrderBy.Direction.ASCENDING;
import static org.joda.time.DateTimeConstants.MILLIS_PER_DAY;

public abstract class Performance<R extends IdPo & Named, G extends IdPo>
	extends CachedGroupingRangeReport<R, G> {
	private Map<Integer, Set<String>> groupQueues;

	private static final transient Log log = Log.getInstance(Performance.class);
	private Map<Integer, Set<String>> rowQueues;

	protected Performance(final String groupParam) {
		super(groupParam);
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

	protected abstract void addGroupQueues(final Map<Integer, Set<String>> groupQueues);

	protected abstract void addRowQueues(final Map<Integer, Set<String>> rowQueues);

	@Override
	public void destroy() {
		super.destroy();
		groupQueues.clear();
		rowQueues.clear();
	}

	// End Servlet methods
	protected JsonMap generate(final EnumSet<SaleSource> sources,
	                           final EnumSet<ContactType> contactTypes,
	                           final Agent loggedIn,
	                           final ProgressMeter meter,
	                           final DateMidnight start,
	                           final DateMidnight end,
	                           final Set<G> groups, final Map<String, String> extras) {
		final Query<R> allRows = allRows(loggedIn);
		final Map<String, Interval> intervals = new TreeMap<>();
		final Interval current = getReportingInterval(start, end);
		intervals.put("current", current);
		final int days = (int) (current.toDurationMillis() / MILLIS_PER_DAY);
		intervals.put("prev", getReportingInterval(start.minusDays(days), end.minusDays(days)));
		intervals.put("prev2",
			getReportingInterval(start.minusDays(2 * days), end.minusDays(2 * days)));
		final int dayOfWeek = current.getStart().getDayOfWeek();
		final int lastYearDayOfWeek = start.minusYears(1).getDayOfWeek();
		final int delta = dayOfWeek - lastYearDayOfWeek;

		final Interval lastYear = getReportingInterval(start.minusYears(1).plusDays(delta),
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
		final Query<Opportunity> groupSales = oppsWithGroup(groups).and(Opportunity.isSold);
		Query<Opportunity> oppQuery = Opportunity.withSources(sources)
			.and(Opportunity.withContactTypes(contactTypes));

		forEach(allRows.orderBy("name", ASCENDING), r -> {
			meter.setLabel(r.getName());
			final JsonMap row = new JsonMap();
			final Set<String> queues = new HashSet<>(rowQueues.get(r.id));
			queues.retainAll(groupQueues);
			if (queues.isEmpty()) {
				log.debug("No queues for row %s", r.getName());
			}
			list.add(row);
			final Query<Call> callWithQueue =
				Call.isQueue
					.and(queues.isEmpty() ? Query.none(Call.class) : Call.withQueueIn(queues))
					.and(Call.withDurationGreaterThan(30, SECONDS));
			final Query<Call> callWithQueueAndSource = sources.isEmpty() ? callWithQueue :
				callWithQueue.and(Call.withSourceIn(sources));
			final Query<DailyProductLineVisits> groupVisits =
				visitsWithGroup(groups).and(visitsWithRow(r));
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
			addExtra(row, intervals, r, groups);
			for (final Map.Entry<String, Interval> entry : intervals.entrySet()) {
				final String key = entry.getKey();
				final Interval interval = entry.getValue();
				update(totalCalls, calls, key,
					count(callWithQueueAndSource.and(Call.inInterval(interval)).and(Call.isNotSilent)), NumberMath.INTEGER);
				update(silentCalls, silent, key,
					count(callWithQueueAndSource.and(Call.inInterval(interval)).and(Call.isSilent)), NumberMath.INTEGER);
				update(totalVisits, visits, key, $$(groupVisits.and(
					DailyProductLineVisits.inInterval(interval)), SUM, Integer.class, "visits"), NumberMath.INTEGER);
				update(totalRevenue,
					revenue,
					key,
					$$(rowSales.and(
						oppQuery.and(Opportunity.soldInInterval(interval))), SUM, Currency.class, "amount"),
					Currency.MATH);
			}
			meter.increment();
		});
		final JsonMap totals = new JsonMap();
		addTotals(totals, "calls", totalCalls);
		addTotals(totals, "silent", silentCalls);
		addTotals(totals, "visits", totalVisits);
		addTotals(totals, "revenue", totalRevenue);
		final JsonMap json = new JsonMap()
			.$("rows", list)
			.$("total", totals)
			.$("lastAdjustment", delta)
			.$("labels", (JsonList) groups.stream().map(this::getGroupLabel).map(JsonString::new).collect(
				Collectors.toCollection(JsonList::new)));
		addExtra(json, intervals, groups);
		return json;
	}

	protected void addExtra(final JsonMap row, final Map<String, Interval> interval, final R r,
	                        final Set<G> groups) {

	}

	protected void addExtra(final JsonMap json, final Map<String, Interval> intervals,
	                        final Set<G> groups) {
	}

	protected <N extends Number>
	void addTotals(final JsonMap totals, final String key, final Map<String, N> data) {
		final JsonMap map = new JsonMap();
		for (final Map.Entry<String, N> entry : data.entrySet()) {
			put(map, entry.getKey(), entry.getValue());
		}
		totals.put(key, map);
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


	protected abstract Query<Opportunity> oppsWithRow(final R row);

	private <N extends Number & Comparable<N>> void update(final Map<String, N> totals, final JsonMap map,
	                                                       final String key, final N value,
	                                                       final NumberMath<N> calc) {
		totals.put(key, calc.add(totals.getOrDefault(key, calc.zero()), value));
		put(map, key, value);
	}

	protected abstract Query<DailyProductLineVisits> visitsWithRow(final R r);

	@Override
	protected int getJobSize(final Agent loggedIn, final int numGroups) {
		return rowQueues.size();
	}

	protected abstract Query<Opportunity> oppsWithGroup(final Set<G> groups);

	protected abstract Query<DailyProductLineVisits> visitsWithGroup(final Set<G> groups);
}
