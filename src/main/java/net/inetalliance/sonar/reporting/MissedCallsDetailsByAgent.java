package net.inetalliance.sonar.reporting;

import com.callgrove.Callgrove;
import com.callgrove.obj.Agent;
import com.callgrove.obj.Call;
import com.callgrove.obj.ProductLine;
import com.callgrove.obj.Site;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.val;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sql.OrderBy;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.*;

@WebServlet({"/reporting/reports/missedCallsDetailsByAgent"})
public class MissedCallsDetailsByAgent
        extends AngularServlet {

    private static final Info<Site> siteInfo = Info.$(Site.class);

    @Override
    protected void get(final HttpServletRequest request, final HttpServletResponse response)
            throws Exception {
        var interval = Callgrove.getReportingInterval(request);
        val agent = Locator.$(new Agent(request.getParameter("agent")));
        if (agent == null) {
            throw new NotFoundException("Agent not found");
        }
        var description = " for " + agent.getFullName() + " ";
        val siteIds = request.getParameterValues("site");
        final Collection<Site> sites = siteIds == null ?
                Collections.emptySet() :
                Arrays.stream(siteIds).map(siteInfo::lookup).collect(Collectors.toSet());

        val productLineIds = request.getParameterValues("productLine");
        final Set<ProductLine> productLines = productLineIds == null ?
                Collections.emptySet() : Arrays.stream(productLineIds)
                .map(id -> Locator.$(new ProductLine(Integer.valueOf(id))))
                .collect(toSet());

        Query<Call> callQuery = Call.inInterval(interval)
                .and(Call.withAgent(agent))
                .and(Call.missed)
                .orderBy("created", OrderBy.Direction.ASCENDING);

        if (!productLines.isEmpty()) {
            callQuery = callQuery.and(
                    Query.and(Call.class,
                            productLines.stream().map(Call::withProductLine).collect(toList())));
            description += " for " + productLines.stream().map(ProductLine::getName).collect(
                    joining(", ")) + " ";
        }
        if (!sites.isEmpty()) {
            callQuery = callQuery.and(Call.withSiteIn(sites));
            description += " on " + sites.stream().map(Site::getAbbreviation).collect(
                    joining(", ")) + " ";
        }

        description += "between " + Json.format(interval.start()) + " and " +
                Json.format(interval.end());

        val count = Locator.count(callQuery);
        description = count + " missed call" + (count == 1 ? "" : "s") + description;

        val list = new JsonList();
        Locator.forEach(callQuery, call -> {
            val queue = call.getQueue();
            val site = call.getSite();
            list.add(new JsonMap()
                    .$("key", call.key)
                    .$("date", call.getDate())
                    .$("queue", queue == null ? null : queue.getName())
                    .$("site", site == null ? null : site.getName())
            );
        });
        respond(response, new JsonMap()
                .$("description", description)
                .$("rows", list));
    }
}
