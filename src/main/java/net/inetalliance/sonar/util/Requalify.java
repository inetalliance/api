package net.inetalliance.sonar.util;

import com.callgrove.Callgrove;
import com.callgrove.obj.Opportunity;
import com.callgrove.obj.Site;
import com.callgrove.util.Qualifier;
import net.inetalliance.cli.Cli;
import net.inetalliance.log.Log;
import net.inetalliance.log.progress.ProgressMeter;
import net.inetalliance.potion.DbCli;
import org.joda.time.DateMidnight;
import org.joda.time.Interval;

import static java.lang.System.out;
import static net.inetalliance.potion.Locator.*;

public class Requalify extends DbCli {
    @Override
    protected void exec() {
        Callgrove.register();
        var amg = $(new Site(42));
        var query = Opportunity.isDead.and(
                Opportunity.createdInInterval(
                        new Interval(
                                new DateMidnight(2020, 1, 1),
                                new DateMidnight(2020, 4, 1))))
                .and(Opportunity.withSite(amg));
        var count = count(query);
        var meter = new ProgressMeter(count);
        out.printf("There are %d%n", count);
        forEach(query, o -> {
            try {
                Qualifier.request(o);
            } catch (Exception e) {
                log.error(e);
            }
            meter.increment();
        });


    }

    public static void main(String[] args) {
        Cli.run(new Requalify(), args);
    }

    private static final transient Log log = Log.getInstance(Requalify.class);
}
