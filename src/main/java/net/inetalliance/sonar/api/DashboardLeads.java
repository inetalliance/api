package net.inetalliance.sonar.api;

import com.callgrove.obj.AreaCodeTime;
import com.callgrove.obj.Opportunity;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.ForbiddenException;
import net.inetalliance.funky.Funky;
import net.inetalliance.funky.StringFun;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.geopolitical.us.State;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.DateMidnight;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.stream.Stream;

import static java.lang.System.currentTimeMillis;
import static java.util.stream.Collectors.toList;
import static net.inetalliance.potion.Locator.count;
import static net.inetalliance.potion.Locator.forEach;

@WebServlet({"/api/asap", "/api/pipeline"})
public class DashboardLeads extends AngularServlet {
    @Override
    protected void get(final HttpServletRequest request, final HttpServletResponse response) throws Exception {
        var loggedIn = Startup.getAgent(request);
        if (loggedIn == null) {
            throw new ForbiddenException();
        }
        int page;
        Query<Opportunity> q;
        switch (request.getRequestURI()) {
            case "/api/asap":
                q = Opportunity.withAgent(loggedIn)
                        .and(Opportunity.withoutReminder)
                        .and(Opportunity.uncontacted)
                        .and(Opportunity.createdAfter(new DateMidnight().minusDays(14)))
                        .orderBy("created");
                break;
            case "/api/pipeline":
                q = Opportunity.withAgent(loggedIn);
                var pls = request.getParameterValues("pl");
                if (pls != null && pls.length > 0) {
                    q = q.and(Opportunity.withProductLineIdIn(Stream.of(pls)
                            .map(Integer::valueOf).collect(toList())));
                }
                var search = request.getParameter("q");
                if (StringFun.isNotEmpty(search)) {
                    q = Leads.buildSearchQuery(q, search);
                } else {
                    q = q.and(Opportunity.isActive)
                            .orderBy("lastContact");
                }

                break;
            default:
                throw new IllegalArgumentException();
        }
        page = Funky.of(request.getParameter("page")).map(Integer::valueOf).orElse(1);
        var pages = (int) Math.ceil(count(q) / 10.0d);
        if (page > pages) {
            page = pages;
        }
        var list = new JsonList();
        var result = new JsonMap()
                .$("pages", pages)
                .$("page", page)
                .$("leads", list);
        if (pages > 0) {
            forEach(q.limit((page - 1) * 10, 10), o -> {
                var c = o.getContact();
                var p = o.getProductLine();
                var localTime = AreaCodeTime.getAreaCodeTime(c.getShipping().getPhone());
                list.add(new JsonMap()
                        .$("id", o.id)
                        .$("stage", o.getStage())
                        .$("lastContact", o.getLastContact())
                        .$("source", o.getSource())
                        .$("created", o.getCreated())
                        .$("notes", o.getNotes())
                        .$("product", new JsonMap()
                                .$("id", p.id)
                                .$("abbreviation", p.getAbbreviation())
                                .$("name", p.getName()))
                        .$("contact", new JsonMap()
                                .$("id", c.id)
                                .$("name", c.getFullName())
                                .$("state", new JsonMap()
                                        .$("name", Funky.of(c.getShipping().getState()).map(s -> s.getLocalizedName().toString()).orElse(""))
                                        .$("abbreviation", Funky.of(c.getShipping().getState()).map(State::getAbbreviation).orElse("")))
                                .$("phone", c.getShipping().getPhone())
                                .$("localTime", localTime == null ? null : localTime.getDateTimeZone().getOffset(currentTimeMillis()))
                                .$("email", c.getEmail())));
            });
        }
        respond(response, result);
    }
}
