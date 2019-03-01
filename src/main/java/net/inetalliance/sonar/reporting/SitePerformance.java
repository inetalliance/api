package net.inetalliance.sonar.reporting;

import com.callgrove.obj.Queue;
import com.callgrove.obj.*;
import net.inetalliance.potion.info.*;
import net.inetalliance.potion.query.*;
import net.inetalliance.types.json.*;
import org.joda.time.*;

import javax.servlet.annotation.*;
import java.util.*;
import java.util.regex.*;

import static net.inetalliance.potion.Locator.*;
import static net.inetalliance.sql.Aggregate.*;

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
			final ProductLine productLine = queue.getProductLine();
			if (productLine != null) {
				rowQueues.computeIfAbsent(productLine.id, k -> new HashSet<>()).add(queue.key);
			}
		});

	}

	@Override
	protected void addGroupQueues(final Map<Integer, Set<String>> groupQueues) {
		forEach(Query.all(Site.class), site -> {
			Set<String> siteQueues = groupQueues.computeIfAbsent(site.id, i -> new HashSet<>());
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
	protected void addExtra(final JsonMap json, final Map<String, Interval> intervals, final Set<Site> sites) {
		final JsonMap unassigned = new JsonMap();
		for (final Map.Entry<String, Interval> entry : intervals.entrySet()) {
			final Interval interval = entry.getValue();
			final Integer totalVisits =
					$$(DailySiteVisits.withSiteIn(sites).and(DailySiteVisits.inInterval(interval)), SUM, Integer.class,
					   "visits");
			final int allProductLineVisits =
					$$(DailyProductLineVisits.withSiteIn(sites).and(DailyProductLineVisits.inInterval(interval)), SUM,
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
	protected Query<ProductLine> allRows(final Agent loggedIn) {
		return Query.all(ProductLine.class);
	}

	@Override
	protected Site getGroup(final String[] params, final String key) {
		return info.lookup(key);
	}
}
