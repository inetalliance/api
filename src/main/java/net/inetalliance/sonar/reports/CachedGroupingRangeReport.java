package net.inetalliance.sonar.reports;

import com.callgrove.obj.Agent;
import com.callgrove.types.ContactType;
import com.callgrove.types.SaleSource;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.auth.Auth;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.ForbiddenException;
import net.inetalliance.funky.StringFun;
import net.inetalliance.log.Log;
import net.inetalliance.log.progress.ProgressMeter;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.cache.RedisJsonCache;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sonar.events.ProgressHandler;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.util.security.auth.Authorized;
import org.joda.time.DateMidnight;
import org.joda.time.Interval;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.util.*;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.Collections.emptySet;
import static java.util.stream.Collectors.joining;
import static net.inetalliance.funky.StringFun.isEmpty;
import static net.inetalliance.types.www.ContentType.JSON;

public abstract class CachedGroupingRangeReport<R, G>
	extends AngularServlet {

	private RedisJsonCache cache;
	private final String groupParam;
	private final String[] extraParams;
	public static final DateTimeFormatter simple = DateTimeFormat.forPattern("MM-dd-yyyy");

	protected CachedGroupingRangeReport(final String groupParam,
	                                    final String... extraParams) {
		this.groupParam = groupParam;
		this.extraParams = extraParams;
	}

	protected abstract String getGroupLabel(final G group);

	protected abstract String getId(final R row);

	protected abstract Query<R> allRows(final Agent loggedIn);

	protected abstract G getGroup(final String[] params, String g);

	public static Interval getReportingInterval(final DateMidnight start, final DateMidnight end) {
		return new Interval(start, end.plusDays(1).toDateTime().minus(1));
	}

	public static Interval getReportingInterval(final HttpServletRequest request) {
		return getReportingInterval(getInterval(request));
	}

	private static Interval getReportingInterval(final Interval interval) {
		return new Interval(interval.getStart().toDateMidnight(),
			interval.getEnd().toDateMidnight().plusDays(1).toDateTime().minus(1));
	}

	protected abstract int getJobSize(final Agent loggedIn, final int numGroups);

	protected abstract JsonMap generate(final EnumSet<SaleSource> sources,
	                                    final EnumSet<ContactType> contactTypes,
	                                    final Agent loggedIn,
	                                    final ProgressMeter meter, final DateMidnight start,
	                                    final DateMidnight end, final Set<G> groups,
	                                    final Map<String, String> extras);

	public static Interval getInterval(final HttpServletRequest request) {
		final String range = request.getParameter("range");
		final DateMidnight start, end;
		switch (range) {
			case "custom":
				start = simple.parseDateTime(request.getParameter("start")).toDateMidnight();
				end = simple.parseDateTime(request.getParameter("end")).toDateMidnight();
				break;
			case "y":
				end = new DateMidnight();
				start = end.minusDays(1);
				break;
			case "l30":
				end = new DateMidnight().minusDays(1);
				start = end.minusDays(30);
				break;
			case "l60":
				end = new DateMidnight().minusDays(1);
				start = end.minusDays(60);
				break;
			case "l90":
				end = new DateMidnight().minusDays(1);
				start = end.minusDays(90);
				break;
			case "l120":
				end = new DateMidnight().minusDays(1);
				start = end.minusDays(120);
				break;
			case "l6m":
				end = new DateMidnight().minusDays(1);
				start = end.minusMonths(6);
				break;
			case "ly":
				end = new DateMidnight().minusDays(1);
				start = end.minusYears(1);
				break;
			case "mtd":
				end = new DateMidnight().minusDays(1);
				start = end.withDayOfMonth(1);
				break;
			case "ytd":
				end = new DateMidnight().minusDays(1);
				start = end.withDayOfYear(1);
				break;
			case "qtd":
				end = new DateMidnight().minusDays(1);
				start = end.withMonthOfYear(1 + 3 * ((end.getMonthOfYear() - 1) / 3));
				break;
			default:
				throw new BadRequestException("Do not know about date range %s", range);
		}
		return new Interval(start, end);
	}

	@Override
	public final void get(final HttpServletRequest request, final HttpServletResponse response)
		throws Exception {
		final Authorized authorized = Auth.getAuthorized(request);
		if (authorized == null || !authorized.isAuthorized("reports")) {
			throw new ForbiddenException("You do not have access to reports");
		}
		final Agent loggedIn = Locator.$(new Agent(authorized.getPhone()));

		final Interval interval = getInterval(request);
		final DateMidnight start = interval.getStart().toDateMidnight();
		final DateMidnight end = interval.getEnd().toDateMidnight();
		final String[] mode = request.getParameterValues("mode");
		final String[] contactTypesParam = request.getParameterValues("contactTypes");
		String[] groupParams = request.getParameterValues(groupParam);
		if (groupParams == null) {
			groupParams = new String[]{};
		}
		final String q = format("report:%s,user:%s,start:%s,end:%s,%s:%s,mode:%s,contactTypes:%s,%s",
			getClass().getSimpleName(),
			!loggedIn.isManager() && loggedIn.isTeamLeader() ? loggedIn.key : "admin",
			simple.print(start),
			simple.print(end),
			groupParam,
			String.join(",", groupParams),
			mode == null ? "" : String.join(",", mode),
			contactTypesParam == null ? "" : String.join(",", contactTypesParam),
			Arrays.stream(extraParams).map(s -> String.format("%s:%s", s, request.getParameter(s)))
				.collect(joining(",")));
		final Map<String, String> extras = new HashMap<>(extraParams.length);
		for (final String extraParam : extraParams) {
			extras.put(extraParam, request.getParameter(extraParam));
		}

		final String cached = cache.get(q);
		if (isEmpty(cached)) {
			if (request.getParameter("cacheCheck") != null) {
				log.debug("Returning empty cache result for %s", q);
				respond(response, JsonMap.singletonMap("cached", false));
			} else {
				final Set<G> groups;
				if (groupParams.length > 0) {
					groups = new LinkedHashSet<>(groupParams.length);
					for (final String abbreviation : groupParams) {
						if (!groups.add(getGroup(groupParams,abbreviation))) {
							throw new BadRequestException("could not find %s for %s", groupParam, abbreviation);
						}
					}
				} else {
					groups = emptySet();
				}

				final EnumSet<SaleSource> sources =
					mode == null || mode.length == 0 || (mode.length == 1 && "all".equals(mode[0]))
						? EnumSet.noneOf(SaleSource.class)
						: Arrays
						.stream(mode)
						.map(s->StringFun.camelCaseToEnum(SaleSource.class,s))
						.collect(Collectors.toCollection(() -> EnumSet.noneOf(SaleSource.class)));
				final EnumSet<ContactType> contactTypes =
					contactTypesParam == null || contactTypesParam.length == 0 ||
						(contactTypesParam.length == 1 && "all".equals(contactTypesParam[0]))
						? EnumSet.noneOf(ContactType.class)
						: Arrays
						.stream(contactTypesParam)
						.map(s->StringFun.camelCaseToEnum(ContactType.class,s))
						.collect(Collectors.toCollection(() -> EnumSet.noneOf(ContactType.class)));
				ProgressHandler.$.start(authorized.getPhone(), response,
					getJobSize(loggedIn, groupParams.length),
					meter -> {
						final JsonMap map = generate(sources, contactTypes, loggedIn, meter, start, end,
							groups, extras);
						if (end.isAfter(new DateMidnight())) {
							log.debug("Not caching report %s because end is after midnight today", q);
						} else {
							cache.set(q, map);
						}
						return map;
					});
			}
		} else {
			log.debug("Returning cached report result for %s", q);
			response.setContentType(JSON.toString());
			try (final PrintWriter writer = response.getWriter()) {
				writer.write(cached);
				writer.flush();
			}
		}
	}

	@Override
	public void init(final ServletConfig config)
		throws ServletException {
		super.init(config);
		cache = new RedisJsonCache(getClass().getSimpleName());
	}

	private static final transient Log log = Log.getInstance(CachedGroupingRangeReport.class);
}
