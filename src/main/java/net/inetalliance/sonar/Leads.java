package net.inetalliance.sonar;

import com.callgrove.obj.Agent;
import com.callgrove.obj.AreaCodeTime;
import com.callgrove.obj.Contact;
import com.callgrove.obj.Opportunity;
import com.callgrove.obj.ProductLine;
import com.callgrove.obj.Site;
import com.callgrove.types.Address;
import com.callgrove.types.SaleSource;
import com.callgrove.types.SalesStage;
import net.inetalliance.angular.Key;
import net.inetalliance.angular.exception.ForbiddenException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.angular.exception.UnauthorizedException;
import net.inetalliance.funky.functors.F1;
import net.inetalliance.funky.functors.Predicate;
import net.inetalliance.funky.functors.math.IntegerMath;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.DelegatingQuery;
import net.inetalliance.potion.query.Join;
import net.inetalliance.potion.query.Query;
import net.inetalliance.potion.query.Search;
import net.inetalliance.sonar.events.ReminderHandler;
import net.inetalliance.sql.ColumnWhere;
import net.inetalliance.sql.DbVendor;
import net.inetalliance.sql.Namer;
import net.inetalliance.sql.OrderBy;
import net.inetalliance.sql.SqlBuilder;
import net.inetalliance.sql.Where;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.DateMidnight;
import org.joda.time.Interval;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.callgrove.obj.Opportunity.Q.*;
import static com.callgrove.types.ContactType.*;
import static com.callgrove.types.SaleSource.*;
import static java.lang.System.*;
import static java.util.EnumSet.*;
import static java.util.regex.Pattern.*;
import static net.inetalliance.funky.functors.types.str.StringFun.*;
import static net.inetalliance.sql.OrderBy.Direction.*;

