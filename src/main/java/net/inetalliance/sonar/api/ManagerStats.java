package net.inetalliance.sonar.api;

import com.callgrove.obj.Agent;
import com.callgrove.obj.Call;
import com.callgrove.obj.Opportunity;
import com.callgrove.types.SaleSource;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.val;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.ForbiddenException;
import net.inetalliance.angular.exception.UnauthorizedException;
import net.inetalliance.potion.Locator;
import net.inetalliance.sql.Aggregate;
import net.inetalliance.sql.DateTimeInterval;
import net.inetalliance.types.Currency;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static java.time.temporal.TemporalAdjusters.nextOrSame;
import static net.inetalliance.potion.Locator.count;

@WebServlet("/api/managerStats")
public class ManagerStats
        extends AngularServlet {

    @Override
    protected void get(final HttpServletRequest request, final HttpServletResponse response)
            throws Exception {
        val manager = Startup.getAgent(request);
        if (manager == null) {
            throw new ForbiddenException();
        }
        var intervals = new LinkedHashMap<String, DateTimeInterval>();
        intervals.put("today", new DateTimeInterval(LocalDate.now()));
        intervals.put("yesterday", new DateTimeInterval(LocalDate.now()
                .minusDays(1)));
        intervals.put("week", new DateTimeInterval(LocalDate.now().with(nextOrSame(DayOfWeek.MONDAY)),
                LocalDate.now()
                        .plusWeeks(1)
                        .with(nextOrSame(DayOfWeek.MONDAY))));
        intervals.put("month", new DateTimeInterval(LocalDate.now().withDayOfMonth(1),
                LocalDate.now()
                        .withDayOfMonth(1)
                        .plusMonths(1)
        ));
        intervals.put("lastMonth", new DateTimeInterval(LocalDate.now().withDayOfMonth(1).minusMonths(1),
                LocalDate.now().withDayOfMonth(1)));

        val viewable =
                Locator.$$(manager
                        .getViewableAgentsQuery(false)
                        .and(Agent.activeAfter(LocalDate.now().withDayOfMonth(1).atStartOfDay()))
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
        var totalList = new JsonList();
        intervals.forEach((i, interval) -> {
            totalList.add(new JsonMap().$("c", 0).$("r", 0.0d));
            var sales =
                    Locator
                            .$$(theQuery.and(Opportunity.soldInInterval(interval)), Aggregate.SUM, String.class,
                                    "assignedTo",
                                    Currency.class, "amount"
                            );
            var closes =
                    Locator
                            .$$(theQuery.and(Opportunity.soldInInterval(interval)), Aggregate.COUNT, String.class,
                                    "assignedTo",
                                    Integer.class, "*"
                            );
            viewable.forEach(a -> {
                final int c = closes.getOrDefault(a.key, 0);
                val r = sales.getOrDefault(a.key, Currency.ZERO);
                agentIntervalData
                        .computeIfAbsent(a.key, s -> new JsonList())
                        .add(new JsonMap()
                                .$("c", c)
                                .$("r", r.doubleValue()));
            });
        });
        var jsonList = new JsonList();


        var totalAgent = new JsonMap().$("agent", "total")
                .$("intervals", totalList)
                .$("in", 0)
                .$("out", 0)
                .$("surveys", 0)
                .$("social", 0);
        agentIntervalData.forEach((k, list) -> {
            val agent = new Agent(k);
            val withAgent =
                    Opportunity
                            .withAgent(agent)
                            .and(Opportunity.createdInInterval(new DateTimeInterval(LocalDate.now())));
            val todayWithAgent = Call
                    .withAgent(agent)
                    .and(Call.inInterval(new DateTimeInterval(LocalDate.now())));
            var agentJson = new JsonMap()
                    .$("agent", k)
                    .$("intervals", list)
                    .$("in", count(todayWithAgent.and(Call.isQueue).and(Call.isAnswered)))
                    .$("out", count(todayWithAgent.and(Call.isOutbound)))
                    .$("surveys", count(withAgent.and(Opportunity.withSaleSource(SaleSource.SURVEY))))
                    .$("social", count(withAgent.and(Opportunity.withSaleSource(SaleSource.SOCIAL))));
            totalAgent.put("in", totalAgent.getInteger("in") + agentJson.getInteger("in"));
            totalAgent.put("out", totalAgent.getInteger("out") + agentJson.getInteger("out"));
            totalAgent.put("surveys", totalAgent.getInteger("surveys") + agentJson.getInteger("surveys"));
            totalAgent.put("social", totalAgent.getInteger("social") + agentJson.getInteger("social"));
            for (var i = 0; i < list.size(); i++) {
                var iData = ((JsonMap) list.get(i));
                var tData = (JsonMap) totalList.get(i);
                tData.put("c", tData.getInteger("c") + iData.getInteger("c"));
                tData.put("r", tData.getDouble("r") + iData.getDouble("r"));
            }
            jsonList.add(agentJson);
        });
        jsonList.add(totalAgent);

        var aMap = new JsonMap();
        viewable.forEach(a -> aMap.put(a.key, a.getFullName()));
        aMap.put("total", "Total");

        var json = new JsonMap();
        json.put("agents", aMap);
        json.put("sales", jsonList);

        respond(response, json);

    }
}
