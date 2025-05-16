package net.inetalliance.sonar.reporting;

import com.ameriglide.phenix.core.Calculator;
import com.ameriglide.phenix.core.Stats;
import com.callgrove.obj.*;
import com.callgrove.types.ContactType;
import com.callgrove.types.SaleSource;
import lombok.val;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sql.DateTimeInterval;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.types.json.JsonString;
import net.inetalliance.util.ProgressMeter;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.ameriglide.phenix.core.Strings.isNotEmpty;
import static com.callgrove.Callgrove.getReportingInterval;
import static com.callgrove.obj.Call.inInterval;
import static com.callgrove.obj.Opportunity.isPhoneSale;
import static com.callgrove.obj.Site.isDistributor;
import static net.inetalliance.potion.Locator.*;
import static net.inetalliance.sql.OrderBy.Direction.ASCENDING;

public abstract class SalesCycle<R, G>
        extends CachedGroupingRangeReport<R, G> {

    protected SalesCycle(final String groupParam) {
        super(groupParam);
    }

    protected abstract Query<Call> callWithRow(final R row);

    @Override
    protected int getJobSize(final Agent loggedIn, final Set<G> groups,
                             final DateTimeInterval interval) {
        return count(allRows(groups, loggedIn, interval.start()));
    }

    @Override
    protected JsonMap generate(final Set<SaleSource> sources,
                               final Set<ContactType> contactTypes,
                               final Agent loggedIn, final ProgressMeter meter, final LocalDate start,
                               final LocalDate end,
                               final Set<G> groups, Collection<CallCenter> callCenters, final Map<String, String[]> extras) {
        val rows = new JsonList();
        val interval = getReportingInterval(start, end);
        val callInterval = interval.withStart(interval.start().minusDays(60));
        val inInterval = Opportunity.soldInInterval(interval)
                .and(inGroups(groups))
                .and(isPhoneSale)
                .and(Opportunity.withSiteIn($$(isDistributor.negate())));
        forEach(allRows(groups, loggedIn, interval.start()), r -> {
            meter.increment();
            meter.setLabel(getLabel(r));
            val created = Calculator.newLong();
            val firstContact = Calculator.newLong();
            val firstCallCloses = new AtomicInteger(0);
            val callInInterval = inInterval(callInterval);
            val buckets = new int[4]; // 0 = <1, 1 = <2, 2 =<5, 3=>=5
            forEach(inInterval.and(withRow(r)), opp -> {
                if (opp.getAmount().greaterThanOrEqual(opp.getProductLine().getLowestReasonableAmount())) {
                    created.accept(TimeUnit.SECONDS.toMillis(Duration.between(opp.getCreated(), opp.getSaleDate()).getSeconds()));
                    val contact = opp.getContact();
                    val numbers = getNumbers(contact);
                    if (!numbers.isEmpty()) {
                        val forOpp =
                                callInInterval.and(Call.withCallerIdIn(numbers)).and(Call.withSite(opp.getSite()));
                        val firstCall = $1(
                                forOpp.and(inInterval(callInterval)).orderBy("created", ASCENDING));
                        val created1 = firstCall == null ? null : firstCall.getCreated();
                        if (firstCall != null && created1.isBefore(opp.getSaleDate())) {
                            val closingDuration = Duration.between(created1, opp.getSaleDate());
                            buckets[getBucket(TimeUnit.SECONDS.toDays(closingDuration.getSeconds()))]++;
                            firstContact.accept(TimeUnit.SECONDS.toMillis(closingDuration.getSeconds()));
                            if (count(forOpp.and(inInterval(new DateTimeInterval(created1.minusDays(30), opp.getSaleDate())))) == 1) {
                                firstCallCloses.incrementAndGet();
                            }

                        }
                    }
                }
            });

            val total = new DailyPerformance();

            forEach(DailyPerformance.inInterval(interval.toDateInterval()).and(performanceForGroups(groups))
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
        val numbers = new HashSet<String>(1);
        val shipping = contact.getShipping();
        if (shipping != null && isNotEmpty(shipping.getPhone())) {
            numbers.add(shipping.getPhone());
        }
        val billing = contact.getBilling();
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
