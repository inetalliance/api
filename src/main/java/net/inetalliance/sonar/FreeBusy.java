package net.inetalliance.sonar;

import com.callgrove.obj.Agent;
import com.callgrove.obj.Opportunity;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.auth.Auth;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.funky.functors.P1;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.util.security.auth.Authorized;
import org.joda.time.DateMidnight;
import org.joda.time.Interval;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static com.callgrove.obj.Opportunity.Q.withAgent;
import static com.callgrove.obj.Opportunity.Q.withReminderIn;
import static net.inetalliance.funky.functors.types.str.StringFun.empty;
import static net.inetalliance.potion.Locator.$;
import static net.inetalliance.potion.Locator.forEach;
import static net.inetalliance.sql.OrderBy.Direction.ASCENDING;

@WebServlet("/api/freeBusy")
public class FreeBusy
		extends AngularServlet {

	@Override
	protected void get(final HttpServletRequest request, final HttpServletResponse response)
			throws Exception {
		final boolean monthMode;
		final Interval interval;
		final String day = request.getParameter("day");
		if (empty.$(day)) {
			final String month = request.getParameter("month");
			if (empty.$(month)) {
				throw new BadRequestException("Missing parameter \"month\"");
			} else {
				monthMode = true;
				final DateMidnight startOfMonth = Json.F.Parse.jsDateTime.$(month).withDayOfMonth(1).toDateMidnight();
				final DateMidnight start = startOfMonth.minusDays(startOfMonth.getDayOfWeek());
				interval = new Interval(start, start.plusDays(35));
			}
		} else {
			monthMode = false;
			interval = Json.F.Parse.jsDateTime.$(day).toDateMidnight().toInterval();
		}
		final Authorized ticket = Auth.getAuthorized(request);
		final Agent agent = $(new Agent(ticket.getPhone()));

		final JsonMap map = new JsonMap();
		forEach(withReminderIn(interval).and(withAgent(agent)).orderBy("reminder", ASCENDING), new P1<Opportunity>() {
			@Override
			public void $(final Opportunity opp) {
				if (monthMode) {
					final String day = Json.F.Format.jsDate.$(opp.getReminder().toDateMidnight());
					JsonList list = map.getList(day);
					if (list == null) {
						list = new JsonList();
						map.put(day, list);
					}
					list.add(toJson(opp));
				} else {
					map.put(Json.F.Format.jsDateTime.$(opp.getReminder()), toJson(opp));
				}

			}
		});
		respond(response, map);
	}

	private static JsonMap toJson(final Opportunity opp) {
		return new JsonMap()
				.$("id", opp.id)
				.$("productLine", opp.getProductLine().getName())
				.$("reminder", opp.getReminder())
				.$("contact", opp.getContact().getLastNameFirstInitial())
				.$("site", opp.getSite().getName())
				.$("stage", opp.getStage())
				.$("amount", opp.getAmount());
	}
}
