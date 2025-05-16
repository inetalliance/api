package net.inetalliance.sonar.api;

import com.ameriglide.phenix.core.Calculator;
import com.ameriglide.phenix.core.DateTimeFormats;
import com.ameriglide.phenix.core.Enums;
import com.ameriglide.phenix.core.Log;
import com.callgrove.Callgrove;
import com.callgrove.obj.*;
import com.callgrove.types.SaleSource;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.val;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.events.ProgressHandler;
import net.inetalliance.angular.exception.ForbiddenException;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.cache.RedisJsonCache;
import net.inetalliance.potion.obj.IdPo;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sql.Aggregate;
import net.inetalliance.sql.DateTimeInterval;
import net.inetalliance.types.Currency;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonFloat;
import net.inetalliance.types.json.JsonList;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import static com.ameriglide.phenix.core.Strings.isEmpty;
import static com.callgrove.obj.Opportunity.*;
import static java.time.format.FormatStyle.SHORT;
import static java.time.temporal.TemporalAdjusters.nextOrSame;
import static java.util.stream.Collectors.toList;
import static net.inetalliance.types.www.ContentType.JSON;

/**
 * this servlet responds with filtered sales data in a JSON list by day that is compatible with the Google
 * charts api
 */
@WebServlet("/api/salesChart")
public class DailySales
        extends AngularServlet {

    private static final Log log = new Log();
    private RedisJsonCache cache;

    @Override
    protected void get(final HttpServletRequest request, final HttpServletResponse response)
            throws Exception {
        val loggedIn = Startup.getAgent(request);
        if (loggedIn == null) {
            throw new ForbiddenException();
        }
        val sites = Startup.locateParameterValues(request, "site", Site.class);
        val productLines = Startup
                .locateParameterValues(request, "productLine", ProductLine.class);
        val agents = Startup.locateParameterValues(request, "agent", Agent.class);
        val mode = request.getParameter("mode");
        val saleSource =
                isEmpty(mode) || "all".equals(mode) ? null
                        : Enums.decamel(SaleSource.class, mode);
        val interval = Callgrove.getInterval(request);
        val cacheKey =
                String.format("l:%s,s:%s,p:%s,a:%s,m:%s,i:%s/%s", loggedIn.key, IdPo.mapId(sites),
                        IdPo.mapId(productLines),
                        agents.stream().map(a -> a.key).collect(toList()), saleSource,
                        DateTimeFormats.ofDate(SHORT).format(interval.start()),
                        DateTimeFormats.ofDate(SHORT).format(interval.end()));

        val cached = cache.get(cacheKey);
        if (isEmpty(cached)) {

            var cQ = Call.isQueue;
            var oQ = Query.all(Opportunity.class);

            // restrict to visible sites and do some snoop detection
            if (sites.isEmpty()) {
                // if it is a manager, we can just not restrict the site at all, otherwise, load up all the visible sites
                if (!loggedIn.isManager()) {
                    sites.addAll(loggedIn.getVisibleSites());
                }
            } else if (!loggedIn.isManager()) {
                val visible = loggedIn.getVisibleSites();
                if (!visible.containsAll(sites)) {
                    // this shouldn't really happen. they'd have to be sneakily changing the JSON URI outside the UI
                    final Set<Site> forbidden = new HashSet<>(sites);
                    forbidden.removeAll(visible);
                    log.error(() -> "%s tried to request sales data for %s, but is only granted access to %s".formatted(
                            loggedIn.getFullName(), forbidden, visible));
                    throw new ForbiddenException();
                }
            }
            if (!sites.isEmpty()) {
                oQ = oQ.and(withSiteIn(sites));
                cQ = cQ.and(Call.withSiteIn(sites));
            }
            if (!productLines.isEmpty()) {
                oQ = oQ.and(withProductLineIn(productLines));
                cQ = cQ.and(Startup.callsWithProductLines(productLines));
            }
            if (saleSource != null) {
                oQ = oQ.and(withSaleSource(saleSource));
            }

            // restrict to visible agents and do some snoop detection
            if (agents.isEmpty()) {
                if (!loggedIn.isManager()) {
                    final Set<Agent> visible = loggedIn.getViewableAgents();
                    agents.addAll(visible);
                }
            } else if (!loggedIn.isManager()) {
                final Set<Agent> visible = loggedIn.getViewableAgents();
                if (!visible.containsAll(agents)) {
                    final Set<Agent> forbidden = new HashSet<>(agents);
                    agents.removeAll(visible);
                    log.error(() -> "%s tried to request sales data for %s, but is only granted access to %s".formatted(
                            loggedIn, forbidden, visible));
                    throw new ForbiddenException();
                }
            }
            if (!agents.isEmpty()) {
                oQ = oQ.and(withAgentIn(agents));
                cQ = cQ.and(Call.withAgentIn(agents));
            }
            val days = new JsonList();
            val finalOQ = oQ;
            val finalCQ = cQ;

            val firstCall =
                    Locator.$$(Call.withSiteIn(sites).and(Call.isQueue), Aggregate.MIN, String.class,
                            "callerid_number", LocalDateTime.class, "created");
            val newCallers = new TreeSet<>(firstCall.values());

            val end = interval.end().plusDays(1);
            ProgressHandler.$
                    .start(loggedIn.key, response, (int) ChronoUnit.WEEKS.between(interval.start(), end),
                            progressMeter -> {
                                var current = interval.start().toLocalDate();
                                if (current.getDayOfWeek() != end.getDayOfWeek()) {
                                    current = current.with(nextOrSame(end.getDayOfWeek()));
                                    if (current.isAfter(interval.start().toLocalDate())) {
                                        current = current.minusWeeks(1);
                                    }
                                }
                                while (current.isBefore(end.toLocalDate())) {
                                    val point = new JsonList();
                                    val calc = new Calculator<>(Currency.math);
                                    val week = new DateTimeInterval(current, current.plusWeeks(1).minusDays(1));
                                    Locator.forEach(finalOQ.and(Opportunity.isSold.and(soldInInterval(week))),
                                            o -> calc.accept(o.getAmount()));

                                    point.add(Json.format(current));
                                    val stats = calc.getStats();
                                    point.add(stats.n);
                                    point.add(new JsonFloat(stats.sum()));
                                    point.add(Locator.count(finalOQ.and(createdInInterval(week))));
                                    val finalCQinInterval = finalCQ.and(Call.inInterval(week));
                                    point.add(Locator.count(finalCQinInterval));
                                    point.add(Locator.countDistinct(finalCQinInterval, "callerId_number"));
                                    point.add(newCallers.subSet(week.start(), week.end()).size());

                                    days.add(point);
                                    current = current.plusWeeks(1);
                                    progressMeter.increment();
                                }
                                cache.set(cacheKey, days);
                                return days;
                            });

        } else {
            log.debug("Returning cached report result for %s", cacheKey);
            response.setContentType(JSON.toString());
            try (val writer = response.getWriter()) {
                writer.write(cached);
                writer.flush();
            }
        }
    }

    @Override
    public void init(final ServletConfig config)
            throws ServletException {
        super.init(config);
        cache = new RedisJsonCache(config.getServletName());
    }
}