@WebServlet("/api/lead/*")
public class Leads
  extends ListableModel<Opportunity> {

  private static final Pattern space = compile("[ @.]");
  private static final Pattern spaces = compile("[ ]+");
  private static final Pattern or = compile("( \\| )|( OR )", CASE_INSENSITIVE);
  public static final F1<Opportunity, JsonMap> json;

  static {
    final Info<Opportunity> info = Info.$(Opportunity.class);
    json = new F1<Opportunity, JsonMap>() {
      @Override
      public JsonMap $(final Opportunity o) {
        final JsonMap json = info.toJson(o);
        if (o.id != null) {
          final Contact c = o.getContact();
          json.put("extra", new JsonMap()
            .$("contact", new JsonMap()
              .$("name", c.getFullName())
              .$("dealer", c.getContactType() == DEALER))
            .$("productLine", new JsonMap()
              .$("name", o.getProductLine().getName())
              .$("uri", o.getSite().getWebpages().get(o.getProductLine())))
            .$("assignedTo", new JsonMap()
              .$("name",
                o.getSource() == ONLINE ? "Web Order" :
                  o.getAssignedTo().getLastNameFirstInitial()))
            .$("site", new JsonMap()
              .$("name", o.getSite().getName())
              .$("uri", o.getSite().getUri())));
        }
        final Contact contact = o.getContact();
        final Address shipping = contact.getShipping();
        final Address billing = contact.getBilling();
        final String phone = Predicate.notNull.find(shipping == null ? null : shipping.getPhone(),
          billing == null ? null : billing.getPhone(),
          contact.getMobilePhone());
        if (!empty.$(phone)) {
          final AreaCodeTime time = AreaCodeTime.getAreaCodeTime(phone);
          json.put("localTime",
            time == null ? null : time.getDateTimeZone().getOffset(currentTimeMillis()));
        }
        return json;
      }
    };
  }

  @Override
  protected Json toJson(final Key<Opportunity> key, final Opportunity opportunity,
                        final HttpServletRequest request) {
    return Leads.json.$(opportunity);
  }

  public Leads() {
    super(Opportunity.class, compile("/api/lead(?:/([^/]*))?"));
  }

  @Override
  protected void delete(final HttpServletRequest request, final HttpServletResponse response) {
    throw new UnsupportedOperationException();
  }

  private static Query<Opportunity> buildSearchQuery(final Query<Opportunity> query,
                                                     String searchQuery) {
    searchQuery = searchQuery.replaceAll("[-()]", "");
    searchQuery = spaces.matcher(searchQuery).replaceAll(" ");
    searchQuery = or.matcher(searchQuery).replaceAll("|");
    final String[] terms = space.split(searchQuery);
    return new DelegatingQuery<Opportunity>(
      query.and(new Query<Opportunity>(Opportunity.class) {
        @Override
        public Where getWhere(final Namer namer, final String table) {
          return new ColumnWhere(table, "contact", namer.name(Contact.class), "id");
        }

        @Override
        public boolean $(final Opportunity arg) {
          return true;
        }
      }).orderBy("combined_rank", DESCENDING, false).and(Search.$(Opportunity.class, terms).or(
        new Join<>(Search.$(Contact.class, terms),
          Opportunity.class,
          F1.wrap(Opportunity::getContact))))) {
      @Override
      public Iterable<Object> build(final SqlBuilder sql, final Namer namer,
                                    final DbVendor vendor, final String table) {
        if (sql.aggregateFields.length == 0) {
          sql.addColumn(null,
            "ts_rank(Opportunity.document,Opportunity_query) + ts_rank(Contact.document," +
              "Contact_query) AS combined_rank"
          );
        }
        return super.build(sql, namer, vendor, table);
      }

      @Override
      public String getQuerySource() {
        return 'S' + super.getQuerySource();
      }
    };
  }

  @Override
  public F1<Opportunity, ? extends Json> toJson(final HttpServletRequest request) {
    return json;

  }

  static JsonMap getFilters(final HttpServletRequest request) {
    final JsonMap filters = new JsonMap();
    final String[] ss = request.getParameterValues("s");
    if (ss != null && ss.length > 0) {
      final JsonMap labels = new JsonMap();
      for (final String s : ss) {
        final Site site = Locator.$(new Site(new Integer(s)));
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
        if (source == null) {
          throw new NotFoundException("Could not find sale source with key %s", sourceKey);
        }
        labels.put(sourceKey, source.getLocalizedName().toString());
      }
      filters.put("src", labels);
    }
    final String[] pls = request.getParameterValues("pl");
    if (pls != null && pls.length > 0) {
      final JsonMap labels = new JsonMap();
      for (final String pl : pls) {
        final ProductLine productLine = Locator.$(new ProductLine(new Integer(pl)));
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

  @Override
  protected Json getAll(final HttpServletRequest request) {
    final JsonMap map = (JsonMap) super.getAll(request);
    map.$("filters", getFilters(request));
    return map;

  }

  @Override
  protected Json update(final Key<Opportunity> key, final HttpServletRequest request,
                        final HttpServletResponse response,
                        final Opportunity opportunity, final JsonMap data)
    throws IOException {
    try {
      return super.update(key, request, response, opportunity, data);
    } finally {
      if (data.containsKey("reminder") && ReminderHandler.$ != null) {
        ReminderHandler.$.onConnect(opportunity.getAssignedTo().key);
      }
    }
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
      ? active.negate().orderBy(sort.field, sort.direction)
      : review
      ? Query.all(Opportunity.class).orderBy(sort.field, sort.direction)
      : Opportunity.Q.withAgent(Startup.getAgent(request)).orderBy(sort.field, sort.direction);
    query = query.and(webhook ? Opportunity.Q.webhook : Opportunity.Q.noWebhook);
    final String[] pls = request.getParameterValues("pl");
    if (pls != null && pls.length > 0) {
      query = query
        .and(Opportunity.Q.withProductLineIn(IntegerMath.$.parse.copyTo(pls,
          new ArrayList<>(pls.length))));
    }
    final String[] ss = request.getParameterValues("s");
    if (ss != null && ss.length > 0) {
      query = query.and(Opportunity.Q.withSiteIdIn(IntegerMath.$.parse.copyTo(ss,
        new ArrayList<>(ss.length))));
    }
    final String[] sources = request.getParameterValues("src");
    if (sources != null && sources.length > 0) {
      query =
        query.and(Opportunity.Q
          .withSources(
            Arrays
              .stream(sources)
              .map(SaleSource::valueOf)
              .collect(Collectors.toCollection(() -> EnumSet.noneOf(SaleSource.class)))));
    }
    if (support || review) {
      final SalesStage stage = getParameter(request, SalesStage.class, "stage");
      if (stage != null) {
        query = query.and(withStages(of(stage)));
      }
      final String[] as = request.getParameterValues("a");
      if (as != null && as.length > 0) {
        if (review && loggedIn.isTeamLeader()) {
          final Set<String> viewableKeys = Agent.F.key.map(loggedIn.getViewableAgents());
          for (final String a : as) {
            if (!viewableKeys.contains(a)) {
              throw new ForbiddenException("%s tried to look at non-subordinates: %s in %s",
                loggedIn.key,
                a,
                as);
            }
          }
        }
        query = query.and(Opportunity.Q.withAgentKeyIn(Arrays.asList(as)));
      } else if (review) {
        query = query.and(Opportunity.Q.withAgentIn(loggedIn.getViewableAgents()));
      }
    }
    final Range ec = getParameter(request, Range.class, "ec");
    if (ec != null) {
      query = query.and(Opportunity.Q.estimatedCloseInInterval(ec.toInterval()));
    }
    final Range sd = getParameter(request, Range.class, "sd");
    if (sd != null) {
      query = query.and(Opportunity.Q.soldInInterval(sd.toInterval()));
    }
    final Range c = getParameter(request, Range.class, "c");
    if (c != null) {
      query = query.and(Opportunity.Q.createdInInterval(c.toInterval()));
    }
    final String q = request.getParameter("q");
    if (empty.$(q)) {
      return (support || review) ? query : query.and(active);
    }
    return buildSearchQuery(query, q);
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
