package net.inetalliance.sonar.reports;

import com.callgrove.obj.*;
import net.inetalliance.funky.functors.F1;
import net.inetalliance.funky.functors.P1;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.Interval;

import javax.servlet.annotation.WebServlet;
import java.util.Map;
import java.util.Set;

import static com.callgrove.obj.DailyProductLineVisits.Q.withProductLineIn;
import static com.callgrove.obj.DailyProductLineVisits.Q.withSite;
import static net.inetalliance.potion.Locator.$$;
import static net.inetalliance.potion.Locator.forEach;
import static net.inetalliance.potion.obj.IdPo.F.id;
import static net.inetalliance.sql.Aggregate.SUM;

@WebServlet("/api/productLinePerformance")
public class ProductLinePerformance
		extends Performance<Site, ProductLine> {
	private static final F1<String, ProductLine> lookup = Info.$(ProductLine.class).lookup;

	public ProductLinePerformance() {
		super("productLine");
	}

	@Override
	protected void addExtra(final JsonMap row, final Map<String, Interval> intervals, final Site site,
	                        final Set<ProductLine> productLines) {
		final JsonMap unassigned = new JsonMap();
		for (Map.Entry<String, Interval> entry : intervals.entrySet()) {
			final Interval interval = entry.getValue();

			final Integer totalVisits =
					$$(DailySiteVisits.Q.withSite(site).and(DailySiteVisits.Q.inInterval(interval)),
							SUM, Integer.class, "visits");
			final int allAssignedVisits =
					$$(withSite(site)
							.and(DailyProductLineVisits.Q.inInterval(interval)), SUM, Integer.class, "visits");
			final int allProductLineVisits =
					$$(withSite(site)
							.and(withProductLineIn(id.map(productLines)))
							.and(DailyProductLineVisits.Q.inInterval(interval)), SUM, Integer.class, "visits");
			final int unassignedVisits = totalVisits - allAssignedVisits;
			unassigned.put(entry.getKey(), (int) ((double) unassignedVisits * allProductLineVisits / allAssignedVisits));
		}
		row.$("unassigned", unassigned);
	}

	@Override
	protected void addGroupQueues(final Map<Integer, Set<String>> rowQueues) {
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
	protected void addRowQueues(final Map<Integer, Set<String>> groupQueues) {
		forEach(Query.all(Site.class), new P1<Site>() {
			@Override
			public void $(final Site site) {
				groupQueues.get(site.id).addAll(Queue.F.key.map(site.getQueues()));
			}
		});
	}

	@Override
	protected Query<Site> allRows(final Agent agent) {
		return Site.Q.isActive;
	}

	@Override
	protected String getGroupLabel(final ProductLine group) {
		return group.getName();
	}

	@Override
	protected F1<String, ProductLine> getGroupLookup(final String[] params) {
		return lookup;
	}

	@Override
	protected String getId(final Site row) {
		return row.getId().toString();
	}

	@Override
	protected Query<Opportunity> oppsWithGroup(final Set<ProductLine> groups) {
		return Opportunity.Q.withProductLineIn(id.map(groups));
	}

	@Override
	protected Query<Opportunity> oppsWithRow(final Site row) {
		return Opportunity.Q.withSite(row);
	}

	@Override
	protected Query<DailyProductLineVisits> visitsWithGroup(final Set<ProductLine> groups) {
		return withProductLineIn(id.map(groups));
	}

	@Override
	protected Query<DailyProductLineVisits> visitsWithRow(final Site site) {
		return withSite(site);
	}
}
