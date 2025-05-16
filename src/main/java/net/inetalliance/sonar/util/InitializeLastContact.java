package net.inetalliance.sonar.util;

import com.ameriglide.phenix.core.DateTimeFormats;
import com.ameriglide.phenix.core.Log;
import com.callgrove.Callgrove;
import com.callgrove.obj.Call;
import com.callgrove.obj.Opportunity;
import net.inetalliance.cli.Cli;
import net.inetalliance.potion.DbCli;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sql.DateTimeInterval;
import net.inetalliance.util.ProgressMeter;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static java.time.format.FormatStyle.SHORT;
import static net.inetalliance.potion.Locator.forEach;
import static net.inetalliance.sql.Aggregate.MAX;
import static net.inetalliance.sql.Aggregate.MIN;

public class InitializeLastContact extends DbCli {
    @Override
    protected void exec() {
        Callgrove.register();
        var start = Locator.$$(Query.eq(Opportunity.class, "lastContact", null).negate(), MAX, LocalDateTime.class, "created");
        if (start == null) {
            start = Locator.$$(Query.all(Opportunity.class), MIN, LocalDateTime.class, "created");
        }
        var day = start.toLocalDate();
        var meter = new ProgressMeter((int) Duration.between(day, LocalDate.now()).toDays());
        var dateFormat = DateTimeFormats.ofDate(SHORT);
        log.info("Starting from %s", dateFormat.format(start));
        while (day.isBefore(LocalDate.now())) {
            var today = Opportunity.createdInInterval(new DateTimeInterval(day));
            var count = Locator.count(today);
            var dayLabel = dateFormat.format(day);
            var i = new AtomicInteger(0);
            forEach(today, o -> {
                meter.setLabel("%s - %d/%d", dayLabel, i.incrementAndGet(), count);
                var lastContact = Locator.$$(Call.withContact(o.getContact()), MAX, LocalDateTime.class, "created");
                var firstCall = Locator.$$(Call.withContact(o.getContact()), MIN, LocalDateTime.class, "created");
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

    private static final Log log = new Log();
}
