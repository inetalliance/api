package net.inetalliance.sonar.reports;

import com.callgrove.obj.Agent;
import com.callgrove.obj.Call;
import com.callgrove.obj.Contact;
import com.callgrove.obj.DailyPerformance;
import com.callgrove.obj.Opportunity;
import com.callgrove.types.Address;
import com.callgrove.types.ContactType;
import com.callgrove.types.SaleSource;
import net.inetalliance.funky.functors.F1;
import net.inetalliance.funky.functors.P1;
import net.inetalliance.funky.functors.math.Stats;
import net.inetalliance.funky.functors.math.StatsCalculator;
import net.inetalliance.funky.functors.types.str.StringFun;
import net.inetalliance.log.Log;
import net.inetalliance.log.progress.ProgressMeter;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.DateMidnight;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.joda.time.Interval;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.callgrove.obj.Call.Q.*;
import static com.callgrove.obj.Opportunity.Q.*;
import static com.callgrove.obj.Site.Q.*;
import static net.inetalliance.potion.Locator.*;
import static net.inetalliance.sql.OrderBy.Direction.*;

public abstract class SalesCycle<R, G>
  extends CachedGroupingRangeReport<R, G> {

  private static final transient Log log = Log.getInstance(SalesCycle.class);

  public SalesCycle(final String groupParam) {
    super(groupParam);
  }

  protected abstract Query<Call> callWithRow(final R row);

  @Override
  protected JsonMap generate(final EnumSet<SaleSource> sources,
                             final EnumSet<ContactType> contactTypes,
                             final Agent loggedIn, final ProgressMeter meter,
                             final DateMidnight start,
                             final DateMidnight end,
                             final Set<G> groups, final Map<String, String> extras) {
    final JsonList rows = new JsonList();
    final Interval interval = getReportingInterval(start, end);
    final Interval callInterval = interval.withStart(interval.getStart().minusDays(60));
    final Query<Opportunity> inInterval = Opportunity.Q.soldInInterval(interval)
      .and(inGroups(groups)).and(phoneSale)
      .and(Opportunity.Q.withSiteIn($$(distributor.negate())));
    forEach(allRows(loggedIn), new P1<R>() {
      @Override
      public void $(final R r) {
        meter.increment();
        meter.setLabel(getLabel(r));
        final StatsCalculator<Long> created = StatsCalculator.$(Long.class);
        final StatsCalculator<Long> firstContact = StatsCalculator.$(Long.class);
        final AtomicInteger firstCallCloses = new AtomicInteger(0);
        final Query<Call> callInInterval = inInterval(callInterval);
        final int[] buckets = new int[4]; // 0 = <1, 1 = <2, 2 =<5, 3=>=5
        forEach(inInterval.and(withRow(r)),
          new P1<Opportunity>() {
            @Override
            public void $(final Opportunity opp) {
              if (opp.getAmount().greaterThanOrEqual(opp.getProductLine().getLowestReasonableAmount())) {
                created.$(new Duration(opp.getCreated(), opp.getSaleDate()).getMillis());
                final Contact contact = opp.getContact();
                final Collection<String> numbers = getNumbers(contact);
                if (!numbers.isEmpty()) {
                  final Query<Call> forOpp = callInInterval
                    .and(Call.Q.withCallerIdIn(numbers))
                    .and(Call.Q.withSite(opp.getSite()));
                  final Call firstCall = $1(forOpp.and(inInterval(callInterval))
                    .orderBy("created", ASCENDING));
                  final DateTime created = firstCall == null ? null : firstCall.getCreated();
                  if (firstCall != null && created.isBefore(opp.getSaleDate())) {
                    final Duration closingDuration = new Duration(created, opp.getSaleDate());
                    buckets[getBucket(closingDuration.getStandardDays())]++;
                    firstContact.$(closingDuration.getMillis());
                    if (count(forOpp.and(inInterval(new Interval(created.minusDays(30), opp.getSaleDate())))) == 1) {
                      firstCallCloses.incrementAndGet();
                    }

                  }
                }
              }
            }
          }
               );

        final DailyPerformance total = new DailyPerformance();

        forEach(DailyPerformance.Q.inInterval(interval).and(performanceForGroups(groups)).and(performanceWithRow(r)),
          new P1<DailyPerformance>() {
            @Override
            public void $(final DailyPerformance performance) {
              total.add(performance);
            }
          }
               );
        rows.add(new JsonMap()
          .$("label", getLabel(r))
          .$("id", getId(r))
          .$("n", total.getCloses())
          .$("calls", new JsonMap()
            .$("in", total.getQueueCalls())
            .$("out", total.getOutboundCalls()))
          .$("firstCallCloses", firstCallCloses.get())
          .$("created", toMap(created.getStats()))
          .$("firstContact", toMap(firstContact.getStats()))
          .$("histogram", new JsonMap()
            .$("0", buckets[0])
            .$("1", buckets[1])
            .$("2", buckets[2])
            .$("5", buckets[3])));
      }
    });
    return new JsonMap()
      .$("rows", rows)
      .$("labels", JsonList.$(new F1<G, String>() {
        @Override
        public String $(final G g) {
          return getGroupLabel(g);
        }
      }.map(groups)));
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

  protected abstract String getLabel(final R row);

  private Collection<String> getNumbers(final Contact contact) {
    final Set<String> numbers = new HashSet<>(1);
    final Address shipping = contact.getShipping();
    if (shipping != null && !StringFun.empty.$(shipping.getPhone())) {
      numbers.add(shipping.getPhone());
    }
    final Address billing = contact.getBilling();
    if (billing != null && !StringFun.empty.$(billing.getPhone())) {
      numbers.add(billing.getPhone());
    }
    if (!StringFun.empty.$(contact.getMobilePhone())) {
      numbers.add(contact.getMobilePhone());
    }

    return numbers;
  }

  protected abstract Query<DailyPerformance> performanceWithRow(final R row);

  protected abstract Query<Opportunity> withRow(final R row);

  @Override
  protected int getJobSize(final Agent loggedIn, final int numGroups) {
    return count(allRows(loggedIn));
  }

  protected abstract Query<Opportunity> inGroups(final Set<G> groups);

  protected abstract Query<DailyPerformance> performanceForGroups(final Set<G> groups);

  private JsonMap toMap(final Stats<Long> stats) {
    return new JsonMap()
      .$("n", stats.n)
      .$("average", stats.mean())
      .$("stdDev",
        Double.isNaN(stats.stdDeviation) || Double.isInfinite(stats.stdDeviation) ? null : stats.stdDeviation)
      .$("min", stats.min)
      .$("max", stats.max);
  }
}
