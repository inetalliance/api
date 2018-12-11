package net.inetalliance.sonar.reports;

import com.callgrove.obj.*;
import net.inetalliance.angular.dispatch.Dispatchable;
import net.inetalliance.funky.functors.F1;
import net.inetalliance.funky.functors.P1;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.Interval;

import javax.servlet.annotation.WebServlet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static net.inetalliance.potion.Locator.$$;
import static net.inetalliance.potion.Locator.forEach;
import static net.inetalliance.sql.Aggregate.SUM;

@WebServlet("/api/sitePerformance")
public class SitePerformance
  extends Performance<ProductLine, Site>
  implements Dispatchable {
  private static final F1<String, Site> lookup = Info.$(Site.class).lookup;

  public static final Pattern pattern = Pattern.compile("/api/sitePerformance");

  public SitePerformance() {
    super("site");
  }

  // Begin Dispatchable methods
  @Override
  public Pattern getPattern() {
    return pattern;
  }

  // End Dispatchable methods
  @Override
  protected void addExtra(final JsonMap json, final Map<String, Interval> intervals, final Set<Site> sites) {
    final JsonMap unassigned = new JsonMap();
    for (final Map.Entry<String, Interval> entry : intervals.entrySet()) {
      final Interval interval = entry.getValue();
      final Integer totalVisits =
        $$(DailySiteVisits.Q.withSiteIn(sites).and(DailySiteVisits.Q.inInterval(interval)),
          SUM, Integer.class, "visits");
      final int allProductLineVisits =
        $$(DailyProductLineVisits.Q.withSiteIn(sites).and(DailyProductLineVisits.Q.inInterval(interval)),
          SUM, Integer.class, "visits");
      unassigned.put(entry.getKey(), totalVisits - allProductLineVisits);
    }
    json.put("unassignedVisits", unassigned);
  }

  @Override
  protected void addGroupQueues(final Map<Integer, Set<String>> groupQueues) {
    forEach(Query.all(Site.class), new P1<Site>() {
      @Override
      public void $(final Site site) {
        groupQueues.get(site.id).addAll(Queue.F.key.map(site.getQueues()));
      }
    });
  }

  @Override
  protected void addRowQueues(final Map<Integer, Set<String>> rowQueues) {
    forEach(Query.all(Queue.class), new P1<Queue>() {
      @Override
      public void $(final Queue queue) {
        final ProductLine productLine = queue.getProductLine();
        if (productLine != null) {
          rowQueues.get(productLine.id).add(queue.key);
        }
      }
    });

  }

  @Override
  protected Query<ProductLine> allRows(final Agent loggedIn) {
    return Query.all(ProductLine.class);
  }

  @Override
  protected String getGroupLabel(final Site group) {
    return group.getAbbreviation().toString();
  }

  @Override
  protected F1<String, Site> getGroupLookup(final String[] params) {
    return lookup;
  }

  @Override
  protected String getId(final ProductLine row) {
    return row.getId().toString();
  }

  @Override
  protected Query<Opportunity> oppsWithGroup(final Set<Site> groups) {
    return Opportunity.Q.withSiteIn(groups);
  }

  @Override
  protected Query<Opportunity> oppsWithRow(final ProductLine row) {
    return Opportunity.Q.withProductLine(row);
  }

  @Override
  protected Query<DailyProductLineVisits> visitsWithGroup(final Set<Site> groups) {
    return DailyProductLineVisits.Q.withSiteIn(groups);
  }

  @Override
  protected Query<DailyProductLineVisits> visitsWithRow(final ProductLine productLine) {
    return DailyProductLineVisits.Q.withProductLine(productLine);
  }
}
