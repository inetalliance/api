package net.inetalliance.sonar.reporting;

import com.callgrove.obj.*;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.Interval;

import javax.servlet.annotation.WebServlet;
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
	private Info<ProductLine> productLineInfo;

	public ProductLinePerformance() {
		super("product");
		productLineInfo = Info.$(ProductLine.class);
	}

	@Override
	protected void addExtra(final JsonMap row, final Map<String, Interval> intervals, final Site site,
	                        final Set<ProductLine> productLines) {
		final JsonMap unassigned = new JsonMap();
		for (Map.Entry<String, Interval> entry : intervals.entrySet()) {
			final Interval interval = entry.getValue();

			final Integer totalVisits =
				$$(DailySiteVisits.withSite(site).and(DailySiteVisits.inInterval(interval)),
					SUM, Integer.class, "visits");
			final int allAssignedVisits =
				$$(withSite(site)
					.and(DailyProductLineVisits.inInterval(interval)), SUM, Integer.class, "visits");
			final int allProductLineVisits =
				$$(withSite(site)
					.and(withProductLineIn(productLines))
					.and(DailyProductLineVisits.inInterval(interval)), SUM, Integer.class, "visits");
			final int unassignedVisits = totalVisits - allAssignedVisits;
			unassigned.put(entry.getKey(), (int) ((double) unassignedVisits * allProductLineVisits / allAssignedVisits));
		}
		row.$("unassigned", unassigned);
	}

	@Override
	protected void addGroupQueues(final Map<Integer, Set<String>> rowQueues) {
		forEach(Query.all(Queue.class), queue -> {
			final ProductLine productLine = queue.getProductLine();
			if (productLine != null) {
				rowQueues.computeIfAbsent(productLine.id, i -> new HashSet<>()).add(queue.key);
			}
		});

	}

	@Override
	protected void addRowQueues(final Map<Integer, Set<String>> groupQueues) {
		forEach(Query.all(Site.class),
			site -> {
				final Set<String> siteQueues = groupQueues.computeIfAbsent(site.id, i -> new HashSet<>());
				site.getQueues().forEach(s -> siteQueues.add(s.key));
			});
	}

	@Override
	protected Query<Site> allRows(final Agent agent) {
		return Site.isActive;
	}

	@Override
	protected String getGroupLabel(final ProductLine group) {
		return group.getName();
	}

	@Override
	protected ProductLine getGroup(final String[] params, final String key) {
		return productLineInfo.lookup(key);
	}

	@Override
	protected String getId(final Site row) {
		return row.getId().toString();
	}

	@Override
	protected Query<Opportunity> oppsWithGroup(final Set<ProductLine> groups) {
		return Opportunity.withProductLineIn(groups);
	}

	@Override
	protected Query<Opportunity> oppsWithRow(final Site row) {
		return Opportunity.withSite(row);
	}

	@Override
	protected Query<DailyProductLineVisits> visitsWithGroup(final Set<ProductLine> groups) {
		return withProductLineIn(groups);
	}

	@Override
	protected Query<DailyProductLineVisits> visitsWithRow(final Site site) {
		return withSite(site);
	}
}
