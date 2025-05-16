package net.inetalliance.sonar.api;

import com.callgrove.obj.Opportunity;
import com.callgrove.obj.Site;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.val;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.potion.Locator;
import net.inetalliance.sql.DateTimeInterval;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.regex.Pattern;

import static com.ameriglide.phenix.core.Strings.isEmpty;
import static com.callgrove.Callgrove.getInterval;
import static com.callgrove.obj.Call.*;
import static net.inetalliance.potion.Locator.$$;
import static net.inetalliance.potion.Locator.forEach;
import static net.inetalliance.sql.Aggregate.MIN;

@WebServlet("/api/uniqueCid")
public class UniqueCid
    extends AngularServlet {

  public static final Pattern pattern = Pattern.compile("/api/uniqueCid");

  @Override
  protected void get(final HttpServletRequest request, final HttpServletResponse response)
      throws Exception {
    val interval = getInterval(request);
    val atc = new Site(10117);
    val firstCall =
        $$(withSite(atc).and(isQueue), MIN, String.class, "callerid_number", LocalDateTime.class,
            "created");
    final var start = interval.start().toLocalDate();
    final var end = interval.end().toLocalDate();
    var i = start;
    val json = new JsonMap();
    val total = new Info();
    while (i.isBefore(end)) {
      val info = classify(new DateTimeInterval(i), atc, firstCall);
      total.add(info);
      json.put(Json.format(i), info.toJson());
      i = i.plusDays(1);
    }
    respond(response, new JsonMap().$("total", total.toJson()).$("days", json));
  }

  private Info classify(final DateTimeInterval interval, final Site site,
                        final Map<String, LocalDateTime> firstCall) {
    val i = new Info();
    i.opps = Locator.count(Opportunity.createdInInterval(interval).and(Opportunity.withSite(site)));

    forEach(withSite(site).and(inInterval(interval)).and(isQueue), call -> {
      val number = call.getCallerId().getNumber();
      if (isEmpty(number)) {
        i.anon++;
      } else {
        val first = firstCall.get(number);
        if (first.equals(call.getCreated())) {
          i.first++;
        } else {
          i.old++;
        }
      }
    });
    return i;
  }

  private static class Info {

    private int old;
    private int first;
    private int anon;
    private int opps;

    JsonMap toJson() {
      return new JsonMap().$("old", old).$("first", first).$("anon", anon).$("opps", opps);
    }

    void add(Info info) {
      old += info.old;
      first += info.first;
      anon += info.anon;
      opps += info.opps;
    }
  }
}
