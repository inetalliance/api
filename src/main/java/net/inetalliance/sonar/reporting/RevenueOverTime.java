package net.inetalliance.sonar.reporting;

import static net.inetalliance.types.www.ContentType.JSON;

import com.callgrove.Callgrove;
import com.callgrove.obj.Call;
import com.callgrove.obj.Opportunity;
import com.callgrove.obj.ProductLine;
import com.callgrove.obj.Site;
import com.callgrove.types.SaleSource;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.funky.StringFun;
import net.inetalliance.funky.math.Calculator;
import net.inetalliance.log.Log;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.cache.RedisJsonCache;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.Currency;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.types.json.JsonString;
import net.inetalliance.types.localized.LocalizedString;
import org.joda.time.DateMidnight;
import org.joda.time.Interval;

@WebServlet({"/reporting/reports/revenueOverTime"})
public class RevenueOverTime
    extends AngularServlet {

  private static final transient Log log = Log.getInstance(RevenueOverTime.class);
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

    final Interval interval = Callgrove.getInterval(request);
    final DateMidnight start = interval.getStart().toDateMidnight().withDayOfWeek(1);
    final DateMidnight end = interval.getEnd().toDateMidnight().withDayOfWeek(7);
    final String[] mode = request.getParameterValues("mode");
    final String[] siteStrings = request.getParameterValues("site");
    final String[] productLineStrings = request.getParameterValues("product");

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

    final String q =
        String.format("report:%s,start:%s,end:%s,mode:%s,site:%s,product:%s",
            getClass().getSimpleName(),
            Callgrove.simple.print(start),
            Callgrove.simple.print(end),
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

    final String cached = cache.get(q);
    if (StringFun.isEmpty(cached)) {
      final EnumSet<SaleSource> sources =
          mode == null || mode.length == 0 || (mode.length == 1 && "all".equals(mode[0]))
              ? EnumSet.noneOf(SaleSource.class)
              : Arrays.stream(mode)
                  .map(s -> StringFun.camelCaseToEnum(SaleSource.class, s))
                  .collect(Collectors.toCollection(() -> EnumSet.noneOf(SaleSource.class)));

      Query<Opportunity> query =
          Opportunity.isSold
              .and(Opportunity.soldInInterval(new Interval(start.minusWeeks(8), end)));
      Query<Call> callQuery = Query.all(Call.class);

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
        callQuery = callQuery.and(Call.withProductLineIn(productLines));
      }
      final Set<Opportunity> opportunities = Locator.$$(query);
      final JsonList rows = new JsonList();

      // we start back 8 weeks to get the moving average for our start date
      for (DateMidnight date = start.minusWeeks(8); !date.plusWeeks(1).isAfter(end); date = date.plusWeeks(1)) {
        final Interval week = new Interval(date, date.plusWeeks(1));
        rows.add(
            new JsonMap()
                .$("date", date)
                .$("missedCalls",
                    Locator.count(Call.isAnswered.negate().and(Call.inInterval(week))))
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
      for (Json json : rows) {
        JsonMap row = (JsonMap) json;
        final Double revenue = row.getDouble("revenue");
        lastTwo.add(revenue);
        lastFour.add(revenue);
        lastEight.add(revenue);
        if (lastTwo.size() == 2) {
          final Calculator<Double> calculator = Calculator.newDouble();
          lastTwo.forEach(calculator);
          row.$("week2", calculator.m);
          lastTwo.remove(0);
        }
        if (lastFour.size() == 4) {
          final Calculator<Double> calculator = Calculator.newDouble();
          lastFour.forEach(calculator);
          row.$("week4", calculator.m);
          lastFour.remove(0);
        }
        if (lastEight.size() == 8) {
          final Calculator<Double> calculator = Calculator.newDouble();
          lastEight.forEach(calculator);
          row.$("week8", calculator.m);
          lastEight.remove(0);
        }
      }

      // remove first eight entries because they are before our start date
      // note that we can't just chop 8 entries off the front of the list, because
      // we aren't 100% sure we have data before the start date

      final Iterator<Json> iterator = rows.iterator();
      while (iterator.hasNext()) {
        JsonMap row = (JsonMap) iterator.next();
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
              .collect(Collectors.toList());
      final JsonList productLineNames = productLineQueries == null ? null
          : Locator.$$(Query.or(ProductLine.class, productLineQueries))
              .stream()
              .map(ProductLine::getName)
              .map(JsonString::new)
              .collect(JsonList.collect);

      final Collection<Query<? super Site>> siteQueries =
          sites == null ? null : sites
              .stream()
              .map(Site::withId)
              .collect(Collectors.toList());
      final JsonList siteAbbreviations = siteQueries == null ? null
          : Locator.$$(Query.or(Site.class, siteQueries))
              .stream()
              .map(Site::getAbbreviation)
              .map(Object::toString)
              .map(JsonString::new)
              .collect(JsonList.collect);

      final JsonMap result = new JsonMap()
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
      try (final PrintWriter writer = response.getWriter()) {
        writer.write(cached);
        writer.flush();
      }
    }
  }
}
