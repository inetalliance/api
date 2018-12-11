package net.inetalliance.sonar.reports;

import com.callgrove.obj.*;
import com.callgrove.types.SaleSource;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.funky.functors.F1;
import net.inetalliance.funky.functors.P1;
import net.inetalliance.funky.functors.math.Stats;
import net.inetalliance.funky.functors.math.StatsCalculator;
import net.inetalliance.funky.functors.types.str.StringFun;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.types.struct.maps.LazyMap;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.joda.time.Interval;
import org.joda.time.format.DateTimeFormat;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.*;

import static net.inetalliance.potion.Locator.$$;

@WebServlet("/api/followUpEmailPerformance")
public class FollowUpEmailPerformance
		extends AngularServlet {

	private Info<ProductLine> productLineInfo = Info.$(ProductLine.class);
	private Info<Site> siteInfo = Info.$(Site.class);

	protected void get(final HttpServletRequest request, final HttpServletResponse response)
			throws Exception {
		final Interval interval = CachedGroupingRangeReport.getReportingInterval(request);
		final ProductLine productLine = productLineInfo.lookup.$(request.getParameter("productLine"));
		if (productLine == null) {
			throw new BadRequestException("Invalid product line specified");
		}
		final Site site = siteInfo.lookup.$(request.getParameter("site"));
		if (site == null) {
			throw new BadRequestException("Invalid site specified");
		}
		final String sourceParam = request.getParameter("source");
		final SaleSource source = StringFun.empty.$(sourceParam) || "all".equals(sourceParam) ?
				null : StringFun.camelCaseToEnum(SaleSource.class).$(sourceParam);

		Query<Opportunity> oppQuery = Opportunity.Q.withSite(site).and(Opportunity.Q.withProductLine(productLine));
		if (source != null) {
			oppQuery = oppQuery.and(Opportunity.Q.withSaleSource(source));
		}
		final Query<SentFollowUpEmail> query =
				SentFollowUpEmail.Q.hasEmail.and(
						SentFollowUpEmail.Q.withSentIn(interval))
						.and(SentFollowUpEmail.Q.opportunityJoin(oppQuery));
		final Map<FollowUpEmail, Row> rows = new LazyMap<FollowUpEmail, Row>(new HashMap<>(5)) {
			@Override
			public Row create(final FollowUpEmail followUpEmail) {
				return new Row(followUpEmail);
			}
		};
		for (final SentFollowUpEmail email : $$(query)) {
			rows.get(email.getEmail()).$(email);
		}
		final List<Row> data = new ArrayList<>(rows.values());
		Collections.sort(data);
		respond(response, new JsonList(Row.toJson.map(data)));
	}

	static final class Row
			extends P1<SentFollowUpEmail>
			implements Comparable<Row> {
		private final int id;
		private final String name;
		private final DateTime lastSent;
		private int sent;
		private int opened;
		private int conversions;
		private final StatsCalculator<Long> timeToOpen;
		private final StatsCalculator<Long> timeToConvert;

		public Row(final FollowUpEmail email) {
			id = email.id;
			name = email.getName();
			lastSent = email.getLastSent();
			sent = 0;
			opened = 0;
			conversions = 0;
			timeToOpen = StatsCalculator.$(Long.class);
			timeToConvert = StatsCalculator.$(Long.class);
		}

		@Override
		public int compareTo(final Row that) {
			return Integer.compare(that.id, this.id);
		}

		@Override
		public void $(final SentFollowUpEmail email) {
			sent++;
			if (email.isOpened()) {
				opened++;
				timeToOpen.$(new Duration(email.getSent(), email.getOpened()).getMillis());
			}
			if (email.isOpened() && email.isConverted()) {
				conversions++;
				timeToConvert.$(new Duration(email.getOpened(), email.getConversion()).getMillis());
			}
		}

		public static F1<Row, JsonMap> toJson = new F1<Row, JsonMap>() {
			@Override
			public JsonMap $(final Row row) {
				final Stats<Long> timeToOpenStats = row.timeToOpen.getStats();
				final Stats<Long> timeToConvertStats = row.timeToConvert.getStats();
				return new JsonMap()
						.$("id", row.id)
						.$("name", row.name)
						.$("lastSent", DateTimeFormat.shortDateTime().print(row.lastSent))
						.$("sent", row.sent)
						.$("opened", row.opened)
						.$("conversions", row.conversions)
						.$("timeToOpen", new JsonMap()
								.$("mean", formatDuration(timeToOpenStats.mean))
								.$("stdDeviation", formatDuration(timeToOpenStats.stdDeviation)))
						.$("timeToConvert", new JsonMap()
								.$("mean", formatDuration(timeToConvertStats.mean))
								.$("stdDeviation", formatDuration(timeToConvertStats.stdDeviation)));
			}
		};
	}

	private static String formatDuration(final Number number) {
		final long longValue = number.longValue();
		return longValue == 0L ? "" : new net.inetalliance.types.Duration(longValue).getShortString();
	}
}
