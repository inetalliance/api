package net.inetalliance.sonar.reports;

import com.callgrove.obj.*;
import com.callgrove.types.ContactType;
import com.callgrove.types.SaleSource;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.angular.exception.UnauthorizedException;
import net.inetalliance.funky.functors.F1;
import net.inetalliance.funky.functors.P1;
import net.inetalliance.funky.functors.types.str.StringFun;
import net.inetalliance.log.Log;
import net.inetalliance.log.progress.ProgressMeter;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.Currency;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.DateMidnight;
import org.joda.time.Interval;

import javax.servlet.annotation.WebServlet;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.callgrove.obj.Opportunity.Q.*;
import static com.callgrove.types.SaleSource.PHONE_CALL;
import static com.callgrove.types.SaleSource.SURVEY;
import static java.util.Optional.ofNullable;
import static net.inetalliance.funky.functors.types.str.StringFun.enumToCamelCase;
import static net.inetalliance.log.Log.getInstance;
import static net.inetalliance.potion.Locator.*;
import static net.inetalliance.sql.Aggregate.SUM;
import static net.inetalliance.types.Currency.ZERO;

@WebServlet({"/api/surveyClosing", "/reporting/reports/surveyClosing"})
public class SurveyClosing
	extends CachedGroupingRangeReport<Agent, Site> {

	private static final transient Log log = getInstance(SurveyClosing.class);

	private static final F1<String, Site> lookup = Info.$(Site.class).lookup;

	public SurveyClosing() {
		super("site", "productLine");
	}

	@Override
	protected F1<String, Site> getGroupLookup(final String[] params) {
		return lookup;
	}

	@Override
	protected JsonMap generate(final EnumSet<SaleSource> sources,
	                           final EnumSet<ContactType> contactTypes,
	                           final Agent loggedIn, final ProgressMeter meter,
	                           final DateMidnight start, final DateMidnight end,
	                           final Set<Site> sites,
	                           final Map<String, String> extras) {
		if (loggedIn == null || !(loggedIn.isManager() || loggedIn.isTeamLeader())) {
			log.warning("%s tried to access closing report data",
				loggedIn == null ? "Nobody?" : loggedIn.key);
			throw new UnauthorizedException();
		}
		final String productLineId = extras.get("productLine");
		if (StringFun.empty.$(productLineId)) {
			throw new BadRequestException("Must specify product line via ?productLine=");
		}
		final ProductLine productLine = $(new ProductLine(new Integer(productLineId)));
		if (productLine == null) {
			throw new NotFoundException("Could not find product line with id %s", productLineId);
		}

		final Interval interval = getReportingInterval(start, end);

		final Set<Site> visibleSites = loggedIn.getVisibleSites();
		if (sites != null && !sites.isEmpty()) {
			for (final Site site : sites) {
				if (!visibleSites.contains(site)) {
					log.warning("%s tried to access closing data for site %d", loggedIn.key, site.id);
					throw new UnauthorizedException();
				}
			}
		}

		boolean hasSite = sites != null && !sites.isEmpty();

		final Query<Opportunity> base =
			hasSite ? withProductLine(productLine).and(withSiteIn(sites)) : withProductLine(productLine);

		final Set<String> queuesForProductLine = ProductLineClosing.getQueues(loggedIn, productLine, sites);
		final JsonList rows = new JsonList();
		forEach(allRows(loggedIn), new P1<Agent>() {
			@Override
			public void $(final Agent agent) {
				final Query<Opportunity> andAgent = base.and(Opportunity.Q.withAgent(agent));
				final JsonMap row = new JsonMap().$("agent", agent.getLastNameFirstInitial());
				final AtomicBoolean hasOpps = new AtomicBoolean(false);
				EnumSet.of(SURVEY, PHONE_CALL).forEach(source ->
				{
					final Query<Opportunity> andSource = andAgent.and(withSaleSource(source));
					final Query<Opportunity> andSoldInInterval = andSource.and(soldInInterval(interval));
					final int count = count(andSource.and(createdInInterval(interval)));
					if (count > 0) {
						hasOpps.set(true);
					}
					row.$(enumToCamelCase.$(source),
						new JsonMap()
							.$("count", count)
							.$("closes", count(andSoldInInterval))
							.$("total", (ofNullable($$(andSoldInInterval, SUM, Currency.class, "amount"))
								.orElse(ZERO)).doubleValue()));
				});
				row.$("calls", queuesForProductLine.isEmpty() ? 0 : count(Call.Q.inInterval(interval).and(Call.Q.queue).and(Call.Q.withQueueIn(queuesForProductLine))
					.and(Call.Q.withBlame(agent))));

				meter.increment(agent.getLastNameFirstInitial());
				if (hasOpps.get()) {
					rows.add(row);
				}
			}

		});
		return new JsonMap().$("rows", rows);

	}

	@Override
	protected Query<Agent> allRows(final Agent loggedIn) {
		return loggedIn.getViewableAgentsQuery().and(Agent.Q.sales);
	}

	@Override
	protected String getGroupLabel(final Site group) {
		return group.getName();
	}

	@Override
	protected String getId(final Agent row) {
		return row.key;
	}

	@Override
	protected int getJobSize(final Agent loggedIn, final int numGroups) {
		return count(allRows(loggedIn));
	}
}
