package net.inetalliance.sonar.reporting;

import com.callgrove.*;
import com.callgrove.obj.*;
import com.callgrove.types.*;
import net.inetalliance.angular.*;
import net.inetalliance.angular.auth.Auth;
import net.inetalliance.angular.events.*;
import net.inetalliance.angular.exception.*;
import net.inetalliance.funky.*;
import net.inetalliance.log.*;
import net.inetalliance.log.progress.*;
import net.inetalliance.potion.*;
import net.inetalliance.potion.cache.*;
import net.inetalliance.potion.query.*;
import net.inetalliance.types.json.*;
import net.inetalliance.util.security.auth.*;
import org.joda.time.*;

import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;
import java.util.*;
import java.util.stream.*;

import static java.lang.String.*;
import static java.util.Collections.*;
import static java.util.stream.Collectors.*;
import static net.inetalliance.funky.StringFun.*;
import static net.inetalliance.types.www.ContentType.*;

public abstract class CachedGroupingRangeReport<R, G>
		extends AngularServlet {

	private static final transient Log log = Log.getInstance(CachedGroupingRangeReport.class);
	private final String groupParam;
	private final String[] extraParams;
	private RedisJsonCache cache;

	CachedGroupingRangeReport(final String groupParam, final String... extraParams) {
		this.groupParam = groupParam;
		this.extraParams = extraParams;
	}

	protected abstract String getGroupLabel(final G group);

	protected abstract String getId(final R row);

	protected abstract Query<R> allRows(final Agent loggedIn, final DateTime intervalStart);

	@Override
	public final void get(final HttpServletRequest request, final HttpServletResponse response)
			throws Exception {
		final Authorized authorized = Auth.getAuthorized(request);
		if (authorized == null || !authorized.isAuthorized("reports")) {
			throw new ForbiddenException("You do not have access to reports");
		}
		final Agent loggedIn = Locator.$(new Agent(authorized.getPhone()));

		final Interval interval = Callgrove.getInterval(request);
		final DateMidnight start = interval.getStart().toDateMidnight();
		final DateMidnight end = interval.getEnd().toDateMidnight();
		final String[] mode = request.getParameterValues("mode");
		final String[] contactTypesParam = request.getParameterValues("contactTypes");
		String[] groupParams = request.getParameterValues(groupParam);
		if (groupParams == null) {
			groupParams = new String[]{};
		}
    final String[] callCentersParam = request.getParameterValues("callCenters");
		final String q =
				format("report:%s,user:%s,start:%s,end:%s,%s:%s,mode:%s,contactTypes:%s,callCenters:%s,%s",
          getClass().getSimpleName(),
				       !loggedIn.isManager() && loggedIn.isTeamLeader() ? loggedIn.key : "admin",
				       Callgrove.simple.print(start),
				       Callgrove.simple.print(end), groupParam, String.join(",", groupParams),
				       mode == null ? "" : String.join(",", mode),
				       contactTypesParam == null ? "" : String.join(",", contactTypesParam),
          callCentersParam == null ? "" : String.join(",", callCentersParam),
				       Arrays.stream(extraParams).map(s -> format("%s:%s", s, request.getParameter(s))).collect(joining(",")));
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
						if (!groups.add(getGroup(groupParams, abbreviation))) {
							throw new BadRequestException("could not find %s for %s", groupParam, abbreviation);
						}
					}
				} else {
					groups = emptySet();
				}

				final EnumSet<SaleSource> sources =
						mode == null || mode.length == 0 || (mode.length == 1 && "all".equals(mode[0]))
								? EnumSet.noneOf(SaleSource.class)
								: Arrays.stream(mode)
								        .map(s -> StringFun.camelCaseToEnum(SaleSource.class, s))
								        .collect(Collectors.toCollection(() -> EnumSet.noneOf(SaleSource.class)));
				final EnumSet<ContactType> contactTypes =
						contactTypesParam == null || contactTypesParam.length == 0 || (contactTypesParam.length == 1 && "all".equals(
								contactTypesParam[0]))
								? EnumSet.noneOf(ContactType.class)
								: Arrays.stream(contactTypesParam)
								        .map(s -> StringFun.camelCaseToEnum(ContactType.class, s))
								        .collect(Collectors.toCollection(() -> EnumSet.noneOf(ContactType.class)));
				ProgressHandler.$.start(authorized.getPhone(), response,
				                        getJobSize(loggedIn, groupParams.length, interval.getStart()), meter -> {
							final JsonMap map = generate(sources, contactTypes, loggedIn, meter, start, end, groups, extras);
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

	protected abstract G getGroup(final String[] params, String g);

	protected abstract int getJobSize(final Agent loggedIn, final int numGroups, final DateTime intervalStart);

	protected abstract JsonMap generate(final EnumSet<SaleSource> sources, final EnumSet<ContactType> contactTypes,
			final Agent loggedIn, final ProgressMeter meter, final DateMidnight start, final DateMidnight end,
			final Set<G> groups, final Map<String, String> extras);

	@Override
	public void init(final ServletConfig config)
			throws ServletException {
		super.init(config);
		cache = new RedisJsonCache(getClass().getSimpleName());
	}
}
