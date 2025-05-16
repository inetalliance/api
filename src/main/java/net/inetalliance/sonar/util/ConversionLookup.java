package net.inetalliance.sonar.util;

import com.ameriglide.phenix.core.DateTimeFormats;
import com.ameriglide.phenix.core.Log;
import com.callgrove.obj.Call;
import com.callgrove.obj.Contact;
import com.callgrove.obj.Opportunity;
import com.callgrove.obj.Site;
import com.callgrove.types.SalesStage;
import lombok.val;
import net.inetalliance.cli.Cli;
import net.inetalliance.potion.DbCli;
import net.inetalliance.sql.DateTimeInterval;
import net.inetalliance.types.Currency;
import net.inetalliance.types.annotations.Parameter;
import net.inetalliance.types.annotations.Required;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.time.Duration;
import java.time.LocalDate;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.callgrove.obj.Call.*;
import static java.time.format.FormatStyle.SHORT;
import static net.inetalliance.potion.Locator.$;
import static net.inetalliance.potion.Locator.forEach;

public class ConversionLookup
        extends DbCli {

    private static final Log log = new Log();
    @Parameter('c')
    @Required
    String callerIds;

    public static void main(final String[] args) {
        Cli.run(new ConversionLookup(), args);
    }

    @Override
    protected void exec()
            throws Throwable {
        val csv = new File(callerIds);
        if (!csv.exists()) {
            log.error("input file %s does not exist", csv.getAbsolutePath());
            System.exit(1);
        }
        if (!csv.isFile()) {
            log.error("input %s is not a file", csv.getAbsolutePath());
            System.exit(2);
        }
        val contacts = new AtomicInteger(0);
        val opps = new AtomicInteger(0);
        val sales = new AtomicInteger(0);
        val n = new AtomicInteger(0);
        val total = new Currency[]{Currency.ZERO};
        val cids = new TreeSet<String>();
        try (val reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(csv)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                cids.add(line);
                n.incrementAndGet();
                forEach(Contact.withPhoneNumber(line), contact -> {
                    contacts.incrementAndGet();
                    forEach(Opportunity.withContact(contact), opportunity -> {
                        opps.incrementAndGet();
                        if (opportunity.getStage() == SalesStage.SOLD) {
                            sales.incrementAndGet();
                            total[0] = total[0].add(opportunity.getAmount());
                        }
                    });
                });
            }
        }
        log.info("n: %d", n.get());
        log.info("opps: %d %.1f%%", opps.get(), 100.0F * opps.get() / n.get());
        log.info("sales: %d %.1f%%", sales.get(), 100.0F * sales.get() / n.get());
        log.info("revenue: $%.1f $%.1f per call ", total[0].doubleValue(),
                total[0].doubleValue() / n.get());
        val talktime = new AtomicLong(0);
        val calls = new AtomicInteger(0);
        forEach(Call.withCallerIdIn(cids), call -> {
            talktime.getAndAdd(call.getTalkTime());
            calls.getAndIncrement();
        });
        log.info("calls: %d", calls.get());
        val dFmt = DateTimeFormats.ofDuration(SHORT);
        log.info("talktime: %s, %s", dFmt.format(Duration.ofMillis(talktime.get())),
                dFmt.format(Duration.ofMillis(talktime.get() / calls.get())));
        val allTalkTime = new AtomicLong(0);
        val allCalls = new AtomicInteger(0);

        forEach(withSite($(new Site(42))).and(isQueue)
                        .and(inInterval(
                                new DateTimeInterval(LocalDate.of(2015, 7, 1),
                                        LocalDate.of(2015, 8, 1)))),
                call -> {
                    allCalls.getAndIncrement();
                    allTalkTime.getAndAdd(call.getTalkTime());

                });
        log.info("all calls: %d", allCalls.get());
        log.info("talktime: %s, %s", dFmt.format(Duration.ofMillis(allTalkTime.get())),
                dFmt.format(Duration.ofMillis(allTalkTime.get() / allCalls.get())));

    }
}
