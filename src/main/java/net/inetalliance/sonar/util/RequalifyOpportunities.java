package net.inetalliance.sonar.util;

import com.callgrove.obj.Site;
import net.inetalliance.cli.Cli;
import net.inetalliance.log.progress.ProgressMeter;
import net.inetalliance.potion.DbCli;
import org.joda.time.DateMidnight;

import static com.callgrove.Callgrove.register;
import static com.callgrove.obj.Opportunity.*;
import static net.inetalliance.potion.Locator.*;

public class RequalifyOpportunities extends DbCli {
    @Override
    protected void exec() {
       register();
       var ag = $(new Site(42));
       var qSold = withSite(ag)
               .and(soldAfter(new DateMidnight().minusDays(2)));
        var qCreated = withSite(ag)
                .and(createdAfter(new DateMidnight().minusDays(2)));

        var nSold = count(qSold);
        System.out.printf("Updating %d sold opps%n",nSold);
        var meter = new ProgressMeter(nSold);
        forEach(qSold, o-> {
            meter.increment();
            o.clearCallRailQualified();
            o.onUpdate(null);
        });
        var nCreated = count(qCreated);
        System.out.printf("Updating %d created opps%n", nCreated);
        meter.reset(nCreated);
        forEach(qCreated, o-> {
            meter.increment();
            o.clearCallRailQualified();
            o.onUpdate(null);
        });
    }

    public static void main(String[] args) {
        Cli.run(new RequalifyOpportunities(),args);
    }
}
