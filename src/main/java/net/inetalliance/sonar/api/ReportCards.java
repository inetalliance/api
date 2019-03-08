package net.inetalliance.sonar.api;

import static com.callgrove.obj.Opportunity.soldInInterval;
import static com.callgrove.obj.Opportunity.withProductLine;
import static net.inetalliance.potion.Locator.$$;
import static net.inetalliance.potion.Locator.count;
import static net.inetalliance.potion.Locator.forEach;
import static net.inetalliance.sql.OrderBy.Direction.ASCENDING;
import static net.inetalliance.types.Currency.ZERO;
import static net.inetalliance.types.util.LocalizedMessages.$M;

import com.callgrove.obj.Agent;
import com.callgrove.obj.Call;
import com.callgrove.obj.DailyPerformance;
import com.callgrove.obj.DailyRank;
import com.callgrove.obj.Opportunity;
import com.callgrove.obj.ProductLine;
import com.callgrove.obj.report.ReportCard;
import com.callgrove.obj.report.ReportCardData;
import com.callgrove.reports.Report;
import com.callgrove.types.CallDirection;
import java.util.EnumSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.ForbiddenException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.angular.exception.UnauthorizedException;
import net.inetalliance.funky.StringFun;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sql.Aggregate;
import net.inetalliance.types.Currency;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.DateMidnight;
import org.joda.time.Interval;
import org.joda.time.format.DateTimeFormat;

@WebServlet("/api/reportCard/*")
public class ReportCards
    extends AngularServlet {

  private static Pattern pattern = Pattern.compile("/api/reportCard(?:/(\\d\\d\\d\\d))?");

  public ReportCards() {
    super();
  }

  private Agent getAgent(final HttpServletRequest request) {
    final Matcher matcher = pattern.matcher(request.getRequestURI());
    return matcher.matches() && matcher.group(1) != null
        ? Locator.$(new Agent(matcher.group(1)))
        : Startup.getAgent(request);
  }

  protected void get(final HttpServletRequest request, final HttpServletResponse response)
      throws Exception {

    final Agent loggedIn = Startup.getAgent(request);
    final Agent agent = getAgent(request);
    if (agent == null) {
      throw new NotFoundException();
    }
    if (loggedIn == null) {
      throw new ForbiddenException();
    }
    if (!agent.equals(loggedIn) && !loggedIn.getViewableAgents().contains(agent)) {
      throw new UnauthorizedException("%s %s attempted to view the report card of %s %s",
          loggedIn.getFirstName(),
          loggedIn.getLastName(), agent.getFirstName(), agent.getLastName());
    }
    final Report<ReportCard> report = new Report<>(
        $M(request.getLocale(), "reportCardForAgent", agent.getLastNameFirstInitial(),
            DateTimeFormat.shortDate().print(new DateMidnight())), ReportCard.class);
    forEach(Query.all(ProductLine.class), productLine -> {
      final Integer cycle = productLine.getCycleDays();
      if (cycle != null) {
        DateMidnight end = new DateMidnight();
        final ReportCard reportCard = new ReportCard();
        reportCard.cycleDays = cycle;
        for (int i = 0; i < 3; i++) {
          final DateMidnight start = end.minusDays(cycle);
          final Interval interval = new Interval(start, end);
          final DailyPerformance total = new DailyPerformance(null, productLine, null);
          final DailyPerformance[] last = new DailyPerformance[1];
          forEach(DailyPerformance.withAgent(agent)
              .and(DailyPerformance.inInterval(interval)
                  .and(DailyPerformance.withProductLine(productLine)))
              .orderBy("day", ASCENDING), arg -> {
            if (last[0] == null || arg.day.isAfter(last[0].day)) {
              last[0] = arg;
            }
            total.add(arg);
          });

          final ReportCardData data = reportCard.getData(i);
          reportCard.productLine = productLine.getName();
          final int qCalls = total.getQueueCalls();
          final int tCalls = qCalls + total.getOutboundCalls();
          data.setQueueRatio(qCalls == 0 ? 0 : 100.0D * total.getCloses() / total.getQueueCalls());
          data.setTotalRatio(
              tCalls == 0 ? 0 : 100.0D * total.getCloses() / (total.getQueueCalls() + total
                  .getOutboundCalls()));
          if (last[0] != null) {
            final DailyRank cycle1 = last[0].getCycle1();
            if (cycle1 != null) {
              final Integer size = cycle1.getPoolSize();
              data.setRank1(size - cycle1.getRank());
              data.setPool1(size);
            }
            final DailyRank cycle2 = last[0].getCycle2();
            if (cycle2 != null) {
              final Integer size = cycle2.getPoolSize();
              data.setRank2(size - cycle2.getRank());
              data.setPool2(size);
            }
            final DailyRank cycle3 = last[0].getCycle3();
            if (cycle3 != null) {
              final Integer size = cycle3.getPoolSize();
              data.setRank3(size - cycle3.getRank());
              data.setPool3(size);
            }
            final DailyRank cycle4 = last[0].getCycle4();
            if (cycle4 != null) {
              final Integer size = cycle4.getPoolSize();
              data.setRank4(size - cycle4.getRank());
              data.setPool4(size);
            }
            final DailyRank cycle5 = last[0].getCycle5();
            if (cycle5 != null) {
              final Integer size = cycle5.getPoolSize();
              data.setRank5(size - cycle5.getRank());
              data.setPool5(size);
            }
          }
          data.setDPC(qCalls == 0 ? ZERO : total.getSales().divide(total.getQueueCalls()));
          data.setQueueCalls(total.getQueueCalls());
          data.setOutboundCalls(total.getOutboundCalls());
          data.setDumps(total.getDumps());
          data.setDumpRatio(qCalls == 0 ? 0 : 100.0D * total.getDumps() / total.getQueueCalls());
          data.setSales(total.getCloses());
          data.setTotal(total.getSales());
          final int closes = total.getCloses();
          data.setMu(closes == 0 ? ZERO : total.getSales().divide(total.getCloses()));
          data.setSigma(new Currency(total.getStats().stdDeviation));
          end = start;
        }
        if (reportCard.hasData()) {
          report.records.add(reportCard);
        }
      }
    });
    final JsonMap json = report.toJson();
    json.remove("title");
    json.remove("record");
    EnumSet.of(CallDirection.QUEUE, CallDirection.OUTBOUND)
        .forEach(dir -> json.put(StringFun.enumToCamelCase(dir), count(
            Call.withAgent(agent).and(Call.createdAfter(new DateMidnight()))
                .and(Call.withDirection(dir)))));
    final JsonList sales = new JsonList();
    json.put("sales", sales);
    final Interval thisMonth = new Interval(new DateMidnight().withDayOfMonth(1),
        new DateMidnight().plusDays(1));
    final double[] total = {0, 0};
    forEach(Query.all(ProductLine.class), productLine -> {
      final Query<Opportunity> saleQuery =
          Opportunity.withAgent(agent).and(soldInInterval(thisMonth))
              .and(withProductLine(productLine));
      final int closes = Locator.count(saleQuery);
      if (closes > 0) {
        final Currency productLineSales = $$(saleQuery, Aggregate.SUM, Currency.class, "amount");
        if (productLineSales != null && productLineSales.greaterThan(Currency.ZERO)) {
          sales.add(new JsonMap().$("product", productLine.getName())
              .$("total", productLineSales.doubleValue())
              .$("closes", closes));
          total[0] += productLineSales.doubleValue();
          total[1] += closes;
        }
      }
    });

    sales.add(new JsonMap().$("product", "Total").$("total", total[0]).$("closes", total[1]));
    respond(response, json);
  }
}
