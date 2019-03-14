package net.inetalliance.sonar.api;

import static com.callgrove.obj.Opportunity.isActive;
import static com.callgrove.obj.Opportunity.withProductLineIdIn;
import static com.callgrove.obj.Opportunity.withSiteIdIn;
import static com.callgrove.obj.Opportunity.withStages;
import static com.callgrove.types.ContactType.DEALER;
import static com.callgrove.types.SaleSource.ONLINE;
import static java.lang.System.currentTimeMillis;
import static java.util.EnumSet.of;
import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static java.util.regex.Pattern.compile;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static net.inetalliance.funky.StringFun.isEmpty;
import static net.inetalliance.funky.StringFun.isNotEmpty;
import static net.inetalliance.sql.OrderBy.Direction.ASCENDING;
import static net.inetalliance.sql.OrderBy.Direction.DESCENDING;

import com.callgrove.obj.Agent;
import com.callgrove.obj.AreaCodeTime;
import com.callgrove.obj.Contact;
import com.callgrove.obj.Opportunity;
import com.callgrove.obj.ProductLine;
import com.callgrove.obj.Site;
import com.callgrove.types.SaleSource;
import com.callgrove.types.SalesStage;
import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.inetalliance.angular.Key;
import net.inetalliance.angular.exception.ForbiddenException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.angular.exception.UnauthorizedException;
import net.inetalliance.funky.Funky;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.DelegatingQuery;
import net.inetalliance.potion.query.Join;
import net.inetalliance.potion.query.Query;
import net.inetalliance.potion.query.Search;
import net.inetalliance.potion.query.SortedQuery;
import net.inetalliance.sonar.ListableModel;
import net.inetalliance.sonar.events.ReminderHandler;
import net.inetalliance.sql.ColumnWhere;
import net.inetalliance.sql.DbVendor;
import net.inetalliance.sql.Namer;
import net.inetalliance.sql.OrderBy;
import net.inetalliance.sql.SqlBuilder;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.DateMidnight;
import org.joda.time.Interval;

