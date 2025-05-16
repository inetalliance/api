package net.inetalliance.sonar.api;

import com.ameriglide.phenix.core.DateTimeFormats;
import com.ameriglide.phenix.core.Enums;
import com.callgrove.obj.*;
import com.callgrove.obj.report.ReportCard;
import com.callgrove.reports.Report;
import com.callgrove.types.CallDirection;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.val;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.ForbiddenException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.angular.exception.UnauthorizedException;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sql.Aggregate;
import net.inetalliance.sql.DateTimeInterval;
import net.inetalliance.types.Currency;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.regex.Pattern;

import static com.callgrove.obj.Opportunity.soldInInterval;
import static com.callgrove.obj.Opportunity.withProductLine;
import static java.time.format.FormatStyle.SHORT;
import static net.inetalliance.potion.Locator.*;
import static net.inetalliance.sql.OrderBy.Direction.ASCENDING;
import static net.inetalliance.types.Currency.ZERO;
import static net.inetalliance.types.LocalizedMessages.$M;

@WebServlet("/api/reportCard/*")
public class ReportCards
        extends AngularServlet {

    private static final Pattern pattern = Pattern.compile("/api/reportCard(?:/(\\d\\d\\d\\d))?");

    public ReportCards() {
        super();
    }

    private Agent getAgent(final HttpServletRequest request) {
        val matcher = pattern.matcher(request.getRequestURI());
        return matcher.matches() && matcher.group(1) != null
                ? Locator.$(new Agent(matcher.group(1)))
                : Startup.getAgent(request);
    }

    protected void get(final HttpServletRequest request, final HttpServletResponse response)
            throws Exception {

        val loggedIn = Startup.getAgent(request);
        val agent = getAgent(request);
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
        val report = new Report<>(
                $M(request.getLocale(), "reportCardForAgent", agent.getLastNameFirstInitial(),
                        DateTimeFormats.ofDate(SHORT).format(LocalDate.now())), ReportCard.class);
        forEach(Query.all(ProductLine.class), productLine -> {
            val cycle = productLine.getCycleDays();
            if (cycle != null) {
                var end = LocalDate.now();
                val reportCard = new ReportCard();
                reportCard.cycleDays = cycle;
                for (var i = 0; i < 3; i++) {
                    var start = end.minusDays(cycle);
                    val interval = new DateTimeInterval(start, end);
                    val total = new DailyPerformance(null, productLine, null);
                    val last = new DailyPerformance[1];
                    forEach(DailyPerformance.withAgent(agent)
                            .and(DailyPerformance.inInterval(interval.toDateInterval())
                                    .and(DailyPerformance.withProductLine(productLine)))
                            .orderBy("day", ASCENDING), arg -> {
                        if (last[0] == null || arg.day.isAfter(last[0].day)) {
                            last[0] = arg;
                        }
                        total.add(arg);
                    });

                    val data = reportCard.getData(i);
                    reportCard.productLine = productLine.getName();
                    val qCalls = total.getQueueCalls();
                    val tCalls = qCalls + total.getOutboundCalls();
                    data.setQueueRatio(qCalls == 0 ? 0 : 100.0D * total.getCloses() / total.getQueueCalls());
                    data.setTotalRatio(
                            tCalls == 0 ? 0 : 100.0D * total.getCloses() / (total.getQueueCalls() + total
                                    .getOutboundCalls()));
                    if (last[0] != null) {
                        val cycle1 = last[0].getCycle1();
                        if (cycle1 != null) {
                            val size = cycle1.getPoolSize();
                            data.setRank1(size - cycle1.getRank());
                            data.setPool1(size);
                        }
                        val cycle2 = last[0].getCycle2();
                        if (cycle2 != null) {
                            val size = cycle2.getPoolSize();
                            data.setRank2(size - cycle2.getRank());
                            data.setPool2(size);
                        }
                        val cycle3 = last[0].getCycle3();
                        if (cycle3 != null) {
                            val size = cycle3.getPoolSize();
                            data.setRank3(size - cycle3.getRank());
                            data.setPool3(size);
                        }
                        val cycle4 = last[0].getCycle4();
                        if (cycle4 != null) {
                            val size = cycle4.getPoolSize();
                            data.setRank4(size - cycle4.getRank());
                            data.setPool4(size);
                        }
                        val cycle5 = last[0].getCycle5();
                        if (cycle5 != null) {
                            val size = cycle5.getPoolSize();
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
                    val closes = total.getCloses();
                    data.setMu(closes == 0 ? ZERO : total.getSales().divide(total.getCloses()));
                    data.setSigma(new Currency(total.getStats().stdDeviation));
                    end = start;
                }
                if (reportCard.hasData()) {
                    report.records.add(reportCard);
                }
            }
        });
        val json = report.toJson();
        json.remove("title");
        json.remove("record");
        EnumSet.of(CallDirection.QUEUE, CallDirection.OUTBOUND)
                .forEach(dir -> json.put(Enums.camel(dir), count(
                        Call.withAgent(agent).and(Call.createdAfter(LocalDate.now()))
                                .and(Call.withDirection(dir)))));
        val sales = new JsonList();
        json.put("sales", sales);
        val thisMonth = new DateTimeInterval(LocalDate.now().withDayOfMonth(1),
                LocalDate.now().plusDays(1));
        final double[] total = {0, 0};
        forEach(Query.all(ProductLine.class), productLine -> {
            val saleQuery =
                    Opportunity.withAgent(agent).and(soldInInterval(thisMonth))
                            .and(withProductLine(productLine));
            val closes = Locator.count(saleQuery);
            if (closes > 0) {
                val productLineSales = $$(saleQuery, Aggregate.SUM, Currency.class, "amount");
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
