package net.inetalliance.sonar.reporting;

import static com.callgrove.obj.Call.inInterval;
import static com.callgrove.obj.Call.isQueue;
import static com.callgrove.obj.Opportunity.createdBefore;
import static com.callgrove.obj.Opportunity.createdInInterval;
import static com.callgrove.obj.Opportunity.isActive;
import static com.callgrove.obj.Opportunity.isDead;
import static com.callgrove.obj.Opportunity.withReferrer;
import static com.callgrove.obj.Opportunity.withSaleSource;
import static com.callgrove.obj.Queue.withAffiliate;
import static com.callgrove.types.SaleSource.ONLINE;
import static com.callgrove.types.SaleSource.REFERRAL;
import static net.inetalliance.potion.Locator.$1;
import static net.inetalliance.potion.Locator.count;
import static net.inetalliance.potion.Locator.countDistinct;
import static net.inetalliance.types.www.ContentType.JSON;

import com.callgrove.Callgrove;
import com.callgrove.obj.Affiliate;
import com.callgrove.obj.Call;
import com.callgrove.obj.Opportunity;
import com.callgrove.types.SaleSource;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.analyticsreporting.v4.AnalyticsReporting;
import com.google.api.services.analyticsreporting.v4.AnalyticsReportingScopes;
import com.google.api.services.analyticsreporting.v4.model.DateRange;
import com.google.api.services.analyticsreporting.v4.model.GetReportsRequest;
import com.google.api.services.analyticsreporting.v4.model.GetReportsResponse;
import com.google.api.services.analyticsreporting.v4.model.Metric;
import com.google.api.services.analyticsreporting.v4.model.ReportRequest;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.funky.StringFun;
import net.inetalliance.log.Log;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.cache.RedisJsonCache;
import net.inetalliance.sql.OrderBy.Direction;
import net.inetalliance.types.Currency;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.DateMidnight;
import org.joda.time.Interval;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

@WebServlet({"/reporting/reports/referrals"})
public class Referrals extends AngularServlet {

  private static final transient Log log = Log.getInstance(Referrals.class);
  private RedisJsonCache cache;

  public Referrals() {
  }

  @Override
  public void init(final ServletConfig config)
      throws ServletException {
    super.init(config);
    cache = new RedisJsonCache(getClass().getSimpleName());
  }

  private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

  private static final AnalyticsReporting analytics;

  static {

    try {

      HttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
      GoogleCredential credential = GoogleCredential
          .fromStream(Referrals.class.getResourceAsStream("/client-secrets.json"))
          .createScoped(AnalyticsReportingScopes.all());

      // Construct the Analytics Reporting service object.
      analytics = new AnalyticsReporting.Builder(httpTransport, JSON_FACTORY, credential)
          .setApplicationName("Sonar Reporting").build();
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  private int countVisitors(Affiliate affiliate, Interval interval) throws IOException {
    DateRange dateRange = new DateRange();
    final DateTimeFormatter formatter = DateTimeFormat.forPattern("YYYY-MM-dd");
    dateRange.setStartDate(formatter.print(interval.getStart()));
    dateRange.setEndDate(formatter.print(interval.getEnd()));
    Metric visitors = new Metric()
        .setExpression("ga:users").setAlias("users");
    ReportRequest request = new ReportRequest()
        .setViewId(affiliate.getViewId())
        .setMetrics(List.of(visitors))
        .setDateRanges(List.of(dateRange));

    var requests = new ArrayList<ReportRequest>();
    requests.add(request);

    GetReportsRequest getReport = new GetReportsRequest()
        .setReportRequests(requests);

    GetReportsResponse response = analytics.reports().batchGet(getReport).execute();
    return Integer.valueOf(response.getReports().get(0).getData().getTotals().get(0).getValues().get(0));


  }


  @Override
  protected void get(HttpServletRequest request, HttpServletResponse response)
      throws Exception {

    final Interval interval = Callgrove.getReportingInterval(request);
    final DateMidnight start = interval.getStart().toDateMidnight();
    final DateMidnight end = interval.getEnd().toDateMidnight();
    final String affiliateIdString = request.getParameter("affiliate");

    if (affiliateIdString == null) {
      respond(response, null);
      return;
    }
    Affiliate affiliate = null;
    try {
      affiliate = Locator.$(new Affiliate(Integer.parseInt(affiliateIdString)));
    } catch (NumberFormatException e) {
      respond(response, null);
      return;
    }

    final String q =
        String.format("report:%s,start:%s,end:%s,affiliate:%s",
            getClass().getSimpleName(),
            Callgrove.simple.print(start),
            Callgrove.simple.print(end),
            affiliateIdString);

    final String cached = cache.get(q);
    if (StringFun.isEmpty(cached)) {

      final JsonList phoneSales = query(interval, REFERRAL, affiliate);
      final JsonList onlineOrders = query(interval, ONLINE, affiliate);

      // add commission
      final Currency phoneCommissions = sumCommissions(phoneSales);
      final Currency onlineCommissions = sumCommissions(onlineOrders);

      var withAffiliate = withReferrer(affiliate.getDomain());

      final int phoneLeadsCreated = count(
          withAffiliate.and(createdInInterval(interval)).and(withSaleSource(REFERRAL)));
      final int phoneLeadsOpen = count(
          withAffiliate.and(isActive).and(createdBefore(interval.getEnd())));
      final int phoneLeadsClosedAsDead = count(
          withAffiliate.and(isDead).and(createdBefore(interval.getEnd())));

      var queue = $1(withAffiliate(affiliate));

      var affiliateCalls = isQueue.and(inInterval(interval).and(Call.withQueue(queue)));

      final JsonMap result = new JsonMap()
          .$("uniqueVisitors", countVisitors(affiliate, interval))
          .$("uniqueCallerIds", countDistinct(affiliateCalls, "callerid_number"))
          .$("totalCalls", count(affiliateCalls))
          .$("phoneLeadsCreated", phoneLeadsCreated)
          .$("phoneLeadsClosedAsDead", phoneLeadsClosedAsDead)
          .$("phoneLeadsOpen", phoneLeadsOpen)
          .$("phoneSales", phoneSales)
          .$("phoneCommissions", phoneCommissions)
          .$("onlineOrders", onlineOrders)
          .$("onlineCommissions", onlineCommissions)
          .$("totalCommissions", phoneCommissions.add(onlineCommissions));

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

  private static Currency sumCommissions(final JsonList list) {
    return list
        .stream()
        .map(json -> ((JsonMap) json))
        .map(json -> {
          try {
            return Currency.parse(json.get("commission"));
          } catch (ParseException e) {
            throw new RuntimeException(e);
          }
        })
        .reduce(Currency.ZERO, Currency::add);
  }

  private static JsonList query(final Interval interval, final SaleSource source,
      final Affiliate affiliate) {
    return Locator
        .$$(Opportunity.soldInInterval(interval)
            .and(Opportunity.isSold)
            .and(withReferrer(affiliate.getDomain()))
            .and(withSaleSource(source)
                .orderBy("saleDate", Direction.ASCENDING)))
        .stream(

        )
        .map(opp -> new JsonMap()
            .$("id", opp.getId())
            .$("item", opp.getProductLineName())
            .$("date", opp.getSaleDate())
            .$("amount", opp.getAmount())
            .$("commission", opp.getAmount().multiply(affiliate.getCommission())))
        .collect(JsonList.collect);
  }
}
