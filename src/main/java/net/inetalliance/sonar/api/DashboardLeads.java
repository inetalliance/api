package net.inetalliance.sonar.api;

import com.ameriglide.phenix.core.Optionals;
import com.callgrove.obj.AreaCodeTime;
import com.callgrove.obj.Opportunity;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.ForbiddenException;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.geopolitical.us.State;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.ameriglide.phenix.core.Strings.isNotEmpty;
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
        var manage = "manager".equals(request.getParameter("mode"));
        var withAgent = manage ? Opportunity.withAgentIn(loggedIn.getViewableAgents())
                : Opportunity.withAgent(loggedIn);
        int page;
        Query<Opportunity> q;
        switch (request.getRequestURI()) {
            case "/api/asap":
                q = withAgent
                        .and(Opportunity.withoutReminder)
                        .and(Opportunity.uncontacted)
                        .and(Opportunity.createdAfter(LocalDate.now().minusDays(14).atStartOfDay()))
                        .orderBy("created");
                break;
            case "/api/pipeline":
                q = withAgent;
                var pls = request.getParameterValues("pl");
                if (pls != null && pls.length > 0) {
                    q = q.and(Opportunity.withProductLineIdIn(Stream.of(pls)
                            .map(Integer::valueOf).collect(toList())));
                }
                var search = request.getParameter("q");
                if (isNotEmpty(search)) {
                    q = Leads.buildSearchQuery(q, search);
                } else {
                    q = q.and(Opportunity.isActive)
                            .orderBy("lastContact");
                }

                break;
            default:
                throw new IllegalArgumentException();
        }
        page = Optionals.of(request.getParameter("page")).map(Integer::valueOf).orElse(1);
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
                var a = o.getAssignedTo();
                var c = o.getContact();
                var p = o.getProductLine();
                var localTime = AreaCodeTime.getAreaCodeTime(c.getShipping().getPhone());
                list.add(new JsonMap()
                        .$("id", o.id)
                        .$("stage", o.getStage())
                        .$("assignedTo", new JsonMap()
                                .$("key", a.key)
                                .$("name", a.getFirstNameLastInitial()))
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
                                        .$("name", Optionals.of(c.getShipping().getState()).map(s -> s.getLocalizedName().toString()).orElse(""))
                                        .$("abbreviation", Optionals.of(c.getShipping().getState()).map(State::getAbbreviation).orElse("")))
                                .$("phone", c.getShipping().getPhone())
                                .$("localTime", localTime == null ? null : TimeUnit.SECONDS.toMillis(localTime.getLocalDateTimeZone().getRules().getOffset(LocalDateTime.now()).getTotalSeconds()))
                                .$("email", c.getEmail())));
            });
        }
        respond(response, result);
    }
}
