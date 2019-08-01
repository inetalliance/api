package net.inetalliance.sonar.reporting;

import static java.lang.String.format;
import static java.util.Collections.emptySet;
import static java.util.stream.Collectors.joining;
import static net.inetalliance.funky.StringFun.isEmpty;
import static net.inetalliance.types.www.ContentType.JSON;

import com.callgrove.Callgrove;
import com.callgrove.obj.Agent;
import com.callgrove.obj.CallCenter;
import com.callgrove.types.ContactType;
import com.callgrove.types.SaleSource;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.auth.Auth;
import net.inetalliance.angular.events.ProgressHandler;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.ForbiddenException;
import net.inetalliance.funky.Funky;
import net.inetalliance.funky.StringFun;
import net.inetalliance.log.Log;
import net.inetalliance.log.progress.ProgressMeter;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.cache.RedisJsonCache;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.util.security.auth.Authorized;
import org.joda.time.DateMidnight;
import org.joda.time.DateTime;
import org.joda.time.Interval;

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

  protected abstract Query<R> allRows(final Set<G> groups, final Agent loggedIn,
      final DateTime intervalStart);

  static String getSingleExtra(final Map<String, String[]> extras, final String extra,
      final String defaultValue) {
    final String[] values = extras.get(extra);
    return values == null || values.length == 0 ? defaultValue : values[0];
  }

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
    final String[] callCentersParam = request.getParameterValues("callCenter");
    final Collection<CallCenter> callCenters = Optional.ofNullable(callCentersParam)
        .map(array -> Arrays.stream(array)
            .map(key -> Locator.$(new CallCenter(Integer.parseInt(key))))
            .flatMap(callCenter -> Funky.stream(callCenter.toBreadthFirstIterator()))
            .collect(Collectors.toList()))
        .orElse(Collections.emptyList());

    final StringBuilder extra = new StringBuilder();
    for (String extraParam : extraParams) {
      final String[] extraValues = request.getParameterValues(extraParam);
      if (extraValues != null) {
        extra.append(",")
            .append(
                Arrays.stream(extraValues)
                    .map(v -> extraParam + ":" + v)
                    .collect(joining(",")));
      }
    }

    final String q =
        format("report:%s,user:%s,start:%s,end:%s,%s:%s,mode:%s,contactTypes:%s,callCenters:%s%s",
            getClass().getSimpleName(),
            !loggedIn.isManager() && loggedIn.isTeamLeader() ? loggedIn.key : "admin",
            Callgrove.simple.print(start),
            Callgrove.simple.print(end), groupParam, String.join(",", groupParams),
            mode == null ? "" : String.join(",", mode),
            contactTypesParam == null ? "" : String.join(",", contactTypesParam),
            callCentersParam == null ? "" : String.join(",", callCentersParam),
            extra.toString());
    final Map<String, String[]> extras = new HashMap<>(extraParams.length);
    for (final String extraParam : extraParams) {
      extras.put(extraParam, request.getParameterValues(extraParam));
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
            contactTypesParam == null || contactTypesParam.length == 0 || (
                contactTypesParam.length == 1 && "all".equals(
                    contactTypesParam[0]))
                ? EnumSet.noneOf(ContactType.class)
                : Arrays.stream(contactTypesParam)
                    .map(s -> StringFun.camelCaseToEnum(ContactType.class, s))
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(ContactType.class)));
        ProgressHandler.$.start(authorized.getPhone(), response,
            getJobSize(loggedIn, groups, interval.getStart()), meter -> {
              final JsonMap map = generate(sources, contactTypes, loggedIn, meter, start, end,
                  groups, callCenters, extras);
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

  protected abstract int getJobSize(final Agent loggedIn, final Set<G> groups,
      final DateTime intervalStart);

  protected abstract JsonMap generate(final EnumSet<SaleSource> sources,
      final EnumSet<ContactType> contactTypes,
      final Agent loggedIn, final ProgressMeter meter, final DateMidnight start,
      final DateMidnight end,
      final Set<G> groups, Collection<CallCenter> callCenters, final Map<String, String[]> extras);

  @Override
  public void init(final ServletConfig config)
      throws ServletException {
    super.init(config);
    cache = new RedisJsonCache(getClass().getSimpleName());
  }
}