@WebServlet("/api/lead/*")
public class Leads
    extends ListableModel<Opportunity> {

  private static final Pattern space = compile("[ @.]");
  private static final Pattern spaces = compile("[ ]+");
  private static final Pattern or = compile("( \\| )|( OR )", CASE_INSENSITIVE);

  public Leads() {
    super(Opportunity.class, compile("/api/lead(?:/([^/]*))?"));
  }

  public static JsonMap json(Opportunity o) {
    final JsonMap json = Info.$(o).toJson(o);
    if (o.id != null) {
      final Contact c = o.getContact();
      json.put("extra", new JsonMap().$("contact", new JsonMap().$("name", c.getFullName())
          .$("state", c.getState() == null
              ? null
              : c.getState().getAbbreviation())
          .$("dealer", c.getContactType() == DEALER))
          .$("productLine", new JsonMap().$("name", o.getProductLine().getName())
              .$("abbreviation",
                  o.getProductLine().getAbbreviation())
              .$("uri", o.getSite()
                  .getWebpages()
                  .get(o.getProductLine())))
          .$("assignedTo", new JsonMap().$("name", o.getSource() == ONLINE
              ? "Web Order"
              : o.getAssignedTo().getLastNameFirstInitial()))
          .$("site", new JsonMap().$("name", o.getSite().getName())
              .$("abbreviation", o.getSite().getAbbreviation())
              .$("uri", o.getSite().getUri())));
    }
    final Contact contact = o.getContact();
    final String phone = contact.getPhone();
    if (isNotEmpty(phone)) {
      final AreaCodeTime time = AreaCodeTime.getAreaCodeTime(phone);
      json.put("localTime",
          time == null ? null : time.getDateTimeZone().getOffset(currentTimeMillis()));
    }
    return json;

  }

  static JsonMap getFilters(final HttpServletRequest request) {
    final JsonMap filters = new JsonMap();
    final String[] ss = request.getParameterValues("s");
    if (ss != null && ss.length > 0) {
      final JsonMap labels = new JsonMap();
      for (final String s : ss) {
        final Site site = Locator.$(new Site(Integer.valueOf(s)));
        if (site == null) {
          throw new NotFoundException("Could not find site with id %s", s);
        }
        labels.put(s, site.getAbbreviation());
      }
      filters.put("s", labels);
    }
    final String[] sources = request.getParameterValues("src");
    if (sources != null && sources.length > 0) {
      final JsonMap labels = new JsonMap();
      for (final String sourceKey : sources) {
        final SaleSource source = SaleSource.valueOf(sourceKey);
        labels.put(sourceKey, source.getLocalizedName().toString());
      }
      filters.put("src", labels);
    }
    final String[] pls = request.getParameterValues("pl");
    if (pls != null && pls.length > 0) {
      final JsonMap labels = new JsonMap();
      for (final String pl : pls) {
        final ProductLine productLine = Locator.$(new ProductLine(Integer.valueOf(pl)));
        if (productLine == null) {
          throw new NotFoundException("Could not find product line with id %s", pl);
        }
        labels.put(pl, productLine.getName());
      }
      filters.put("pl", labels);
    }
    final String[] as = request.getParameterValues("a");
    if (as != null && as.length > 0) {
      final JsonMap labels = new JsonMap();
      for (final String a : as) {
        final Agent agent = Locator.$(new Agent(a));
        if (agent == null) {
          throw new NotFoundException("Could not find agent with key %s", a);
        }
        labels.put(a, agent.getLastNameFirstInitial());
      }
      filters.put("a", labels);
    }
    return filters;

  }

  private static Query<Opportunity> buildSearchQuery(final Query<Opportunity> query,
      String searchQuery) {
    searchQuery = searchQuery.replaceAll("[-()]", "");
    searchQuery = spaces.matcher(searchQuery).replaceAll(" ");
    searchQuery = or.matcher(searchQuery).replaceAll("|");
    final String[] terms = space.split(searchQuery);
    final SortedQuery<Opportunity> delegate = query
        .and(new Query<>(Opportunity.class, Funky.unsupported(),
            (namer, table) -> new ColumnWhere(table, "contact",
                namer.name(
                    Contact.class),
                "id")))
        .orderBy("combined_rank", DESCENDING, false)
        .and(new Search<>(Opportunity.class, terms).or(
            new Join<>(new Search<>(Contact.class, terms),
                Opportunity.class,
                Opportunity::getContact)));
    return new DelegatingQuery<>(delegate, s -> 'S' + s, Function.identity(), b -> b) {
      @Override
      public Iterable<Object> build(final SqlBuilder sql, final Namer namer, final DbVendor vendor,
          final String table) {
        if (sql.aggregateFields.length == 0) {
          sql.addColumn(null,
              "ts_rank(Opportunity.document,Opportunity_query) + ts_rank(Contact.document," +
                  "Contact_query) AS combined_rank");
        }
        return super.build(sql, namer, vendor, table);
      }

    };
  }

  @Override
  public Json toJson(final HttpServletRequest request, final Opportunity o) {
    return json(o);
  }

  @Override
  public Query<Opportunity> all(final Class<Opportunity> type, final HttpServletRequest request) {
    final boolean support = request.getParameter("support") != null;
    final boolean review = request.getParameter("review") != null;
    final boolean webhook = request.getParameter("webhook") != null;
    final SortField sort = SortField.from(request);
    final Agent loggedIn = Startup.getAgent(request);
    if (loggedIn == null) {
      throw new UnauthorizedException();
    }
    if (review && !(loggedIn.isManager() || loggedIn.isTeamLeader())) {
      throw new ForbiddenException("%s tried to access review section", loggedIn.key);
    }
    Query<Opportunity> query = support
        ? isActive.negate().orderBy(sort.field, sort.direction)
        : review
            ? Query.all(Opportunity.class).orderBy(sort.field, sort.direction)
            : Opportunity.withAgent(Startup.getAgent(request)).orderBy(sort.field, sort.direction);
    query = query.and(webhook ? Opportunity.hasWebhook : Opportunity.noWebhook);
    final String[] pls = request.getParameterValues("pl");
    if (pls != null && pls.length > 0) {
      query = query
          .and(withProductLineIdIn(Arrays.stream(pls).map(Integer::valueOf).collect(toList())));
    }
    final String[] ss = request.getParameterValues("s");
    if (ss != null && ss.length > 0) {
      query = query.and(withSiteIdIn(Arrays.stream(ss).map(Integer::valueOf).collect(toList())));
    }
    final String[] sources = request.getParameterValues("src");
    if (sources != null && sources.length > 0) {
      query = query.and(Opportunity.withSources(Arrays.stream(sources)
          .map(SaleSource::valueOf)
          .collect(Collectors.toCollection(
              () -> EnumSet.noneOf(SaleSource.class)))));
    }

    if (support || review) {
      final SalesStage stage = getParameter(request, SalesStage.class, "stage");
      if (stage != null) {
        query = query.and(withStages(of(stage)));
      }

      final String[] as = request.getParameterValues("a");
      if (as != null && as.length > 0) {
        if (review && loggedIn.isTeamLeader()) {
          Set<String> viewableKeys = loggedIn.getViewableAgents().stream().map(a -> a.key)
              .collect(toSet());
          Arrays.stream(as).filter(s -> !viewableKeys.contains(s)).findFirst().ifPresent(a -> {
            throw new ForbiddenException("%s tried to look at non-subordinates: %s in %s",
                loggedIn.key, a, as);
          });
        }
        query = query.and(Opportunity.withAgentKeyIn(Arrays.asList(as)));
      } else if (review) {
        query = query.and(Opportunity.withAgentIn(loggedIn.getViewableAgents()));
      }
    }
    final Range ec = getParameter(request, Range.class, "ec");
    if (ec != null) {
      query = query.and(Opportunity.estimatedCloseInInterval(ec.toInterval()));
    }
    final Range sd = getParameter(request, Range.class, "sd");
    if (sd != null) {
      query = query.and(Opportunity.soldInInterval(sd.toInterval()));
    }
    final Range c = getParameter(request, Range.class, "c");
    if (c != null) {
      query = query.and(Opportunity.createdInInterval(c.toInterval()));
    }
    final String q = request.getParameter("q");
    if (isEmpty(q)) {
      return (support || review) ? query : query.and(isActive);
    }
    return buildSearchQuery(query, q);
  }

  @Override
  protected void delete(final HttpServletRequest request, final HttpServletResponse response) {
    throw new UnsupportedOperationException();
  }

  @Override
  protected Json update(final Key<Opportunity> key, final HttpServletRequest request,
      final HttpServletResponse response, final Opportunity opportunity, final JsonMap data)
      throws IOException {
    try {
      return super.update(key, request, response, opportunity, data);
    } finally {
      if (data.containsKey("reminder") && ReminderHandler.$ != null) {
        ReminderHandler.$.onConnect(opportunity.getAssignedTo().key);
      }
    }
  }

  @Override
  protected Json toJson(final Key<Opportunity> key, final Opportunity opportunity,
      final HttpServletRequest request) {
    return Leads.json(opportunity);
  }

  @Override
  protected Json getAll(final HttpServletRequest request) {
    final JsonMap map = (JsonMap) super.getAll(request);
    map.$("filters", getFilters(request));
    return map;

  }

  public enum SortField {
    ESTIMATED_CLOSE_ASC("estimatedClose", ASCENDING),
    ESTIMATED_CLOSE_DESC("estimatedClose", DESCENDING);
    private final String field;
    private final OrderBy.Direction direction;

    SortField(final String field, final OrderBy.Direction direction) {
      this.field = field;
      this.direction = direction;
    }

    static SortField from(final HttpServletRequest request) {
      final String raw = request.getParameter("sort");
      if (raw != null) {
        switch (raw) {
          case "estimatedClose":
            return ESTIMATED_CLOSE_ASC;
          case "-estimatedClose":
            return ESTIMATED_CLOSE_DESC;
        }
      }
      return ESTIMATED_CLOSE_DESC;
    }
  }

  @SuppressWarnings("unused")
  public enum Range {
    DAY() {
      @Override
      public Interval toInterval() {
        return new DateMidnight().toInterval();
      }
    },
    WEEK() {
      @Override
      public Interval toInterval() {
        final DateMidnight start = new DateMidnight().withDayOfWeek(1);
        return new Interval(start, start.plusWeeks(1));
      }
    },
    MONTH() {
      @Override
      public Interval toInterval() {
        final DateMidnight start = new DateMidnight().withDayOfMonth(1);
        return new Interval(start, start.plusMonths(1));
      }
    },
    YEAR() {
      @Override
      public Interval toInterval() {
        final DateMidnight start = new DateMidnight().withDayOfYear(1);
        return new Interval(start, start.plusYears(1));
      }
    },
    N30() {
      public Interval toInterval() {
        final DateMidnight start = new DateMidnight();
        return new Interval(start, start.plusDays(30));
      }
    },
    N90() {
      public Interval toInterval() {
        final DateMidnight start = new DateMidnight();
        return new Interval(start, start.plusDays(90));
      }
    },
    L30() {
      @Override
      public Interval toInterval() {
        final DateMidnight start = new DateMidnight();
        return new Interval(start.minusDays(30), start);
      }
    },
    L90() {
      @Override
      public Interval toInterval() {
        final DateMidnight start = new DateMidnight();
        return new Interval(start.minusDays(90), start);
      }
    };

    abstract public Interval toInterval();
  }
}
