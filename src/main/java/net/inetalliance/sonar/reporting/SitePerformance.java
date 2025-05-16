package net.inetalliance.sonar.reporting;

import static net.inetalliance.potion.Locator.$$;
import static net.inetalliance.potion.Locator.forEach;
import static net.inetalliance.sql.Aggregate.SUM;

import com.callgrove.obj.Agent;
import com.callgrove.obj.DailyProductLineVisits;
import com.callgrove.obj.DailySiteVisits;
import com.callgrove.obj.Opportunity;
import com.callgrove.obj.ProductLine;
import com.callgrove.obj.Queue;
import com.callgrove.obj.Site;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import jakarta.servlet.annotation.WebServlet;
import lombok.val;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sql.DateTimeInterval;
import net.inetalliance.types.json.JsonMap;
import org.jooq.types.Interval;

@WebServlet("/reporting/reports/sitePerformance")
public class SitePerformance
    extends Performance<ProductLine, Site> {

  public static final Pattern pattern = Pattern.compile("/api/sitePerformance");
  private final Info<Site> info;

  public SitePerformance() {
    super("site");
    info = Info.$(Site.class);
  }

  public Pattern getPattern() {
    return pattern;
  }

  @Override
  protected void addRowQueues(final Map<Integer, Set<String>> rowQueues) {
    forEach(Query.all(Queue.class), queue -> {
      val productLine = queue.getProductLine();
      if (productLine != null) {
        rowQueues.computeIfAbsent(productLine.id, k -> new HashSet<>()).add(queue.key);
      }
    });

  }

  @Override
  protected void addGroupQueues(final Map<Integer, Set<String>> groupQueues) {
    forEach(Query.all(Site.class), site -> {
        var siteQueues = groupQueues.computeIfAbsent(site.id, i -> new HashSet<>());
      site.getQueues().forEach(q -> siteQueues.add(q.key));
    });
  }

  @Override
  protected Query<Opportunity> oppsWithGroup(final Set<Site> groups) {
    return Opportunity.withSiteIn(groups);
  }

  @Override
  protected Query<DailyProductLineVisits> visitsWithGroup(final Set<Site> groups) {
    return DailyProductLineVisits.withSiteIn(groups);
  }

  @Override
  protected Query<DailyProductLineVisits> visitsWithRow(final ProductLine productLine) {
    return DailyProductLineVisits.withProductLine(productLine);
  }

  @Override
  protected Query<Opportunity> oppsWithRow(final ProductLine row) {
    return Opportunity.withProductLine(row);
  }

  @Override
  protected void addExtra(final JsonMap json, final Map<String, DateTimeInterval> intervals,
      final Set<Site> sites) {
    val unassigned = new JsonMap();
    for (val entry : intervals.entrySet()) {
      val interval = entry.getValue();
      val totalVisits =
          $$(DailySiteVisits.withSiteIn(sites).and(DailySiteVisits.inInterval(interval)), SUM,
              Integer.class,
              "visits");
      final int allProductLineVisits =
          $$(DailyProductLineVisits.withSiteIn(sites)
                  .and(DailyProductLineVisits.inInterval(interval)), SUM,
              Integer.class, "visits");
      unassigned.put(entry.getKey(), totalVisits - allProductLineVisits);
    }
    json.put("unassignedVisits", unassigned);
  }

  @Override
  protected String getGroupLabel(final Site group) {
    return group.getAbbreviation().toString();
  }

  @Override
  protected String getId(final ProductLine row) {
    return row.getId().toString();
  }

  @Override
  protected Query<ProductLine> allRows(final Set<Site> groups, final Agent loggedIn,
      final LocalDateTime intervalStart) {
    // don't show vehicle conversion data in reports unless ATC is included in the report
    var wavSites = Set.of(10117,10050); // ATC and AGR
    return groups.stream().anyMatch(g -> wavSites.contains(g.id))
        ? Query.all(ProductLine.class)
        : ProductLine.withId(10045).negate(); // Vehicle Conversions
  }

  @Override
  protected Site getGroup(final String[] params, final String key) {
    return info.lookup(key);
  }
}
