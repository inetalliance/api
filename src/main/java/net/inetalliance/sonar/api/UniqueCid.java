package net.inetalliance.sonar.api;

import com.callgrove.obj.Opportunity;
import com.callgrove.obj.Site;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.potion.Locator;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.DateMidnight;
import org.joda.time.DateTime;
import org.joda.time.Interval;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.regex.Pattern;

import static com.callgrove.Callgrove.*;
import static com.callgrove.obj.Call.*;
import static net.inetalliance.funky.StringFun.*;
import static net.inetalliance.potion.Locator.*;
import static net.inetalliance.sql.Aggregate.*;

@WebServlet(urlPatterns = {"/api/uniqueCid"})
public class UniqueCid
	extends AngularServlet {

	public static final Pattern pattern = Pattern.compile("/api/uniqueCid");

	private static class Info {
		private int old;
		private int first;
		private int anon;
		private int opps;

		JsonMap toJson() {
			return new JsonMap()
				.$("old", old)
				.$("first", first)
				.$("anon", anon)
				.$("opps", opps);
		}

		void add(Info info) {
			old += info.old;
			first += info.first;
			anon += info.anon;
			opps += info.opps;
		}
	}

	@Override
	protected void get(final HttpServletRequest request, final HttpServletResponse response)
		throws Exception {
		final Interval interval = getInterval(request);
		final Site atc = new Site(10117);
		final Map<String, DateTime> firstCall = $$(withSite(atc).and(isQueue), MIN, String.class,
			"callerid_number", DateTime.class, "created");
		final DateMidnight start = interval.getStart().toDateMidnight();
		final DateMidnight end = interval.getEnd().toDateMidnight();
		DateMidnight i = start;
		final JsonMap json = new JsonMap();
		final Info total = new Info();
		while (i.isBefore(end)) {
			final Info info = classify(i.toInterval(), atc, firstCall);
			total.add(info);
			json.put(Json.dateFormat.print(i), info.toJson());
			i = i.plusDays(1);
		}
		respond(response, new JsonMap().$("total", total.toJson()).$("days", json));
	}

	private Info classify(final Interval interval, final Site site, final Map<String, DateTime> firstCall) {
		final Info i = new Info();
		i.opps = Locator.count(Opportunity.createdInInterval(interval).and(Opportunity.withSite(site)));

		forEach(withSite(site).and(inInterval(interval)).and(isQueue), call -> {
			final String number = call.getCallerId().getNumber();
			if (isEmpty(number)) {
				i.anon++;
			} else {
				final DateTime first = firstCall.get(number);
				if (first.equals(call.getCreated())) {
					i.first++;
				} else {
					i.old++;
				}
			}
		});
		return i;
	}
}
