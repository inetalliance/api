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
import net.inetalliance.types.geopolitical.us.State;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.util.ProgressMeter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.callgrove.Callgrove.getReportingInterval;
import static com.callgrove.obj.Opportunity.*;
import static com.callgrove.types.SaleSource.PHONE_CALL;
import static com.callgrove.types.SaleSource.SURVEY;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toSet;
import static net.inetalliance.potion.Locator.$$;
import static net.inetalliance.potion.Locator.count;
import static net.inetalliance.sql.Aggregate.SUM;
import static net.inetalliance.types.Currency.ZERO;

@WebServlet({"/reporting/reports/stateClosing"})
public class StateClosing
        extends CachedGroupingRangeReport<State, Site> {

    private static final Log log = new Log();
    private final Info<Site> info;

    public StateClosing() {
        super("site", "productLine");
        this.info = Info.$(Site.class);
    }

    @Override
    protected String getGroupLabel(final Site group) {
        return group.getName();
    }

    @Override
    protected String getId(final State row) {
        return row.name();
    }

    @Override
    protected Query<State> allRows(final Set<Site> groups, final Agent loggedIn,
                                   final LocalDateTime intervalStart) {
        throw new IllegalStateException();
    }

    @Override
    protected Site getGroup(final String[] params, final String key) {
        return info.lookup(key);
    }

    @Override
    protected int getJobSize(final Agent loggedIn, final Set<Site> groups,
                             final DateTimeInterval interval) {
        return State.values().length;
    }

    @Override
    protected JsonMap generate(final Set<SaleSource> sources,
                               final Set<ContactType> contactTypes,
                               final Agent loggedIn, final ProgressMeter meter, final LocalDate start,
                               final LocalDate end,
                               final Set<Site> sites, Collection<CallCenter> callCenters,
                               final Map<String, String[]> extras) {
        var productLines = SurveyClosing.guard(loggedIn, extras, log);
        var interval = getReportingInterval(start, end);

        val visibleSites = loggedIn.getVisibleSites();
        if (sites != null && !sites.isEmpty()) {
            for (val site : sites) {
                if (!visibleSites.contains(site)) {
                    log.warn(()->"%s tried to access closing data for site %d".formatted(loggedIn.key, site.id));
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
        var states = new ArrayList<State>(State.values().length + 1);
        states.addAll(Arrays.asList(State.values()));
        states.add(null);
        states.forEach(state -> {
            val andAgent = base.and(Opportunity.withState(state));
            val row = new JsonMap().$("state", state == null ? "None" : state.getAbbreviation());
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
                    .and(Call.withState(state))));

            meter.increment(state == null ? "None" : state.getAbbreviation());
            if (hasOpps.get()) {
                rows.add(row);
            }
        });
        return new JsonMap().$("rows", rows);

    }
}
