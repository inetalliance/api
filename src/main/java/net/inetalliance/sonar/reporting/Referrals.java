package net.inetalliance.sonar.reporting;

import static net.inetalliance.types.www.ContentType.JSON;

import com.callgrove.Callgrove;
import com.callgrove.obj.Affiliate;
import java.io.PrintWriter;
import java.util.Arrays;
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
import net.inetalliance.types.Currency;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.DateMidnight;
import org.joda.time.DateTime;
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

      // ===============
      // QUERY DATA HERE
      // ===============

      final JsonList phoneSales = Arrays.asList(
          new JsonMap()
              .$("id", 42)
              .$("item", "Acme Widget 3000")
              .$("date", new DateTime().minusDays(8))
              .$("amount", new Currency(Math.random() * 200)),
          new JsonMap()
              .$("id", 45)
              .$("item", "Awesome Product")
              .$("date", new DateTime().minusDays(4))
              .$("amount", new Currency(Math.random() * 200)),
          new JsonMap()
              .$("id", 48)
              .$("item", "Turboencabulator 9000")
              .$("date", new DateTime().minusDays(2))
              .$("amount", new Currency(Math.random() * 200))
      ).stream().collect(JsonList.collect);

      final JsonList onlineOrders = Arrays.asList(
          new JsonMap()
              .$("id", 35)
              .$("item", "100pc Sprocket Set")
              .$("date", new DateTime().minusDays(9))
              .$("amount", new Currency(Math.random() * 200)),
          new JsonMap()
              .$("id", 46)
              .$("item", "Build your own product Kit")
              .$("date", new DateTime().minusDays(6))
              .$("amount", new Currency(Math.random() * 200)),
          new JsonMap()
              .$("id", 50)
              .$("item", "Electric Washing Cabinet")
              .$("date", new DateTime().minusDays(1))
              .$("amount", new Currency(Math.random() * 200))
      ).stream().collect(JsonList.collect);

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
          .$("onlineOrders", onlineOrders);

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
