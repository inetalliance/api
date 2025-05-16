package net.inetalliance.sonar.util;

import com.ameriglide.phenix.core.DateTimeFormats;
import com.callgrove.obj.Call;
import com.callgrove.obj.Site;
import com.callgrove.obj.Site.SiteQueue;
import lombok.val;
import net.inetalliance.cli.Cli;
import net.inetalliance.potion.DbCli;
import net.inetalliance.sonar.api.Startup;
import net.inetalliance.sql.DateTimeInterval;
import net.inetalliance.util.ProgressMeter;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import static java.lang.System.out;
import static java.time.format.FormatStyle.SHORT;
import static java.util.stream.Collectors.toSet;
import static net.inetalliance.potion.Locator.*;

public class ClosingRatioTrends extends DbCli {

    @Override
    protected void exec() {
        val sites = Set.of(41, 42).stream().map(id -> $(new Site(id))).collect(toSet());
        val start = LocalDate.of(2019, 1, 1);
        val end = LocalDate.now().minusDays(30);
        var current = start;
        val data = new HashMap<Site, List<Double>>();
        Startup.initProductLines();
        val allStairLiftQueues = Startup.productLineQueues.get(6);
        val siteQueues = new HashMap<Site, Set<String>>();

        sites.forEach(site -> siteQueues.put(site, $$(SiteQueue.withSite(site)).stream()
                .map(q -> q.queue.key)
                .filter(allStairLiftQueues::contains)
                .collect(toSet())));
        out.println("USM Queues");
        out.println(siteQueues.get(new Site(41)));
        out.println("AG Queues");
        out.println(siteQueues.get(new Site(42)));
        var meter = new ProgressMeter((int) ChronoUnit.DAYS.between(start, end));

        while (current.isBefore(end)) {
            for (Site site : sites) {
                var interval = new DateTimeInterval(current, current.plusDays(30));
                val sales = count(Call.withQueueIn(siteQueues.get(site))
                        .and(Call.isQueue)
                        .and(Call.isAnswered)
                        .and(Call.isBusinessHours)
                        .and(Call.inInterval(interval)));
        /* todo: figure out what this is

        final int sales = count(Opportunity.withProductLine(sl)
            .and(Opportunity.withSite(site))
            .and(Opportunity.soldInInterval(interval)));

         */
                val calls = count(Call.isBusinessHours.and(Call.withQueueIn(siteQueues.get(site)).and(Call.isQueue).and(Call.inInterval(interval))));

                val ratio = (double) sales / calls;
                data.computeIfAbsent(site, s -> new LinkedList<>()).add(ratio);
            }
            current = current.plusDays(1);
            meter.increment(DateTimeFormats.ofDate(SHORT).format(current));
        }

        var usm = data.get(new Site(41)).iterator();
        var ag = data.get(new Site(42)).iterator();
        current = start;

        while (usm.hasNext() && ag.hasNext()) {
            out.printf("%s,%f,%f%n", DateTimeFormats.ofDate(SHORT).format(current), usm.next(), ag.next());
            current = current.plusDays(1);
        }


    }

    public static void main(String[] args) {
        Cli.run(new ClosingRatioTrends(), args);
    }
}
