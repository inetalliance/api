package net.inetalliance.sonar.api;

import com.callgrove.obj.*;
import com.callgrove.types.*;
import net.inetalliance.angular.*;
import net.inetalliance.angular.exception.*;
import net.inetalliance.potion.*;
import net.inetalliance.potion.query.*;
import net.inetalliance.sql.*;
import net.inetalliance.types.Currency;
import net.inetalliance.types.json.*;
import org.joda.time.*;

import javax.servlet.annotation.*;
import javax.servlet.http.*;
import java.util.*;

import static net.inetalliance.potion.Locator.*;

@WebServlet("/api/managerStats")
public class ManagerStats
		extends AngularServlet {
	@Override
	protected void get(final HttpServletRequest request, final HttpServletResponse response)
			throws Exception {
		final var manager = Startup.getAgent(request);
		if (manager == null) {
			throw new NotFoundException();
		}
		var intervals = new LinkedHashMap<String, Interval>();
		intervals.put("today", new DateMidnight().toInterval());
		intervals.put("yesterday", new DateMidnight()
				.minusDays(1)
				.toInterval());
		intervals.put("week", new Interval(new DateMidnight().withDayOfWeek(DateTimeConstants.MONDAY),
		                                   new DateMidnight()
				                                   .withDayOfWeek(DateTimeConstants.MONDAY)
				                                   .plusWeeks(1)
		));
		intervals.put("month", new Interval(new DateMidnight().withDayOfMonth(1),
		                                    new DateMidnight()
				                                    .withDayOfMonth(1)
				                                    .plusMonths(1)
		));

		final Agent loggedIn = Startup.getAgent(request);
		assert loggedIn != null;
		final var viewable =
				Locator.$$(loggedIn
						           .getViewableAgentsQuery(false)
						           .and(Agent.activeAfter(new DateMidnight().withDayOfMonth(1)))
						           .and(Agent.isSales));

		if (viewable.isEmpty()) {
			throw new UnauthorizedException();
		}

		var theQuery =
				Opportunity
						.withAgentIn(viewable)
						.and(Opportunity
								     .withSources(EnumSet.of(SaleSource.ONLINE))
								     .negate());

		Map<String, JsonList> agentIntervalData = new HashMap<>();
		intervals.forEach((intervalLabel, interval) -> {
			var sales =
					Locator.$$(theQuery.and(Opportunity.soldInInterval(interval)), Aggregate.SUM, String.class, "assignedTo",
					           Currency.class, "amount"
					);
			var closes =
					Locator.$$(theQuery.and(Opportunity.soldInInterval(interval)), Aggregate.COUNT, String.class, "assignedTo",
					           Integer.class, "*"
					);
			viewable.forEach(a -> {
				final int c = closes.getOrDefault(a.key, 0);
				final Currency r = sales.getOrDefault(a.key, Currency.ZERO);
				agentIntervalData
						.computeIfAbsent(a.key, k -> new JsonList())
						.add(new JsonMap()
								     .$("c", c)
								     .$("r", r.doubleValue()));
			});
		});
		var jsonList = new JsonList();
		agentIntervalData.forEach((k, list) -> {
			final Agent agent = new Agent(k);
			final Query<Opportunity> withAgent =
					Opportunity
							.withAgent(agent)
							.and(Opportunity.createdInInterval(new DateMidnight().toInterval()));
			final Query<Call> todayWithAgent = Call
					.withAgent(agent)
					.and(Call.inInterval(new DateMidnight().toInterval()));
			jsonList.add(new JsonMap()
					             .$("agent", k)
					             .$("intervals", list)
					             .$("in", count(todayWithAgent.and(Call.isQueue)))
					             .$("out", count(todayWithAgent.and(Call.isOutbound)))
					             .$("surveys", count(withAgent.and(Opportunity.withSaleSource(SaleSource.SURVEY))))
					             .$("social", count(withAgent.and(Opportunity.withSaleSource(SaleSource.SOCIAL)))));
		});

		var aMap = new JsonMap();
		viewable.forEach(a -> aMap.put(a.key, a.getFullName()));

		var json = new JsonMap();
		json.put("agents", aMap);
		json.put("sales", jsonList);

		respond(response, json);

	}

	private static Query<Opportunity> withProduct(int id) {
		return Opportunity.withProductLine(new ProductLine(id));
	}
}
