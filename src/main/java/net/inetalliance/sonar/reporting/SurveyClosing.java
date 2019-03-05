package net.inetalliance.sonar.reporting;

import com.callgrove.obj.*;
import com.callgrove.types.*;
import net.inetalliance.angular.exception.*;
import net.inetalliance.log.*;
import net.inetalliance.log.progress.*;
import net.inetalliance.potion.info.*;
import net.inetalliance.potion.query.*;
import net.inetalliance.types.Currency;
import net.inetalliance.types.json.*;
import org.joda.time.*;

import javax.servlet.annotation.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import static com.callgrove.Callgrove.*;
import static com.callgrove.obj.Opportunity.*;
import static com.callgrove.types.SaleSource.*;
import static java.util.Optional.*;
import static net.inetalliance.funky.StringFun.*;
import static net.inetalliance.log.Log.getInstance;
import static net.inetalliance.potion.Locator.*;
import static net.inetalliance.sql.Aggregate.*;
import static net.inetalliance.types.Currency.*;

@WebServlet({"/reporting/reports/surveyClosing"})
public class SurveyClosing
		extends CachedGroupingRangeReport<Agent, Site> {

	private static final transient Log log = getInstance(SurveyClosing.class);
	private final Info<Site> info;

	public SurveyClosing() {
		super("site", "productLine");
		this.info = Info.$(Site.class);
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
	protected Query<Agent> allRows(final Agent loggedIn, final DateTime intervalStart) {
		return loggedIn.getViewableAgentsQuery().and(Agent.activeAfter(intervalStart)).and(Agent.isSales);
	}

	@Override
	protected Site getGroup(final String[] params, final String key) {
		return info.lookup(key);
	}

	@Override
	protected int getJobSize(final Agent loggedIn, final int numGroups, final DateTime intervalStart) {
		return count(allRows(loggedIn, intervalStart));
	}

	@Override
	protected JsonMap generate(final EnumSet<SaleSource> sources, final EnumSet<ContactType> contactTypes,
			final Agent loggedIn, final ProgressMeter meter, final DateMidnight start, final DateMidnight end,
			final Set<Site> sites, final Map<String, String> extras) {
		if (loggedIn == null || !(loggedIn.isManager() || loggedIn.isTeamLeader())) {
			log.warning("%s tried to access closing report data", loggedIn == null ? "Nobody?" : loggedIn.key);
			throw new UnauthorizedException();
		}
		final String productLineId = extras.get("productLine");
		if (isEmpty(productLineId)) {
			throw new BadRequestException("Must specify product line via ?productLine=");
		}
		final ProductLine productLine = $(new ProductLine(Integer.valueOf(productLineId)));
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
		forEach(allRows(loggedIn, interval.getStart()), agent -> {
			final Query<Opportunity> andAgent = base.and(Opportunity.withAgent(agent));
			final JsonMap row = new JsonMap().$("agent", agent.getLastNameFirstInitial());
			final AtomicBoolean hasOpps = new AtomicBoolean(false);
			EnumSet.of(SURVEY, PHONE_CALL).forEach(source -> {
				final Query<Opportunity> andSource = andAgent.and(withSaleSource(source));
				final Query<Opportunity> andSoldInInterval = andSource.and(soldInInterval(interval));
				final int count = count(andSource.and(createdInInterval(interval)));
				if (count > 0) {
					hasOpps.set(true);
				}
				row.$(enumToCamelCase(source), new JsonMap().$("count", count)
				                                            .$("closes", count(andSoldInInterval))
				                                            .$("total",
				                                               (ofNullable($$(andSoldInInterval, SUM, Currency.class,
				                                                              "amount"))
						                                               .orElse(ZERO)).doubleValue()));
			});
			row.$("calls", queuesForProductLine.isEmpty()
					? 0
					: count(Call.inInterval(interval)
					            .and(Call.isQueue)
					            .and(Call.withQueueIn(queuesForProductLine))
					            .and(Call.withBlame(agent))));

			meter.increment(agent.getLastNameFirstInitial());
			if (hasOpps.get()) {
				rows.add(row);
			}
		});
		return new JsonMap().$("rows", rows);

	}
}
