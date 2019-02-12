package net.inetalliance.sonar.api;

import com.callgrove.obj.Agent;
import com.callgrove.obj.Call;
import com.callgrove.obj.Opportunity;
import com.callgrove.obj.ProductLine;
import com.callgrove.types.SaleSource;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.auth.Auth;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.angular.exception.UnauthorizedException;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sql.Aggregate;
import net.inetalliance.types.Currency;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.DateMidnight;
import org.joda.time.DateTimeConstants;
import org.joda.time.Interval;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.*;

import static net.inetalliance.potion.Locator.*;

@WebServlet("/api/managerStats")
public class ManagerStats
	extends AngularServlet {
	@Override
	protected void get(final HttpServletRequest request, final HttpServletResponse response) throws Exception {
		final var manager = Startup.getAgent(request);
		if (manager == null) {
			throw new NotFoundException();
		}
		if (manager.isManager() || manager.isTeamLeader()) {
			var intervals = new LinkedHashMap<String, Interval>();
			intervals.put("today", new DateMidnight().toInterval());
			intervals.put("yesterday", new DateMidnight().minusDays(1).toInterval());
			intervals.put("week", new Interval(new DateMidnight().withDayOfWeek(DateTimeConstants.MONDAY),
				new DateMidnight().withDayOfWeek(DateTimeConstants.MONDAY).plusWeeks(1)));
			intervals.put("month",
				new Interval(new DateMidnight().withDayOfMonth(1), new DateMidnight().withDayOfMonth(1).plusMonths(1)));

			final var viewable = new HashSet<Agent>();
			manager.getManagedCallCenters(Auth.getAuthorized(request)).forEach(
				c -> viewable.addAll(Locator.$$(Agent.withCallCenter(c))));

			if(viewable.isEmpty()) {
				viewable.addAll(manager.getViewableAgents());
			}

			var theQuery = Opportunity.withAgentIn(viewable).and(
				Opportunity.withSources(EnumSet.of(SaleSource.PHONE_CALL, SaleSource.MANUAL)));

			Map<String, JsonList> agentIntervalData = new HashMap<>();
			intervals.forEach((intervalLabel, interval) -> {
				var sales = Locator.$$(theQuery.and(Opportunity.soldInInterval(interval)),
					Aggregate.SUM, String.class, "assignedTo", Currency.class, "amount");
				var closes = Locator.$$(theQuery.and(Opportunity.soldInInterval(interval)),
					Aggregate.COUNT, String.class, "assignedTo", Integer.class, "*");
				viewable.forEach(a -> {
					final int c = closes.getOrDefault(a.key, 0);
					final Currency r = sales.getOrDefault(a.key, Currency.ZERO);
					agentIntervalData.computeIfAbsent(a.key, k -> new JsonList()).add(
						new JsonMap().$("c", c).$("r", r.doubleValue()));
				});
			});
			var jsonList = new JsonList();
			agentIntervalData.forEach((k, list) -> {
				if (list.stream().anyMatch(m -> ((JsonMap) m).getInteger("c") > 0)) {
					final Agent agent = new Agent(k);
					final Query<Opportunity> withAgent = Opportunity.withAgent(agent).and(
						Opportunity.createdInInterval(new DateMidnight().toInterval()));
					final Query<Call> todayWithAgent = Call.withAgent(agent).and(
						Call.inInterval(new DateMidnight().toInterval()));
					jsonList.add(new JsonMap()
						.$("agent", k)
						.$("intervals", list)
						.$("in", count(todayWithAgent.and(Call.isQueue)))
						.$("out", count(todayWithAgent.and(Call.isOutbound)))
						.$("surveys", count(withAgent.and(Opportunity.withSaleSource(SaleSource.SURVEY))))
						.$("social", count(withAgent.and(Opportunity.withSaleSource(SaleSource.SOCIAL)))));
				}

			});

			var aMap = new JsonMap();
			viewable.forEach(a -> aMap.put(a.key, a.getFullName()));

			var json = new JsonMap();
			json.put("agents", aMap);
			json.put("sales", jsonList);

			respond(response, json);

		} else {
			throw new UnauthorizedException();
		}
	}

	private static Query<Opportunity> withProduct(int id) {
		return Opportunity.withProductLine(new ProductLine(id));
	}
}
