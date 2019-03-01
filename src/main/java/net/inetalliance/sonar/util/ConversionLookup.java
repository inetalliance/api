package net.inetalliance.sonar.util;

import com.callgrove.obj.*;
import com.callgrove.types.*;
import net.inetalliance.cli.*;
import net.inetalliance.log.*;
import net.inetalliance.potion.*;
import net.inetalliance.types.Currency;
import net.inetalliance.types.Duration;
import net.inetalliance.types.annotations.*;
import org.joda.time.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import static com.callgrove.obj.Call.*;
import static net.inetalliance.log.Log.*;
import static net.inetalliance.potion.Locator.*;

public class ConversionLookup
		extends DbCli {
	private static final transient Log log = getInstance(ConversionLookup.class);
	@Parameter('c')
	@Required
	String callerIds;

	public static void main(final String[] args) {
		Cli.run(new ConversionLookup(), args);
	}

	@Override
	protected void exec()
			throws Throwable {
		final File csv = new File(callerIds);
		if (!csv.exists()) {
			log.error("input file %s does not exist", csv.getAbsolutePath());
			System.exit(1);
		}
		if (!csv.isFile()) {
			log.error("input %s is not a file", csv.getAbsolutePath());
			System.exit(2);
		}
		final AtomicInteger contacts = new AtomicInteger(0);
		final AtomicInteger opps = new AtomicInteger(0);
		final AtomicInteger sales = new AtomicInteger(0);
		final AtomicInteger n = new AtomicInteger(0);
		final Currency[] total = {Currency.ZERO};
		final Collection<String> cids = new TreeSet<>();
		try (final BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(csv)))) {
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
		log.info("revenue: $%.1f $%.1f per call ", total[0].doubleValue(), total[0].doubleValue() / n.get());
		final AtomicLong talktime = new AtomicLong(0);
		final AtomicInteger calls = new AtomicInteger(0);
		forEach(Call.withCallerIdIn(cids), call -> {
			talktime.getAndAdd(call.getTalkTime());
			calls.getAndIncrement();
		});
		log.info("calls: %d", calls.get());
		log.info("talktime: %s, %s", new Duration(talktime.get()).getShortString(),
		         new Duration(talktime.get() / calls.get()).getShortString());
		final AtomicLong allTalkTime = new AtomicLong(0);
		final AtomicInteger allCalls = new AtomicInteger(0);

		forEach(withSite($(new Site(42))).and(isQueue)
		                                 .and(inInterval(
				                                 new Interval(new DateMidnight(2015, 7, 1), new DateMidnight(2015, 8, 1)))),
		        call -> {
			        allCalls.getAndIncrement();
			        allTalkTime.getAndAdd(call.getTalkTime());

		        });
		log.info("all calls: %d", allCalls.get());
		log.info("talktime: %s, %s", new Duration(allTalkTime.get()).getShortString(),
		         new Duration(allTalkTime.get() / allCalls.get()).getShortString());

	}
}
