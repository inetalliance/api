package net.inetalliance.sonar.reporting;

import com.ameriglide.phenix.core.Enums;
import com.ameriglide.phenix.core.Iterators;
import com.ameriglide.phenix.core.Log;
import com.callgrove.Callgrove;
import com.callgrove.obj.Agent;
import com.callgrove.obj.CallCenter;
import com.callgrove.types.ContactType;
import com.callgrove.types.SaleSource;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.val;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.auth.Auth;
import net.inetalliance.angular.events.ProgressHandler;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.ForbiddenException;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.cache.RedisJsonCache;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sql.DateTimeInterval;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.util.ProgressMeter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.ameriglide.phenix.core.Strings.isEmpty;
import static java.lang.String.format;
import static java.util.Collections.emptySet;
import static java.util.stream.Collectors.joining;
import static net.inetalliance.types.www.ContentType.JSON;

public abstract class CachedGroupingRangeReport<R, G>
        extends AngularServlet {

    private static final Log log = new Log();
    private final String groupParam;
    private final String[] extraParams;
    private RedisJsonCache cache;

    CachedGroupingRangeReport(final String groupParam, final String... extraParams) {
        this.groupParam = groupParam;
        this.extraParams = extraParams;
    }

    protected abstract String getGroupLabel(final G group);

    protected abstract String getId(final R row);

    protected abstract Query<R> allRows(final Set<G> groups, final Agent loggedIn,
                                        final LocalDateTime intervalStart);

    static String getSingleExtra(final Map<String, String[]> extras, final String extra,
                                 final String defaultValue) {
        val values = extras.get(extra);
        return values == null || values.length == 0 ? defaultValue : values[0];
    }

    @Override
    public final void get(final HttpServletRequest request, final HttpServletResponse response)
            throws Exception {
        val authorized = Auth.getAuthorized(request);
        if (authorized == null || !authorized.isAuthorized("reports")) {
            log.error("%s does not have access to reports", authorized == null ? "null" : authorized.getName());
            throw new ForbiddenException("You do not have access to reports");
        }
        val loggedIn = Locator.$(new Agent(authorized.getPhone()));

        var interval = Callgrove.getInterval(request);
        var start = interval.start().toLocalDate();
        var end = interval.end().toLocalDate();
        val mode = request.getParameterValues("mode");
        val contactTypesParam = request.getParameterValues("contactTypes");
        var groupParams = request.getParameterValues(groupParam);
        if (groupParams == null) {
            groupParams = new String[]{};
        }
        val callCentersParam = request.getParameterValues("callCenter");
        final Collection<CallCenter> callCenters = Optional.ofNullable(callCentersParam)
                .map(array -> Arrays.stream(array)
                        .map(key -> Locator.$(new CallCenter(Integer.parseInt(key))))
                        .flatMap(callCenter -> Iterators.stream(callCenter.toBreadthFirstIterator()))
                        .collect(Collectors.toList()))
                .orElse(Collections.emptyList());

        val extra = new StringBuilder();
        for (var extraParam : extraParams) {
            val extraValues = request.getParameterValues(extraParam);
            if (extraValues != null) {
                extra.append(",")
                        .append(
                                Arrays.stream(extraValues)
                                        .map(v -> extraParam + ":" + v)
                                        .collect(joining(",")));
            }
        }

        val q =
                format("report:%s,user:%s,start:%s,end:%s,%s:%s,mode:%s,contactTypes:%s,callCenters:%s%s",
                        getClass().getSimpleName(),
                        !loggedIn.isManager() && loggedIn.isTeamLeader() ? loggedIn.key : "admin",
                        Callgrove.simple.format(start),
                        Callgrove.simple.format(end), groupParam, String.join(",", groupParams),
                        mode == null ? "" : String.join(",", mode),
                        contactTypesParam == null ? "" : String.join(",", contactTypesParam),
                        callCentersParam == null ? "" : String.join(",", callCentersParam),
                        extra.toString());
        final Map<String, String[]> extras = new HashMap<>(extraParams.length);
        for (val extraParam : extraParams) {
            extras.put(extraParam, request.getParameterValues(extraParam));
        }

        val cached = cache.get(q);
        if (isEmpty(cached)) {
            if (request.getParameter("cacheCheck") != null) {
                log.debug("Returning empty cache result for %s", q);
                respond(response, JsonMap.singletonMap("cached", false));
            } else {
                final Set<G> groups;
                if (groupParams.length > 0) {
                    groups = new LinkedHashSet<>(groupParams.length);
                    for (val abbreviation : groupParams) {
                        if (!groups.add(getGroup(groupParams, abbreviation))) {
                            throw new BadRequestException("could not find %s for %s", groupParam, abbreviation);
                        }
                    }
                } else {
                    groups = emptySet();
                }

                var sources = RevenueOverTime.getApplicableSources(mode);
                var contactTypes =
                        contactTypesParam == null || contactTypesParam.length == 0 || (
                                contactTypesParam.length == 1 && "all".equals(
                                        contactTypesParam[0]))
                                ? EnumSet.noneOf(ContactType.class)
                                : Arrays.stream(contactTypesParam)
                                .map(s -> Enums.decamel(ContactType.class, s))
                                .collect(Collectors.toCollection(() -> EnumSet.noneOf(ContactType.class)));
                ProgressHandler.$.start(authorized.getPhone(), response,
                        getJobSize(loggedIn, groups, interval), meter -> {
                            val map = generate(sources, contactTypes, loggedIn, meter, start, end,
                                    groups, callCenters, extras);
                            if (end.plusDays(2).isAfter(LocalDate.now())) {
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
            try (val writer = response.getWriter()) {
                writer.write(cached);
                writer.flush();
            }
        }
    }

    protected abstract G getGroup(final String[] params, String g);

    protected abstract int getJobSize(final Agent loggedIn, final Set<G> groups,
                                      final DateTimeInterval intervalStart);

    protected abstract JsonMap generate(final Set<SaleSource> sources,
                                        final Set<ContactType> contactTypes,
                                        final Agent loggedIn, final ProgressMeter meter, final LocalDate start,
                                        final LocalDate end,
                                        final Set<G> groups, Collection<CallCenter> callCenters, final Map<String, String[]> extras);

    @Override
    public void init(final ServletConfig config)
            throws ServletException {
        super.init(config);
        cache = new RedisJsonCache(getClass().getSimpleName());
    }
}
