package net.inetalliance.sonar.api;

import com.callgrove.obj.Agent;
import com.callgrove.obj.Call;
import com.callgrove.obj.Opportunity;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.auth.Auth;
import net.inetalliance.angular.exception.ForbiddenException;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.Currency;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.DateMidnight;
import org.joda.time.Interval;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.callgrove.obj.Opportunity.*;
import static com.callgrove.types.SaleSource.*;
import static net.inetalliance.potion.Locator.$$;
import static net.inetalliance.potion.Locator.count;
import static net.inetalliance.sql.Aggregate.SUM;

@WebServlet("/api/dashboard")
public class Dashboard extends AngularServlet {
    @Override
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        final Agent loggedIn = Locator.$(new Agent(Auth.getAuthorized(request).getPhone()));
        if (loggedIn == null) {
            throw new ForbiddenException();
        }
        var json = new JsonMap();

        var today = new DateMidnight();
        var intervals = Map.of("Today", today.toInterval(),
                "Week", new Interval(today.withDayOfWeek(1), today.withDayOfWeek(7)),
                "Month", new Interval(today.withDayOfMonth(1), today.withDayOfMonth(1).plusMonths(1)),
                "Last", new Interval(today.withDayOfMonth(1).minusMonths(1), today.withDayOfMonth(1))
        );

        var manage = "manager".equals(request.getParameter("mode"));

        var callCount = Call.withAgent(loggedIn);
        var withAgent = manage ?
                Opportunity.withAgentIn(loggedIn.getViewableAgents()) :
                Opportunity.withAgent(loggedIn);

        var sources = new LinkedHashMap<String, Query<Opportunity>>();
        sources.put("Phone", withAgent.and(withSaleSource(PHONE_CALL)));
        sources.put("Form", withAgent.and(withSaleSource(SURVEY)));
        sources.put("Facebook", withAgent.and(withSaleSource(SOCIAL)));
        sources.put("Referral", withAgent.and(withSaleSource(THIRD_PARTY_ONLINE)));
        intervals.forEach((key, interval) -> {
            var leads = new JsonMap();
            sources.forEach((source, withSource) -> {
                leads.put(source, new JsonMap()
                        .$("new", count(withSource.and(createdInInterval(interval))))
                        .$("closed", count(withSource.and(soldInInterval(interval))))
                        .$("revenue", n2z($$(withSource.and(soldInInterval(interval)), SUM, Currency.class, "amount")))
                );
            });
            json.$(key, new JsonMap()
                    .$("calls", count(callCount.and(Call.inInterval(interval))))
                    .$("leads", leads)
            );
        });
        respond(response, new JsonMap().$("intervals", json));
    }

    private static Double n2z(Currency c) {
        return (c == null ? Currency.ZERO : c).doubleValue();
    }
}
