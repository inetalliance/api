package net.inetalliance.sonar.reporting;

import com.callgrove.obj.*;
import jakarta.servlet.annotation.WebServlet;
import lombok.val;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sql.DateTimeInterval;
import net.inetalliance.types.json.JsonMap;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.callgrove.obj.DailyProductLineVisits.withProductLineIn;
import static com.callgrove.obj.DailyProductLineVisits.withSite;
import static net.inetalliance.potion.Locator.$$;
import static net.inetalliance.potion.Locator.forEach;
import static net.inetalliance.sql.Aggregate.SUM;

@WebServlet("/reporting/reports/productLinePerformance")
public class ProductLinePerformance
        extends Performance<Site, ProductLine> {

    private final Info<ProductLine> productLineInfo;

    public ProductLinePerformance() {
        super("product");
        productLineInfo = Info.$(ProductLine.class);
    }

    @Override
    protected void addRowQueues(final Map<Integer, Set<String>> groupQueues) {
        forEach(Query.all(Site.class), site -> {
            val siteQueues = groupQueues.computeIfAbsent(site.id, _ -> new HashSet<>());
            site.getQueues().forEach(s -> siteQueues.add(s.key));
        });
    }

    @Override
    protected void addGroupQueues(final Map<Integer, Set<String>> rowQueues) {
        forEach(Query.all(Queue.class), queue -> {
            val productLine = queue.getProductLine();
            if (productLine != null) {
                rowQueues.computeIfAbsent(productLine.id, _ -> new HashSet<>()).add(queue.key);
            }
        });

    }

    @Override
    protected Query<Opportunity> oppsWithGroup(final Set<ProductLine> groups) {
        return Opportunity.withProductLineIn(groups);
    }

    @Override
    protected Query<DailyProductLineVisits> visitsWithGroup(final Set<ProductLine> groups) {
        return withProductLineIn(groups);
    }

    @Override
    protected Query<DailyProductLineVisits> visitsWithRow(final Site site) {
        return withSite(site);
    }

    @Override
    protected Query<Opportunity> oppsWithRow(final Site row) {
        return Opportunity.withSite(row);
    }

    @Override
    protected void addExtra(final JsonMap row, final Map<String, DateTimeInterval> intervals, final Site site,
                            final Set<ProductLine> productLines) {
        val unassigned = new JsonMap();
        for (var entry : intervals.entrySet()) {
            val interval = entry.getValue();

            val totalVisits =
                    $$(DailySiteVisits.withSite(site).and(DailySiteVisits.inInterval(interval)), SUM,
                            Integer.class, "visits");
            final int allAssignedVisits =
                    $$(withSite(site).and(DailyProductLineVisits.inInterval(interval)), SUM, Integer.class,
                            "visits");
            final int allProductLineVisits =
                    $$(withSite(site).and(withProductLineIn(productLines))
                                    .and(DailyProductLineVisits.inInterval(interval)), SUM,
                            Integer.class, "visits");
            val unassignedVisits = totalVisits - allAssignedVisits;
            unassigned.put(entry.getKey(),
                    (int) ((double) unassignedVisits * allProductLineVisits / allAssignedVisits));
        }
        row.$("unassigned", unassigned);
    }

    @Override
    protected String getGroupLabel(final ProductLine group) {
        return group.getName();
    }

    @Override
    protected String getId(final Site row) {
        return row.getId().toString();
    }

    @Override
    protected Query<Site> allRows(final Set<ProductLine> groups, final Agent agent,
                                  final LocalDateTime intervalStart) {
        return Site.isActive.and(agent.getVisibleSitesQuery());
    }

    @Override
    protected ProductLine getGroup(final String[] params, final String key) {
        return productLineInfo.lookup(key);
    }
}
