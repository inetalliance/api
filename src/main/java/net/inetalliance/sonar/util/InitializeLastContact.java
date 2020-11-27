package net.inetalliance.sonar.util;

import com.callgrove.Callgrove;
import com.callgrove.obj.Call;
import com.callgrove.obj.Opportunity;
import net.inetalliance.cli.Cli;
import net.inetalliance.log.Log;
import net.inetalliance.log.progress.ProgressMeter;
import net.inetalliance.potion.DbCli;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.query.Query;
import org.joda.time.DateMidnight;
import org.joda.time.DateTime;
import org.joda.time.Interval;
import org.joda.time.format.DateTimeFormat;

import java.util.concurrent.atomic.AtomicInteger;

import static net.inetalliance.potion.Locator.forEach;
import static net.inetalliance.sql.Aggregate.MAX;
import static net.inetalliance.sql.Aggregate.MIN;

public class InitializeLastContact extends DbCli {
    @Override
    protected void exec() {
        Callgrove.register();
        var start = Locator.$$(Query.eq(Opportunity.class,"lastContact",null).negate(), MAX, DateTime.class, "created");
        if (start == null) {
            start = Locator.$$(Query.all(Opportunity.class), MIN, DateTime.class, "created");
        }
        var day = start.toDateMidnight();
        var meter = new ProgressMeter((int) new Interval(day, new DateMidnight()).toDuration().getStandardDays());
        var dateFormat = DateTimeFormat.shortDate();
        log.info("Starting from %s", dateFormat.print(start));
        while (day.isBeforeNow()) {
            var today = Opportunity.createdInInterval(day.toInterval());
            var count = Locator.count(today);
            String dayLabel = dateFormat.print(day);
            var i = new AtomicInteger(0);
            forEach(today, o -> {
                meter.setLabel("%s - %d/%d", dayLabel, i.incrementAndGet(),count);
                var lastContact = Locator.$$(Call.withContact(o.getContact()), MAX, DateTime.class, "created");
                var firstCall = Locator.$$(Call.withContact(o.getContact()), MIN, DateTime.class, "created");
                if (lastContact != null || firstCall != null) {
                    Locator.update(o, "InitializeLastContact", copy -> {
                        copy.setLastContact(lastContact);
                        copy.setFirstCall(firstCall);
                    });
                }
            });
            meter.increment();
            day = day.plusDays(1);
        }

    }

    public static void main(String[] args) {
        Cli.run(new InitializeLastContact(), args);
    }

    private static final transient Log log = Log.getInstance(InitializeLastContact.class);
}
