package net.inetalliance.sonar.api;

import com.callgrove.obj.*;
import com.callgrove.types.SaleSource;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.funky.StringFun;
import net.inetalliance.funky.math.Calculator;
import net.inetalliance.funky.math.Stats;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.joda.time.Interval;
import org.joda.time.format.DateTimeFormat;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.*;
import java.util.function.Consumer;

import static com.callgrove.Callgrove.*;
import static net.inetalliance.funky.StringFun.*;
import static net.inetalliance.potion.Locator.*;

@WebServlet("/api/followUpEmailPerformance")
public class FollowUpEmailPerformance
		extends AngularServlet {

	private Info<ProductLine> productLineInfo = Info.$(ProductLine.class);
	private Info<Site> siteInfo = Info.$(Site.class);

	private static String formatDuration(final Number number) {
		final long longValue = number.longValue();
		return longValue == 0L ? "" : new net.inetalliance.types.Duration(longValue).getShortString();
	}

	protected void get(final HttpServletRequest request, final HttpServletResponse response)
			throws Exception {
		final Interval interval = getReportingInterval(request);
		final ProductLine productLine = productLineInfo.lookup(request.getParameter("productLine"));
		if (productLine == null) {
			throw new BadRequestException("Invalid product line specified");
		}
		final Site site = siteInfo.lookup(request.getParameter("site"));
		if (site == null) {
			throw new BadRequestException("Invalid site specified");
		}
		final String sourceParam = request.getParameter("source");
		final SaleSource source = isEmpty(sourceParam) || "all".equals(sourceParam)
				? null
				: StringFun.camelCaseToEnum(SaleSource.class, sourceParam);

		Query<Opportunity> oppQuery = Opportunity.withSite(site).and(Opportunity.withProductLine(productLine));
		if (source != null) {
			oppQuery = oppQuery.and(Opportunity.withSaleSource(source));
		}
		final Query<SentFollowUpEmail> query = SentFollowUpEmail.hasEmail.and(SentFollowUpEmail.withSentIn(interval))
		                                                                 .and(SentFollowUpEmail.opportunityJoin(oppQuery));
		final Map<FollowUpEmail, Row> rows = new HashMap<>();
		for (final SentFollowUpEmail email : $$(query)) {
			rows.computeIfAbsent(email.getEmail(), Row::new).accept(email);
		}
		final List<Row> data = new ArrayList<>(rows.values());
		Collections.sort(data);
		respond(response, JsonList.collect(data, Row::toJson));
	}

	static final class Row
			implements Consumer<SentFollowUpEmail>, Comparable<Row> {
		private final int id;
		private final String name;
		private final DateTime lastSent;
		private final Calculator<Long> timeToOpen;
		private final Calculator<Long> timeToConvert;
		private int sent;
		private int opened;
		private int conversions;

		private Row(final FollowUpEmail email) {
			id = email.id;
			name = email.getName();
			lastSent = email.getLastSent();
			sent = 0;
			opened = 0;
			conversions = 0;
			timeToOpen = Calculator.newLong();
			timeToConvert = Calculator.newLong();
		}

		private static JsonMap toJson(final Row row) {
			final Stats<Long> timeToOpenStats = row.timeToOpen.getStats();
			final Stats<Long> timeToConvertStats = row.timeToConvert.getStats();
			return new JsonMap().$("id", row.id)
			                    .$("name", row.name)
			                    .$("lastSent", DateTimeFormat.shortDateTime().print(row.lastSent))
			                    .$("sent", row.sent)
			                    .$("opened", row.opened)
			                    .$("conversions", row.conversions)
			                    .$("timeToOpen", new JsonMap().$("mean", formatDuration(timeToOpenStats.mean))
			                                                  .$("stdDeviation",
			                                                     formatDuration(timeToOpenStats.stdDeviation)))
			                    .$("timeToConvert", new JsonMap().$("mean", formatDuration(timeToConvertStats.mean))
			                                                     .$("stdDeviation",
			                                                        formatDuration(timeToConvertStats.stdDeviation)));
		}

		@Override
		public int compareTo(final Row that) {
			return Integer.compare(that.id, this.id);
		}

		@Override
		public void accept(final SentFollowUpEmail email) {
			sent++;
			if (email.isOpened()) {
				opened++;
				timeToOpen.accept(new Duration(email.getSent(), email.getOpened()).getMillis());
			}
			if (email.isOpened() && email.isConverted()) {
				conversions++;
				timeToConvert.accept(new Duration(email.getOpened(), email.getConversion()).getMillis());
			}
		}
	}
}
