package net.inetalliance.sonar.util;

import static java.lang.System.out;
import static java.util.stream.Collectors.toSet;
import static net.inetalliance.potion.Locator.$;
import static net.inetalliance.potion.Locator.$$;
import static net.inetalliance.potion.Locator.count;

import com.callgrove.obj.Call;
import com.callgrove.obj.ProductLine;
import com.callgrove.obj.Site;
import com.callgrove.obj.Site.SiteQueue;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import net.inetalliance.cli.Cli;
import net.inetalliance.log.progress.ProgressMeter;
import net.inetalliance.potion.DbCli;
import net.inetalliance.sonar.api.Startup;
import org.joda.time.DateMidnight;
import org.joda.time.Days;
import org.joda.time.Interval;
import org.joda.time.format.DateTimeFormat;

public class ClosingRatioTrends extends DbCli {

  @Override
  protected void exec() {
    final var sites = Set.of(41, 42).stream().map(id -> $(new Site(id))).collect(toSet());
    final var start = new DateMidnight(2019, 1, 1);
    final var end = new DateMidnight().minusDays(30);
    final var current = start.toMutableDateTime();
    final var data = new HashMap<Site, List<Double>>();
    Startup.initProductLines();
    final var allStairLiftQueues = Startup.productLineQueues.get(6);
    final var siteQueues = new HashMap<Site, Set<String>>();
    final var sl = $(new ProductLine(6));

    sites.forEach(site -> siteQueues.put(site, $$(SiteQueue.withSite(site)).stream()
        .map(q -> q.queue.key)
        .filter(allStairLiftQueues::contains)
        .collect(toSet())));
    out.println("USM Queues");
    out.println(siteQueues.get(new Site(41)));
    out.println("AG Queues");
    out.println(siteQueues.get(new Site(42)));
    var meter = new ProgressMeter(Days.daysBetween(start, end).getDays());

    while (current.isBefore(end)) {
      sites.forEach(site -> {
        var interval = new Interval(current, current.toDateTime().plusDays(30));

        final int sales = count(Call.withQueueIn(siteQueues.get(site))
            .and(Call.isQueue)
            .and(Call.isAnswered)
            .and(Call.isBusinessHours)
            .and(Call.inInterval(interval)));
        /*

        final int sales = count(Opportunity.withProductLine(sl)
            .and(Opportunity.withSite(site))
            .and(Opportunity.soldInInterval(interval)));

         */
        final int calls = count(Call.isBusinessHours.and(Call.withQueueIn(siteQueues.get(site)).and(Call.isQueue).and(Call.inInterval(interval))));

        final double ratio = (double) sales / calls;
        data.computeIfAbsent(site, s -> new LinkedList<>()).add(ratio);
      });
      current.addDays(1);
      meter.increment(DateTimeFormat.shortDate().print(current));
    }

    var usm = data.get(new Site(41)).iterator();
    var ag = data.get(new Site(42)).iterator();
    current.setDate(start);

    while (usm.hasNext() && ag.hasNext()) {
      out.printf("%s,%f,%f%n", DateTimeFormat.shortDate().print(current), usm.next(), ag.next());
      current.addDays(1);
    }


  }

  public static void main(String[] args) {
    Cli.run(new ClosingRatioTrends(), args);
  }
}
