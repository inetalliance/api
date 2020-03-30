package net.inetalliance.sonar.api;

import static com.callgrove.obj.Opportunity.withAgent;
import static com.callgrove.obj.Opportunity.withReminderIn;
import static net.inetalliance.funky.StringFun.isEmpty;
import static net.inetalliance.potion.Locator.$;
import static net.inetalliance.potion.Locator.forEach;
import static net.inetalliance.sql.OrderBy.Direction.ASCENDING;

import com.callgrove.obj.Agent;
import com.callgrove.obj.Opportunity;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.auth.Auth;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.util.security.auth.Authorized;
import org.joda.time.DateMidnight;
import org.joda.time.Interval;

@WebServlet("/api/freeBusy")
public class FreeBusy
    extends AngularServlet {

  private static JsonMap toJson(final Opportunity opp) {
    return new JsonMap().$("id", opp.id)
        .$("productLine", opp.getProductLine().getName())
        .$("reminder", opp.getReminder())
        .$("contact", opp.getContact().getLastNameFirstInitial())
        .$("site", opp.getSite().getName())
        .$("stage", opp.getStage())
        .$("amount", opp.getAmount());
  }

  @Override
  protected void get(final HttpServletRequest request, final HttpServletResponse response)
      throws Exception {
    final boolean monthMode;
    final Interval interval;
    final String day = request.getParameter("day");
    if (isEmpty(day)) {
      final String month = request.getParameter("month");
      if (isEmpty(month)) {
        throw new BadRequestException("Missing parameter \"month\"");
      } else {
        monthMode = true;
        final DateMidnight startOfMonth =
            Json.jsDateTimeFormat.parseDateTime(month).withDayOfMonth(1).toDateMidnight();
        final DateMidnight start = startOfMonth.minusDays(startOfMonth.getDayOfWeek()%7);
        interval = new Interval(start, start.plusDays(35));
      }
    } else {
      monthMode = false;
      interval = Json.jsDateTimeFormat.parseDateTime(day).toDateMidnight().toInterval();
    }
    final Authorized ticket = Auth.getAuthorized(request);
    final Agent agent = $(new Agent(ticket.getPhone()));

    final JsonMap map = new JsonMap();
    forEach(withReminderIn(interval).and(withAgent(agent)).orderBy("reminder", ASCENDING), opp -> {
      if (monthMode) {
        final String day1 = Json.jsDateFormat.print(opp.getReminder().toDateMidnight());
        JsonList list = map.getList(day1);
        if (list == null) {
          list = new JsonList();
          map.put(day1, list);
        }
        list.add(toJson(opp));
      } else {
        map.put(Json.jsDateTimeFormat.print(opp.getReminder()), toJson(opp));
      }

    });
    respond(response, map);
  }
}
