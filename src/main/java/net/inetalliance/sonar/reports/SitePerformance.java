package net.inetalliance.sonar.reports;

import com.callgrove.obj.*;
import net.inetalliance.angular.dispatch.Dispatchable;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.Interval;

import javax.servlet.annotation.WebServlet;
import java.util.HashSet;
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

	public static final Pattern pattern = Pattern.compile("/api/sitePerformance");
	private final Info<Site> info;

	public SitePerformance() {
		super("site");
		this.info = Info.$(Site.class);
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
				$$(DailySiteVisits.withSiteIn(sites).and(DailySiteVisits.inInterval(interval)),
					SUM, Integer.class, "visits");
			final int allProductLineVisits =
				$$(DailyProductLineVisits.withSiteIn(sites).and(DailyProductLineVisits.inInterval(interval)),
					SUM, Integer.class, "visits");
			unassigned.put(entry.getKey(), totalVisits - allProductLineVisits);
		}
		json.put("unassignedVisits", unassigned);
	}

	@Override
	protected void addGroupQueues(final Map<Integer, Set<String>> groupQueues) {
		forEach(Query.all(Site.class), site -> {
			final Set<String> siteQueues = groupQueues.computeIfAbsent(site.id, i -> new HashSet<>());
			site.getQueues().forEach(q -> siteQueues.add(q.key));
		});

	}

	@Override
	protected void addRowQueues(final Map<Integer, Set<String>> rowQueues) {
		forEach(Query.all(Queue.class), queue -> {
			final ProductLine productLine = queue.getProductLine();
			if (productLine != null) {
				rowQueues.get(productLine.id).add(queue.key);
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
	protected Site getGroup(final String[] params, final String key) {
		return info.lookup(key);
	}

	@Override
	protected String getId(final ProductLine row) {
		return row.getId().toString();
	}

	@Override
	protected Query<Opportunity> oppsWithGroup(final Set<Site> groups) {
		return Opportunity.withSiteIn(groups);
	}

	@Override
	protected Query<Opportunity> oppsWithRow(final ProductLine row) {
		return Opportunity.withProductLine(row);
	}

	@Override
	protected Query<DailyProductLineVisits> visitsWithGroup(final Set<Site> groups) {
		return DailyProductLineVisits.withSiteIn(groups);
	}

	@Override
	protected Query<DailyProductLineVisits> visitsWithRow(final ProductLine productLine) {
		return DailyProductLineVisits.withProductLine(productLine);
	}
}
