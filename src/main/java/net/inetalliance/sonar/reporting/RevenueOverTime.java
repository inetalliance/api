package net.inetalliance.sonar.reporting;

import com.ameriglide.phenix.core.Calculator;
import com.ameriglide.phenix.core.Enums;
import com.ameriglide.phenix.core.Iterables;
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
import net.inetalliance.angular.exception.ForbiddenException;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.cache.RedisJsonCache;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sql.DateTimeInterval;
import net.inetalliance.types.Currency;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.types.json.JsonString;
import net.inetalliance.types.localized.LocalizedString;

import java.util.*;
import java.util.stream.Collectors;

import static com.ameriglide.phenix.core.Strings.isEmpty;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.SUNDAY;
import static java.time.temporal.TemporalAdjusters.nextOrSame;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static net.inetalliance.sonar.reporting.ProductLineClosing.getQueues;
import static net.inetalliance.types.www.ContentType.JSON;

@WebServlet({"/reporting/reports/revenueOverTime"})
public class RevenueOverTime
        extends AngularServlet {

    private static final Log log = new Log();
    private RedisJsonCache cache;

    public RevenueOverTime() {
    }

    @Override
    public void init(final ServletConfig config)
            throws ServletException {
        super.init(config);
        cache = new RedisJsonCache(getClass().getSimpleName());
    }

    @Override
    protected void get(HttpServletRequest request, HttpServletResponse response)
            throws Exception {
        val authorized = Auth.getAuthorized(request);
        if (authorized == null || !authorized.isAuthorized("reports")) {
            throw new ForbiddenException("You do not have access to reports");
        }
        val loggedIn = Locator.$(new Agent(authorized.getPhone()));
        var interval = Callgrove.getInterval(request);
        var start = interval.start().toLocalDate().with(nextOrSame(MONDAY));
        var end = interval.end().toLocalDate().with(nextOrSame(SUNDAY));
        val mode = request.getParameterValues("mode");
        val siteStrings = request.getParameterValues("site");
        val productLineStrings = request.getParameterValues("product");

        Collection<Integer> sites;
        Collection<ProductLine> productLines;

        try {
            sites = siteStrings == null ? null : Arrays
                    .stream(siteStrings)
                    .map(Integer::parseInt)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            return;
        }

        try {
            productLines = productLineStrings == null ? null : Arrays
                    .stream(productLineStrings)
                    .map(Integer::parseInt)
                    .map(ProductLine::new)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            return;
        }

        val q =
                String.format("report:%s,start:%s,end:%s,mode:%s,site:%s,product:%s",
                        getClass().getSimpleName(),
                        Callgrove.simple.format(start),
                        Callgrove.simple.format(end),
                        mode == null ? "" : String.join(",", mode),
                        sites == null ? null : sites
                                .stream()
                                .map(Object::toString)
                                .reduce((s1, s2) -> s1 + "|" + s2)
                                .orElse(null),
                        productLines == null ? null : productLines
                                .stream()
                                .map(Object::toString)
                                .reduce((s1, s2) -> s1 + "|" + s2)
                                .orElse(null));

        val cached = cache.get(q);
        if (isEmpty(cached)) {
            var sources = getApplicableSources(mode);

            var query = Opportunity.isSold
                    .and(Opportunity.soldInInterval(new DateTimeInterval(start.minusWeeks(8), end)));
            var callQuery = Call.isQueue;

            if (!sources.isEmpty()) {
                query = query.and(Opportunity.withSources(sources));
                callQuery = callQuery.and(Call.withSourceIn(sources));
            }

            if (sites != null) {
                query = query.and(Opportunity.withSiteIdIn(sites));
                callQuery = callQuery.and(Call.withSiteIdIn(sites));
            }
            if (productLines != null) {
                query = query.and(Opportunity.withProductLineIn(productLines));

                val siteObjects = sites == null ? null : sites.stream()
                        .map(id -> Locator.$(new Site(id))).collect(toSet());
                callQuery = callQuery.and(Call.withQueueIn(
                        productLines.stream()
                                .map(pl -> getQueues(loggedIn, pl, siteObjects))
                                .flatMap(Iterables::stream)
                                .collect(toSet())));
            }
            val opportunities = Locator.$$(query);
            val rows = new JsonList();

            // we start back 8 weeks to get the moving average for our start date
            for (var date = start.minusWeeks(8); !date.plusWeeks(1).isAfter(end);
                 date = date.plusWeeks(1)) {
                val week = new DateTimeInterval(date, date.plusWeeks(1));
                rows.add(
                        new JsonMap()
                                .$("date", date)
                                .$("missedCalls",
                                        Locator
                                                .count(callQuery
                                                        .and(Call.missed)
                                                        .and(Call.isBusinessHours)
                                                        .and(Call.inInterval(week))))
                                .$("calls", Locator.count(callQuery.and(Call.inInterval(week))))
                                .$("revenue",
                                        opportunities
                                                .stream()
                                                .filter(Opportunity.soldInInterval(week))
                                                .map(Opportunity::getAmount)
                                                .reduce(Currency.ZERO, Currency::add)
                                                .doubleValue()));
            }

            // calculate averages
            final List<Double> lastTwo = new ArrayList<>(2);
            final List<Double> lastFour = new ArrayList<>(4);
            final List<Double> lastEight = new ArrayList<>(8);
            for (var json : rows) {
                var row = (JsonMap) json;
                val revenue = row.getDouble("revenue");
                lastTwo.add(revenue);
                lastFour.add(revenue);
                lastEight.add(revenue);
                if (lastTwo.size() == 2) {
                    val calculator = Calculator.newDouble();
                    lastTwo.forEach(calculator);
                    row.$("week2", calculator.m);
                    lastTwo.removeFirst();
                }
                if (lastFour.size() == 4) {
                    val calculator = Calculator.newDouble();
                    lastFour.forEach(calculator);
                    row.$("week4", calculator.m);
                    lastFour.removeFirst();
                }
                if (lastEight.size() == 8) {
                    val calculator = Calculator.newDouble();
                    lastEight.forEach(calculator);
                    row.$("week8", calculator.m);
                    lastEight.removeFirst();
                }
            }

            // remove the first eight entries because they are before our start date
            // note that we can't just chop 8 entries off the front of the list because
            // we aren't 100% sure we have data before the start date

            val iterator = rows.iterator();
            while (iterator.hasNext()) {
                var row = (JsonMap) iterator.next();
                if (row.getDate("date").isBefore(start)) {
                    iterator.remove();
                } else {
                    break;
                }
            }

            final Collection<Query<? super ProductLine>> productLineQueries =
                    productLines == null ? null : productLines
                            .stream()
                            .map(ProductLine::getId)
                            .map(ProductLine::withId)
                            .collect(toList());
            val productLineNames = productLineQueries == null ? null
                    : Locator.$$(Query.or(ProductLine.class, productLineQueries))
                    .stream()
                    .map(ProductLine::getName)
                    .map(JsonString::new)
                    .collect(JsonList.collect);

            final Collection<Query<? super Site>> siteQueries =
                    sites == null ? null : sites
                            .stream()
                            .map(Site::withId)
                            .collect(toList());
            val siteAbbreviations = siteQueries == null ? null
                    : Locator.$$(Query.or(Site.class, siteQueries))
                    .stream()
                    .map(Site::getAbbreviation)
                    .map(Object::toString)
                    .map(JsonString::new)
                    .collect(JsonList.collect);

            val result = new JsonMap()
                    .$("rows", rows)
                    .$("sources", sources
                            .stream()
                            .map(SaleSource::getLocalizedName)
                            .map(LocalizedString::toString)
                            .map(JsonString::new)
                            .collect(JsonList.collect))
                    .$("productLines", productLineNames)
                    .$("sites", siteAbbreviations);

            cache.set(q, result);
            respond(response, result);
        } else {
            log.debug("Returning cached report result for %s", q);
            response.setContentType(JSON.toString());
            try (val writer = response.getWriter()) {
                writer.write(cached);
                writer.flush();
            }
        }
    }

    static Set<SaleSource> getApplicableSources(String[] mode) {
        return mode == null || mode.length == 0 || (mode.length == 1 && "all".equals(mode[0]))
                        ? EnumSet.noneOf(SaleSource.class)
                        : Arrays.stream(mode)
                        .map(s -> Enums.decamel(SaleSource.class, s))
                        .collect(Collectors.toCollection(() -> EnumSet.noneOf(SaleSource.class)));
    }
}
