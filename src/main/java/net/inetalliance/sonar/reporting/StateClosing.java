package net.inetalliance.sonar.reporting;

import com.callgrove.obj.*;
import com.callgrove.types.ContactType;
import com.callgrove.types.SaleSource;
import net.inetalliance.angular.exception.UnauthorizedException;
import net.inetalliance.funky.Funky;
import net.inetalliance.log.Log;
import net.inetalliance.log.progress.ProgressMeter;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.Currency;
import net.inetalliance.types.geopolitical.us.State;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.DateMidnight;
import org.joda.time.DateTime;
import org.joda.time.Interval;

import javax.servlet.annotation.WebServlet;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static com.callgrove.Callgrove.getReportingInterval;
import static com.callgrove.obj.Opportunity.*;
import static com.callgrove.types.SaleSource.PHONE_CALL;
import static com.callgrove.types.SaleSource.SURVEY;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toSet;
import static net.inetalliance.funky.StringFun.enumToCamelCase;
import static net.inetalliance.funky.StringFun.isEmpty;
import static net.inetalliance.log.Log.getInstance;
import static net.inetalliance.potion.Locator.$$;
import static net.inetalliance.potion.Locator.count;
import static net.inetalliance.sql.Aggregate.SUM;
import static net.inetalliance.types.Currency.ZERO;

@WebServlet({"/reporting/reports/stateClosing"})
public class StateClosing
    extends CachedGroupingRangeReport<State, Site> {

  private static final transient Log log = getInstance(StateClosing.class);
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
      final DateTime intervalStart) {
      throw new IllegalStateException();
  }

  @Override
  protected Site getGroup(final String[] params, final String key) {
    return info.lookup(key);
  }

  @Override
  protected int getJobSize(final Agent loggedIn, final Set<Site> groups,
      final Interval interval) {
    return State.values().length;
  }

  @Override
  protected JsonMap generate(final EnumSet<SaleSource> sources,
      final EnumSet<ContactType> contactTypes,
      final Agent loggedIn, final ProgressMeter meter, final DateMidnight start,
      final DateMidnight end,
      final Set<Site> sites, Collection<CallCenter> callCenters,
      final Map<String, String[]> extras) {
    if (loggedIn == null || !(loggedIn.isManager() || loggedIn.isTeamLeader())) {
      log.warning("%s tried to access closing report data",
          loggedIn == null ? "Nobody?" : loggedIn.key);
      throw new UnauthorizedException();
    }
    final String[] productLineIds = extras.get("productLine");
    final Set<ProductLine> productLines;
    if (productLineIds == null || productLineIds.length == 0 || isEmpty(productLineIds[0])) {
      productLines = new HashSet<>();
    } else {
       productLines = Arrays.stream(productLineIds)
              .map(id -> Locator.$(new ProductLine(Integer.valueOf(id)))).collect(toSet());
    }
    final Interval interval = getReportingInterval(start, end);

    final Set<Site> visibleSites = loggedIn.getVisibleSites();
    if (sites != null && !sites.isEmpty()) {
      for (final Site site : sites) {
        if (!visibleSites.contains(site)) {
          log.warning("%s tried to access closing data for site %d", loggedIn.key, site.id);
          throw new UnauthorizedException();
        }
      }
    }

    boolean hasSite = sites != null && !sites.isEmpty();

    final Query<Opportunity>  base;
    if(productLines.isEmpty()) {
        productLines.addAll(Locator.$A(ProductLine.class));
      if(hasSite) {
        base = withSiteIn(sites);
      } else {
        base = Query.all(Opportunity.class);
      }
    } else {
      if(hasSite) {
        base = withProductLineIn(productLines).and(withSiteIn(sites));
      } else {
        base = withProductLineIn(productLines);
      }
    }

    final Set<String> queuesForProductLine =
        productLines.stream().map(pl -> ProductLineClosing.getQueues(loggedIn, pl, sites)).flatMap(
            Funky::stream).collect(toSet());
    final JsonList rows = new JsonList();
    Stream.of(State.values()).forEach(state -> {
      final Query<Opportunity> andAgent = base.and(Opportunity.withState(state));
      final JsonMap row = new JsonMap().$("state", state.getAbbreviation());
      final AtomicBoolean hasOpps = new AtomicBoolean(false);
      EnumSet.of(SURVEY, PHONE_CALL).forEach(source -> {
        final Query<Opportunity> andSource = andAgent.and(withSaleSource(source));
        final Query<Opportunity> andSoldInInterval = andSource.and(soldInInterval(interval));
        final int count = count(andSource.and(createdInInterval(interval)));
        if (count > 0) {
          hasOpps.set(true);
        }
        row.$(enumToCamelCase(source), new JsonMap().$("count", count)
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

      meter.increment(state.getAbbreviation());
      if (hasOpps.get()) {
        rows.add(row);
      }
    });
    return new JsonMap().$("rows", rows);

  }
}
