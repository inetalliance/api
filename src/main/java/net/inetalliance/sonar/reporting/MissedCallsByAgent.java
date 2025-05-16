package net.inetalliance.sonar.reporting;

import com.ameriglide.phenix.core.Iterables;
import com.ameriglide.phenix.core.Log;
import com.callgrove.obj.*;
import com.callgrove.types.ContactType;
import com.callgrove.types.SaleSource;
import jakarta.servlet.annotation.WebServlet;
import lombok.val;
import net.inetalliance.angular.exception.UnauthorizedException;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sql.DateTimeInterval;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.types.json.JsonString;
import net.inetalliance.util.ProgressMeter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.callgrove.Callgrove.getReportingInterval;
import static java.util.stream.Collectors.toSet;
import static net.inetalliance.potion.Locator.*;
import static net.inetalliance.sonar.reporting.ProductLineClosing.getQueues;

@WebServlet({"/reporting/reports/missedCallsByAgent"})
public class MissedCallsByAgent
        extends CachedGroupingRangeReport<Agent, Site> {

    private static final Log log = new Log();
    private final Info<Site> info;

    public MissedCallsByAgent() {
        super("site", "productLine");
        info = Info.$(Site.class);
    }

    @Override
    protected String getGroupLabel(final Site group) {
        return group.getName();
    }

    @Override
    protected String getId(final Agent row) {
        return row.key;
    }

    @Override
    protected Query<Agent> allRows(final Set<Site> groups, final Agent loggedIn,
                                   final LocalDateTime intervalStart) {
        return loggedIn.getViewableAgentsQuery(false).and(Agent.activeAfter(intervalStart))
                .and(Agent.isSales);
    }

    @Override
    protected Site getGroup(final String[] params, final String key) {
        return info.lookup(key);
    }

    @Override
    protected int getJobSize(final Agent loggedIn, final Set<Site> groups,
                             final DateTimeInterval interval) {
        var queueInInterval = Call.isQueue.and(Call.inInterval(interval));
        return count(groups.isEmpty() ? queueInInterval :
                queueInInterval.and(Call.withSiteIn(groups)));
    }

    @Override
    protected JsonMap generate(final Set<SaleSource> sources,
                               final Set<ContactType> contactTypes,
                               final Agent loggedIn, final ProgressMeter meter,
                               final LocalDate start,
                               final LocalDate end,
                               final Set<Site> sites, Collection<CallCenter> callCenters,
                               final Map<String, String[]> extras) {
        if (loggedIn == null || !(loggedIn.isManager() || loggedIn.isTeamLeader())) {
            log.warn(() -> "%s tried to access closing report data".formatted(
                    loggedIn == null ? "Nobody?" : loggedIn.key));
            throw new UnauthorizedException();
        }
        val productLineIds = extras.get("productLine");
        final Set<ProductLine> productLines = productLineIds == null ?
                Collections.emptySet() : Arrays.stream(productLineIds)
                .map(id -> Locator.$(new ProductLine(Integer.valueOf(id))))
                .collect(toSet());

        val interval = getReportingInterval(start, end);

        var callQuery = Call.isQueue.and(Call.inInterval(interval));

        if (!productLines.isEmpty()) {
            callQuery = callQuery.and(Call.withQueueIn(
                    productLines.stream()
                            .map(pl -> getQueues(loggedIn, pl, sites))
                            .flatMap(Iterables::stream)
                            .collect(toSet())));
        }
        if (!sites.isEmpty()) {
            callQuery = callQuery.and(Call.withSiteIn(sites));
        }

        val finalCallQuery = callQuery;

        val rows = new JsonList();
        val totalCalls = new AtomicInteger(0);
        var agentQuery = allRows(sites, loggedIn, interval.start());
        if (!callCenters.isEmpty()) {
            agentQuery = agentQuery.and(Agent.withCallCenters(callCenters));
        }
        var allAgents = Locator.$$(agentQuery);

        var misses = new HashMap<String, Integer>();
        var total = new HashMap<String, Integer>();

        forEach(callQuery.and(Call.missed), call -> {
            meter.increment();
            forEach(Segment.withCall(call), segment -> {
                val agent = segment.getAgent();
                if (allAgents.contains(agent)) {
                    misses.put(agent.key, misses.getOrDefault(agent.key, 0) + 1);
                }
            });
        });

        var missesBusinessHours = new HashMap<String, Integer>();
        forEach(callQuery.and(Call.missed).and(Call.isBusinessHours), call -> {
            meter.increment();
            forEach(Segment.withCall(call), segment -> {
                val agent = segment.getAgent();
                if (allAgents.contains(agent)) {
                    missesBusinessHours.put(agent.key,
                            missesBusinessHours.getOrDefault(agent.key, 0) + 1);
                }
            });
        });
        allAgents.forEach(a -> total.put(a.key, Locator.count(finalCallQuery.and(Call.withAgent(a).and(Call.isAnswered)))));
        allAgents.forEach(agent -> rows.add(new JsonMap()
                .$("key", agent.key)
                .$("agent", agent.getFullName())
                .$("missedCalls", misses.getOrDefault(agent.key, 0))
                .$("missedCallsBusinessHours", missesBusinessHours.getOrDefault(agent.key, 0))
                .$("total", total.getOrDefault(agent.key, 0))));

        return new JsonMap()
                .$("rows", rows)
                .$("times",
                        $$(finalCallQuery.and(Call.missed))
                                .stream()
                                .map(Call::getDate)
                                .map(Json::format)
                                .map(JsonString::new)
                                .collect(JsonList.collect))
                .$("total", totalCalls.get());

    }

}
