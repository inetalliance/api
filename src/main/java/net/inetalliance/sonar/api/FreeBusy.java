package net.inetalliance.sonar.api;

import com.callgrove.obj.Agent;
import com.callgrove.obj.Opportunity;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.val;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.auth.Auth;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.sql.DateTimeInterval;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;

import java.util.Objects;

import static com.ameriglide.phenix.core.Strings.isEmpty;
import static com.callgrove.obj.Opportunity.withAgent;
import static com.callgrove.obj.Opportunity.withReminderIn;
import static net.inetalliance.potion.Locator.$;
import static net.inetalliance.potion.Locator.forEach;
import static net.inetalliance.sql.OrderBy.Direction.ASCENDING;

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
        final DateTimeInterval interval;
        val day = request.getParameter("day");
        if (isEmpty(day)) {
            val month = request.getParameter("month");
            if (isEmpty(month)) {
                throw new BadRequestException("Missing parameter \"month\"");
            } else {
                monthMode = true;
                val startOfMonth =
                        Objects.requireNonNull(Json.parseDate(month)).withDayOfMonth(1).toLocalDate();
                val start = startOfMonth.minusDays(startOfMonth.getDayOfWeek().getValue() % 7);
                interval = new DateTimeInterval(start, start.plusDays(35));
            }
        } else {
            monthMode = false;
            interval = new DateTimeInterval(Objects.requireNonNull(Json.parseDate(day)).toLocalDate());
        }
        val ticket = Auth.getAuthorized(request);
        val agent = $(new Agent(ticket.getPhone()));

        val map = new JsonMap();
        forEach(withReminderIn(interval).and(withAgent(agent)).orderBy("reminder", ASCENDING), opp -> {
            if (monthMode) {
                final String day1 = Json.format(opp.getReminder().toLocalDate());
                var list = map.getList(day1);
                if (list == null) {
                    list = new JsonList();
                    map.put(day1, list);
                }
                list.add(toJson(opp));
            } else {
                map.put(Json.format(opp.getReminder()), toJson(opp));
            }

        });
        respond(response, map);
    }
}
