package net.inetalliance.sonar.reporting;

import com.ameriglide.phenix.core.Enums;
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
import net.inetalliance.types.Currency;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.util.ProgressMeter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.ameriglide.phenix.core.Strings.isEmpty;
import static com.callgrove.Callgrove.getReportingInterval;
import static com.callgrove.obj.Opportunity.*;
import static com.callgrove.types.SaleSource.PHONE_CALL;
import static com.callgrove.types.SaleSource.SURVEY;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toSet;
import static net.inetalliance.potion.Locator.*;
import static net.inetalliance.sql.Aggregate.SUM;
import static net.inetalliance.types.Currency.ZERO;

@WebServlet({"/reporting/reports/surveyClosing"})
public class SurveyClosing
        extends CachedGroupingRangeReport<Agent, Site> {

    private static final Log log = new Log();
    private final Info<Site> info;

    public SurveyClosing() {
        super("site", "productLine");
        this.info = Info.$(Site.class);
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
        return loggedIn.getViewableAgentsQuery().and(Agent.activeAfter(intervalStart))
                .and(Agent.isSales);
    }

    @Override
    protected Site getGroup(final String[] params, final String key) {
        return info.lookup(key);
    }

    @Override
    protected int getJobSize(final Agent loggedIn, final Set<Site> groups,
                             final DateTimeInterval interval) {
        return count(allRows(groups, loggedIn, interval.start()));
    }

    @Override
    protected JsonMap generate(final Set<SaleSource> sources,
                               final Set<ContactType> contactTypes,
                               final Agent loggedIn, final ProgressMeter meter, final LocalDate start,
                               final LocalDate end,
                               final Set<Site> sites, Collection<CallCenter> callCenters,
                               final Map<String, String[]> extras) {
        var productLines = guard(loggedIn, extras, log);
        val interval = getReportingInterval(start, end);

        val visibleSites = loggedIn.getVisibleSites();
        if (sites != null && !sites.isEmpty()) {
            for (val site : sites) {
                if (!visibleSites.contains(site)) {
                    log.warn(() -> "%s tried to access closing data for site %d".formatted(loggedIn.key, site.id));
                    throw new UnauthorizedException();
                }
            }
        }

        var hasSite = sites != null && !sites.isEmpty();

        final Query<Opportunity> base;
        if (productLines.isEmpty()) {
            productLines.addAll(Locator.$A(ProductLine.class));
            if (hasSite) {
                base = withSiteIn(sites);
            } else {
                base = Query.all(Opportunity.class);
            }
        } else {
            if (hasSite) {
                base = withProductLineIn(productLines).and(withSiteIn(sites));
            } else {
                base = withProductLineIn(productLines);
            }
        }

        final Set<String> queuesForProductLine =
                productLines.stream().map(pl -> ProductLineClosing.getQueues(loggedIn, pl, sites)).flatMap(
                        Iterables::stream).collect(toSet());
        val rows = new JsonList();
        forEach(allRows(sites, loggedIn, interval.start()), agent -> {
            val andAgent = base.and(Opportunity.withAgent(agent));
            val row = new JsonMap().$("agent", agent.getLastNameFirstInitial());
            val hasOpps = new AtomicBoolean(false);
            EnumSet.of(SURVEY, PHONE_CALL).forEach(source -> {
                val andSource = andAgent.and(withSaleSource(source));
                val andSoldInInterval = andSource.and(soldInInterval(interval));
                val count = count(andSource.and(createdInInterval(interval)));
                if (count > 0) {
                    hasOpps.set(true);
                }
                row.$(Enums.camel(source), new JsonMap().$("count", count)
                        .$("closes", count(andSoldInInterval))
                        .$("total",
                                (ofNullable($$(andSoldInInterval, SUM, Currency.class,
                                        "amount"))
                                        .orElse(ZERO)).doubleValue()));
            });
            row.$("calls", queuesForProductLine.isEmpty()
                    ? 0
                    : count(Call.inInterval(interval)
                    .and(Call.isQueue)
                    .and(Call.withQueueIn(queuesForProductLine))
                    .and(Call.withBlame(agent))));

            meter.increment(agent.getLastNameFirstInitial());
            if (hasOpps.get()) {
                rows.add(row);
            }
        });
        return new JsonMap().$("rows", rows);

    }

    static Set<ProductLine> guard(Agent loggedIn, Map<String, String[]> extras, Log log) {
        if (loggedIn == null || !(loggedIn.isManager() || loggedIn.isTeamLeader())) {
            log.warn(() -> "%s tried to access closing report data".formatted(
                    loggedIn == null ? "Nobody?" : loggedIn.key));
            throw new UnauthorizedException();
        }
        val productLineIds = extras.get("productLine");
        if (productLineIds == null || productLineIds.length == 0 || isEmpty(productLineIds[0])) {
            return new HashSet<>();
        } else {
            return Arrays.stream(productLineIds)
                    .map(id -> Locator.$(new ProductLine(Integer.valueOf(id)))).collect(toSet());
        }
    }
}
