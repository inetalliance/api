package net.inetalliance.sonar.reporting;

import static net.inetalliance.types.www.ContentType.JSON;

import com.callgrove.Callgrove;
import com.callgrove.obj.Affiliate;
import com.callgrove.obj.Opportunity;
import com.callgrove.types.SaleSource;
import java.io.PrintWriter;
import java.text.ParseException;
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

  @Override
  protected void get(HttpServletRequest request, HttpServletResponse response)
      throws Exception {

    final Interval interval = Callgrove.getInterval(request);
    final DateMidnight start = interval.getStart().toDateMidnight();
    final DateMidnight end = interval.getEnd().toDateMidnight();
    final String affiliateIdString = request.getParameter("affiliate");

    if (affiliateIdString == null) {
      respond(response, null);
    }
    Affiliate affiliate = null;
    try {
      affiliate = Locator.$(new Affiliate(Integer.parseInt(affiliateIdString)));
    } catch (NumberFormatException e) {
      respond(response, null);
    }

    final String q =
        String.format("report:%s,start:%s,end:%s,affiliate:%s",
            getClass().getSimpleName(),
            Callgrove.simple.print(start),
            Callgrove.simple.print(end),
            affiliateIdString);

    final String cached = cache.get(q);
    if (StringFun.isEmpty(cached)) {

      final JsonList phoneSales = query(interval, SaleSource.PHONE_CALL, affiliate);
      final JsonList onlineOrders = query(interval, SaleSource.ONLINE, affiliate);

      // add commission
      final Currency phoneCommissions = sumCommissions(phoneSales);
      final Currency onlineCommissions = sumCommissions(onlineOrders);

      final int phoneLeadsCreated = (int) Math.floor(Math.random() * 200);
      final int phoneLeadsOpen = (int) Math.floor(Math.random() * phoneLeadsCreated);
      final int phoneLeadsClosedAsDead = (int) Math
          .floor(Math.random() * (phoneLeadsCreated - phoneLeadsOpen));
      final JsonMap result = new JsonMap()
          .$("uniqueVisitors", Math.floor(Math.random() * 500))
          .$("uniqueCallerIds", Math.floor(Math.random() * 400))
          .$("totalCalls", Math.floor(Math.random() * 800))
          .$("phoneLeadsCreated", phoneLeadsCreated)
          .$("phoneLeadsClosedAsDead", phoneLeadsClosedAsDead)
          .$("phoneLeadsOpen", phoneLeadsOpen)
          .$("phoneSales", phoneSales)
          .$("phoneCommissions", phoneCommissions)
          .$("onlineOrders", onlineOrders)
          .$("onlineCommissions", onlineCommissions);

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
// TODO: uncomment this line to make this report actually work
//            .and(Opportunity.withReferrer(affiliate.getDomain()))
            .and(Opportunity.withSaleSource(source)
                .orderBy("saleDate", Direction.ASCENDING)))
        .stream()
        .map(opp -> new JsonMap()
            .$("id", opp.getId())
            .$("item", opp.getProductLineName())
            .$("date", opp.getSaleDate())
            .$("amount", opp.getAmount())
            .$("commission", opp.getAmount().multiply(affiliate.getCommission())))
        .collect(JsonList.collect);
  }
}
