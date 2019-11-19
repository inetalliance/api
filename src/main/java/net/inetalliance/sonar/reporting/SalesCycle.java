package net.inetalliance.sonar.reporting;

import static com.callgrove.Callgrove.getReportingInterval;
import static com.callgrove.obj.Call.inInterval;
import static com.callgrove.obj.Opportunity.isPhoneSale;
import static com.callgrove.obj.Site.isDistributor;
import static net.inetalliance.funky.StringFun.isNotEmpty;
import static net.inetalliance.potion.Locator.$$;
import static net.inetalliance.potion.Locator.$1;
import static net.inetalliance.potion.Locator.count;
import static net.inetalliance.potion.Locator.forEach;
import static net.inetalliance.sql.OrderBy.Direction.ASCENDING;

import com.callgrove.obj.Agent;
import com.callgrove.obj.Call;
import com.callgrove.obj.CallCenter;
import com.callgrove.obj.Contact;
import com.callgrove.obj.DailyPerformance;
import com.callgrove.obj.Opportunity;
import com.callgrove.types.Address;
import com.callgrove.types.ContactType;
import com.callgrove.types.SaleSource;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import net.inetalliance.funky.math.Calculator;
import net.inetalliance.funky.math.Stats;
import net.inetalliance.log.progress.ProgressMeter;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.types.json.JsonString;
import org.joda.time.DateMidnight;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.joda.time.Interval;

public abstract class SalesCycle<R, G>
    extends CachedGroupingRangeReport<R, G> {

  protected SalesCycle(final String groupParam) {
    super(groupParam);
  }

  protected abstract Query<Call> callWithRow(final R row);

  @Override
  protected int getJobSize(final Agent loggedIn, final Set<G> groups,
      final Interval interval) {
    return count(allRows(groups, loggedIn, interval.getStart()));
  }

  @Override
  protected JsonMap generate(final EnumSet<SaleSource> sources,
      final EnumSet<ContactType> contactTypes,
      final Agent loggedIn, final ProgressMeter meter, final DateMidnight start,
      final DateMidnight end,
      final Set<G> groups, Collection<CallCenter> callCenters, final Map<String, String[]> extras) {
    final JsonList rows = new JsonList();
    final Interval interval = getReportingInterval(start, end);
    final Interval callInterval = interval.withStart(interval.getStart().minusDays(60));
    final Query<Opportunity> inInterval = Opportunity.soldInInterval(interval)
        .and(inGroups(groups))
        .and(isPhoneSale)
        .and(Opportunity.withSiteIn($$(isDistributor.negate())));
    forEach(allRows(groups, loggedIn, interval.getStart()), r -> {
      meter.increment();
      meter.setLabel(getLabel(r));
      final Calculator<Long> created = Calculator.newLong();
      final Calculator<Long> firstContact = Calculator.newLong();
      final AtomicInteger firstCallCloses = new AtomicInteger(0);
      final Query<Call> callInInterval = inInterval(callInterval);
      final int[] buckets = new int[4]; // 0 = <1, 1 = <2, 2 =<5, 3=>=5
      forEach(inInterval.and(withRow(r)), opp -> {
        if (opp.getAmount().greaterThanOrEqual(opp.getProductLine().getLowestReasonableAmount())) {
          created.accept(new Duration(opp.getCreated(), opp.getSaleDate()).getMillis());
          final Contact contact = opp.getContact();
          final Collection<String> numbers = getNumbers(contact);
          if (!numbers.isEmpty()) {
            final Query<Call> forOpp =
                callInInterval.and(Call.withCallerIdIn(numbers)).and(Call.withSite(opp.getSite()));
            final Call firstCall = $1(
                forOpp.and(inInterval(callInterval)).orderBy("created", ASCENDING));
            final DateTime created1 = firstCall == null ? null : firstCall.getCreated();
            if (firstCall != null && created1.isBefore(opp.getSaleDate())) {
              final Duration closingDuration = new Duration(created1, opp.getSaleDate());
              buckets[getBucket(closingDuration.getStandardDays())]++;
              firstContact.accept(closingDuration.getMillis());
              if (count(
                  forOpp.and(inInterval(new Interval(created1.minusDays(30), opp.getSaleDate()))))
                  == 1) {
                firstCallCloses.incrementAndGet();
              }

            }
          }
        }
      });

      final DailyPerformance total = new DailyPerformance();

      forEach(DailyPerformance.inInterval(interval).and(performanceForGroups(groups))
              .and(performanceWithRow(r)),
          total::add);
      rows.add(new JsonMap().$("label", getLabel(r))
          .$("id", getId(r))
          .$("n", total.getCloses())
          .$("calls", new JsonMap().$("in", total.getQueueCalls()).$("out",
              total.getOutboundCalls()))
          .$("firstCallCloses", firstCallCloses.get())
          .$("created", toMap(created.getStats()))
          .$("firstContact", toMap(firstContact.getStats()))
          .$("histogram", new JsonMap().$("0", buckets[0])
              .$("1", buckets[1])
              .$("2", buckets[2])
              .$("5", buckets[3])));
    });
    return new JsonMap().$("rows", rows)
        .$("labels", (JsonList) groups.stream()
            .map(this::getGroupLabel)
            .map(JsonString::new)
            .collect(Collectors.toCollection(JsonList::new)));
  }

  protected abstract Query<Opportunity> inGroups(final Set<G> groups);

  protected abstract String getLabel(final R row);

  protected abstract Query<Opportunity> withRow(final R row);

  private Collection<String> getNumbers(final Contact contact) {
    final Set<String> numbers = new HashSet<>(1);
    final Address shipping = contact.getShipping();
    if (shipping != null && isNotEmpty(shipping.getPhone())) {
      numbers.add(shipping.getPhone());
    }
    final Address billing = contact.getBilling();
    if (billing != null && isNotEmpty(billing.getPhone())) {
      numbers.add(billing.getPhone());
    }
    if (isNotEmpty(contact.getMobilePhone())) {
      numbers.add(contact.getMobilePhone());
    }

    return numbers;
  }

  private int getBucket(final long days) {
    if (days < 1) {
      return 0;
    }
    if (days < 2) {
      return 1;
    }
    if (days < 5) {
      return 2;
    }
    return 3;
  }

  protected abstract Query<DailyPerformance> performanceForGroups(final Set<G> groups);

  protected abstract Query<DailyPerformance> performanceWithRow(final R row);

  private JsonMap toMap(final Stats<Long> stats) {
    return new JsonMap().$("n", stats.n)
        .$("average", stats.mean())
        .$("stdDev", Double.isNaN(stats.stdDeviation) || Double.isInfinite(stats.stdDeviation)
            ? null
            : stats.stdDeviation)
        .$("min", stats.min)
        .$("max", stats.max);
  }
}
