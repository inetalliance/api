package net.inetalliance.sonar.api;

import com.callgrove.Callgrove;
import com.callgrove.obj.*;
import com.callgrove.types.SaleSource;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.ForbiddenException;
import net.inetalliance.funky.StringFun;
import net.inetalliance.funky.math.Calculator;
import net.inetalliance.funky.math.Stats;
import net.inetalliance.log.Log;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.cache.RedisJsonCache;
import net.inetalliance.potion.obj.IdPo;
import net.inetalliance.potion.query.Query;
import net.inetalliance.angular.events.ProgressHandler;
import net.inetalliance.sql.Aggregate;
import net.inetalliance.types.Currency;
import net.inetalliance.types.json.JsonFloat;
import net.inetalliance.types.json.JsonList;
import org.joda.time.DateMidnight;
import org.joda.time.DateTime;
import org.joda.time.Interval;
import org.joda.time.Weeks;
import org.joda.time.format.DateTimeFormat;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.util.*;

import static com.callgrove.obj.Opportunity.*;
import static java.util.stream.Collectors.*;
import static net.inetalliance.funky.StringFun.*;
import static net.inetalliance.log.Log.*;
import static net.inetalliance.types.json.Json.*;
import static net.inetalliance.types.www.ContentType.*;

/**
 * this responds with filtered sales data in a json list by day that is compatible with the google charts api
 */
@WebServlet("/api/salesChart")
public class DailySales
	extends AngularServlet {

	private RedisJsonCache cache;

	@Override
	protected void get(final HttpServletRequest request, final HttpServletResponse response)
		throws Exception {
		final Agent loggedIn = Startup.getAgent(request);
		assert loggedIn != null;
		final Set<Site> sites = Startup.locateParameterValues(request, "site", Site.class);
		final Set<ProductLine> productLines = Startup.locateParameterValues(request, "productLine",
			ProductLine.class);
		final Set<Agent> agents = Startup.locateParameterValues(request, "agent", Agent.class);
		final String mode = request.getParameter("mode");
		final SaleSource saleSource = isEmpty(mode) || "all".equals(mode)
			? null : StringFun.camelCaseToEnum(SaleSource.class, mode);
		final Interval interval = Callgrove.getInterval(request);
		final String cacheKey = String.format("l:%s,s:%s,p:%s,a:%s,m:%s,i:%s/%s",
			loggedIn.key,
			IdPo.mapId(sites),
			IdPo.mapId(productLines),
			agents.stream().map(a -> a.key).collect(toList()), saleSource,
			DateTimeFormat.shortDate().print(interval.getStart()),
			DateTimeFormat.shortDate().print(interval.getEnd()));

		final String cached = cache.get(cacheKey);
		if (StringFun.isEmpty(cached)) {

			Query<Call> cQ = Call.isQueue;
			Query<Opportunity> oQ = Query.all(Opportunity.class);

			// restrict to visible sites and do some snoop detection
			if (sites.isEmpty()) {
				// if it is a manager, we can just not restrict site at all, otherwise, load up all the visible sites
				if (!loggedIn.isManager()) {
					sites.addAll(loggedIn.getVisibleSites());
				}
			} else if (!loggedIn.isManager()) {
				final Set<Site> visible = loggedIn.getVisibleSites();
				if (!visible.containsAll(sites)) {
					// this shouldn't really happen. they'd have to be sneakily changing the json URI outside of the UI
					final Set<Site> forbidden = new HashSet<>(sites);
					forbidden.removeAll(visible);
					log.error("%s tried to request sales data for %s, but is only granted access to %s", forbidden,
						visible);
					throw new ForbiddenException();
				}
			}
			if (!sites.isEmpty()) {
				oQ = oQ.and(withSiteIn(sites));
				cQ = cQ.and(Call.withSiteIn(sites));
			}
			if (!productLines.isEmpty()) {
				oQ = oQ.and(withProductLineIn(productLines));
				cQ = cQ.and(Startup.callsWithProductLines(productLines));
			}
			if (saleSource != null) {
				oQ = oQ.and(withSaleSource(saleSource));
			}

			// restrict to visible agents and do some snoop detection
			if (agents.isEmpty()) {
				if (!loggedIn.isManager()) {
					final Set<Agent> visible = loggedIn.getViewableAgents();
					agents.addAll(visible);
				}
			} else if (!loggedIn.isManager()) {
				final Set<Agent> visible = loggedIn.getViewableAgents();
				if (!visible.containsAll(agents)) {
					final Set<Agent> forbidden = new HashSet<>(agents);
					agents.removeAll(visible);
					log.error("%s tried to request sales data for %s, but is only granted access to %s", forbidden,
						visible);
					throw new ForbiddenException();
				}
			}
			if (!agents.isEmpty()) {
				oQ = oQ.and(withAgentIn(agents));
				cQ = cQ.and(Call.withAgentIn(agents));
			}
			final JsonList days = new JsonList();
			final Query<Opportunity> finalOQ = oQ;
			final Query<Call> finalCQ = cQ;

			final Map<String, DateTime> firstCall = Locator.$$(Call.withSiteIn(sites).and(Call.isQueue), Aggregate.MIN,
				String.class, "callerid_number", DateTime.class, "created");
			final SortedSet<DateTime> newCallers = new TreeSet<>(firstCall.values());

			final DateMidnight end = interval.getEnd().plusDays(1).toDateMidnight();
			ProgressHandler.$.start(loggedIn.key, response, Weeks.weeksBetween(interval.getStart(), end).getWeeks(),
				progressMeter -> {
					DateMidnight current = interval.getStart().toDateMidnight();
					if (current.getDayOfWeek() != end.getDayOfWeek()) {
						current = current.withDayOfWeek(end.getDayOfWeek());
						if (current.isAfter(interval.getStart())) {
							current = current.minusWeeks(1);
						}
					}
					while (current.isBefore(end)) {
						final JsonList point = new JsonList();
						final Calculator<Currency> calc = new Calculator<>(Currency.MATH);
						final Interval week = new Interval(current, current.plusWeeks(1).minus(1));
						Locator.forEach(finalOQ.and(Opportunity.isSold.and(soldInInterval(week))),
							o -> calc.accept(o.getAmount()));

						point.add(jsDateFormat.print(current));
						final Stats<Currency> stats = calc.getStats();
						point.add(stats.n);
						point.add(new JsonFloat(stats.sum()));
						point.add(Locator.count(finalOQ.and(createdInInterval(week))));
						final Query<Call> finalCQinInterval = finalCQ.and(Call.inInterval(week));
						point.add(Locator.count(finalCQinInterval));
						point.add(Locator.countDistinct(finalCQinInterval, "callerId_number"));
						point.add(newCallers.subSet(week.getStart(), week.getEnd()).size());

						days.add(point);
						current = current.plusWeeks(1);
						progressMeter.increment();
					}
					cache.set(cacheKey, days);
					return days;
				});

		} else {
			log.debug("Returning cached report result for %s", cacheKey);
			response.setContentType(JSON.toString());
			try (final PrintWriter writer = response.getWriter()) {
				writer.write(cached);
				writer.flush();
			}
		}
	}

	@Override
	public void init(final ServletConfig config)
		throws ServletException {
		super.init(config);
		cache = new RedisJsonCache(config.getServletName());
	}

	private static final transient Log log = getInstance(DailySales.class);
}
