package net.inetalliance.sonar.util;

import com.ameriglide.phenix.core.Iterables;
import com.callgrove.Callgrove;
import com.callgrove.obj.*;
import lombok.val;
import net.inetalliance.cli.Cli;
import net.inetalliance.potion.DbCli;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sql.Aggregate;
import net.inetalliance.sql.DateTimeInterval;
import net.inetalliance.types.annotations.Parameter;
import net.inetalliance.types.annotations.Required;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.callgrove.obj.Opportunity.withProductLine;
import static com.callgrove.obj.Opportunity.withSite;
import static java.lang.System.err;
import static java.lang.System.out;
import static java.util.stream.Collectors.toSet;

public class CycleAnalyzer extends DbCli {


    @Parameter('p')
    @Required
    Integer productLineId;
    @Parameter('s')
    @Required
    Integer siteId;

    @Override
    protected void exec() {

        Callgrove.register();

        val productLine = Locator.$(new ProductLine(productLineId));

        if (productLine == null) {
            throw new IllegalArgumentException("Could not find product line with id " + productLineId);
        }
        val site = Locator.$(new Site(siteId));
        if (site == null) {
            throw new IllegalArgumentException("Could not find site with id " + siteId);
        }

        final Query<Opportunity> q = withSite(site)
                .and(withProductLine(productLine))
                .and(Opportunity.isSold)
                .orderBy("created");

        err.println("Site is " + site.getName());
        err.println("Product line is " + productLine.getName());
        val count = Locator.count(q);
        err.println("Processing " + count + " opps");
        var broken = new AtomicInteger(0);

        Locator.forEach(q, o -> {
            var interval = getInterval(o);
            if (interval == null) {
                broken.getAndIncrement();
            } else {
                out.printf("%d\t%s\t%s\t%d%n", o.id, interval.start(), interval.end(),
                        TimeUnit.SECONDS.toDays(interval.toDuration().getSeconds()));
            }
        });
        err.println("There were " + broken.get() + " broken opps");


    }

    private static DateTimeInterval getInterval(final Opportunity opp) {

        err.println("Opp " + opp.id);
        if (opp.getContact().getAllPhoneNumbers().findFirst().isPresent()) {
            val contacts = Locator.$$(Contact.withPhoneNumberIn(opp.getContact()));
            err.println("# contacts " + contacts.size());
            if (contacts.size() > 5) {
                return null;
            }

            var allNumbers = contacts.stream()
                    .map(Contact::getAllPhoneNumbers)
                    .map(s -> s.collect(toSet()))
                    .flatMap(Iterables::stream)
                    .limit(5)
                    .collect(toSet());
            err.println("all phone numbers " + allNumbers);

            val opps = contacts.stream()
                    .map(c -> Locator.$$(Opportunity.isSold.and(Opportunity.withContact(c))))
                    .flatMap(Iterables::stream).collect(toSet());

            err.println("# opps " + opps.size());

            var earliestCallDate = opps.stream()
                    .map(Opportunity::getSaleDate)
                    .map(d -> d.plusWeeks(2))
                    .filter(d -> d.isBefore(opp.getSaleDate()))
                    .max(LocalDateTime::compareTo)
                    .orElse(opp.getCreated().minusMonths(2));
            if (earliestCallDate.isAfter(opp.getCreated())) {
                earliestCallDate = opp.getCreated().minusMonths(2);
            }

            err.println("sale date " + opp.getSaleDate());
            err.println("creation date " + opp.getCreated());
            err.println("earliest acceptable call date " + earliestCallDate);

            val earliestCall = Locator.$$(Call.withCallerIdIn(allNumbers)
                            .and(Call.inInterval(new DateTimeInterval(earliestCallDate, opp.getCreated()))),
                    Aggregate.MIN, LocalDateTime.class, "created");
            err.println("earliest call " + earliestCall);

            val firstContact = earliestCall == null ? opp.getCreated() : earliestCall;
            if (opp.getSaleDate().isBefore(firstContact)) {
                return null;
            }
            return new DateTimeInterval(firstContact, opp.getSaleDate());
        }
        return null;

    }

    public static void main(String[] args) {
        Cli.run(new CycleAnalyzer(), args);
    }
}
