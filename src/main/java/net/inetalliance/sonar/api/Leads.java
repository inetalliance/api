package net.inetalliance.sonar.api;

import com.ameriglide.phenix.core.Strings;
import com.callgrove.obj.*;
import com.callgrove.types.ContactType;
import com.callgrove.types.SaleSource;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.val;
import net.inetalliance.angular.Key;
import net.inetalliance.angular.exception.ForbiddenException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.angular.exception.UnauthorizedException;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.DelegatingQuery;
import net.inetalliance.potion.query.Join;
import net.inetalliance.potion.query.Query;
import net.inetalliance.potion.query.Search;
import net.inetalliance.sonar.ListableModel;
import net.inetalliance.sonar.events.ReminderHandler;
import net.inetalliance.sql.*;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.ameriglide.phenix.core.Strings.isEmpty;
import static com.ameriglide.phenix.core.Strings.isNotEmpty;
import static com.callgrove.obj.Opportunity.*;
import static com.callgrove.types.ContactType.DEALER;
import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static java.util.regex.Pattern.compile;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static net.inetalliance.sql.OrderBy.Direction.ASCENDING;
import static net.inetalliance.sql.OrderBy.Direction.DESCENDING;

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
        val json = Info.$(o).toJson(o);
        if (o.id != null) {
            val c = o.getContact();
            json.put("extra", new JsonMap().$("contact", new JsonMap().$("name", c.getFullName())
                            .$("state", c.getState() == null
                                    ? null
                                    : c.getState().getAbbreviation())
                            .$("dealer", c.getContactType() == DEALER))
                    .$("amountNoCents", o.getAmount().toStringNoCents())
                    .$("productLine", new JsonMap().$("name", o.getProductLine().getName())
                            .$("abbreviation",
                                    o.getProductLine().getAbbreviation())
                            .$("uri", o.getSite()
                                    .getWebpages()
                                    .get(o.getProductLine())))
                    .$("assignedTo", new JsonMap()
                            .$("name", o.getAssignedTo().getFullName())
                            .$("key", o.getAssignedTo().key)
                    )
                    .$("site", new JsonMap().$("name", o.getSite().getName())
                            .$("abbreviation", o.getSite().getAbbreviation())
                            .$("uri", o.getSite().getUri())));
        }
        val contact = o.getContact();
        val phone = contact.getPhone();
        if (isNotEmpty(phone)) {
            val time = AreaCodeTime.getAreaCodeTime(phone);
            if (time != null) {
                var tz = time.getLocalDateTimeZone();
                json.put("localTime",
                        TimeUnit.SECONDS.toMillis(tz.getRules().getOffset(Instant.now()).getTotalSeconds()));

            }
        }
        return json;

    }

    static JsonMap getFilters(final HttpServletRequest request) {
        val filters = new JsonMap();
        val ss = request.getParameterValues("s");
        if (ss != null && ss.length > 0) {
            val labels = new JsonMap();
            for (val s : ss) {
                val site = Locator.$(new Site(Integer.valueOf(s)));
                if (site == null) {
                    throw new NotFoundException("Could not find site with id %s", s);
                }
                labels.put(s, site.getAbbreviation());
            }
            filters.put("s", labels);
        }
        val contactTypes = request.getParameterValues("type");
        if (contactTypes != null && contactTypes.length > 0) {
            val labels = new JsonMap();
            for (val contactTypeName : contactTypes) {
                val contactName = ContactType.valueOf(contactTypeName);
                labels.put(contactTypeName, contactName.getLocalizedName().toString());
            }
            filters.put("type", labels);
        }
        val sources = request.getParameterValues("src");
        if (sources != null && sources.length > 0) {
            val labels = new JsonMap();
            for (val sourceKey : sources) {
                val source = SaleSource.valueOf(sourceKey);
                labels.put(sourceKey, source.getLocalizedName().toString());
            }
            filters.put("src", labels);
        }
        val pls = request.getParameterValues("pl");
        if (pls != null && pls.length > 0) {
            val labels = new JsonMap();
            for (val pl : pls) {
                val productLine = Locator.$(new ProductLine(Integer.valueOf(pl)));
                if (productLine == null) {
                    throw new NotFoundException("Could not find product line with id %s", pl);
                }
                labels.put(pl, productLine.getName());
            }
            filters.put("pl", labels);
        }
        val as = request.getParameterValues("a");
        if (as != null && as.length > 0) {
            val labels = new JsonMap();
            for (val a : as) {
                val agent = Locator.$(new Agent(a));
                if (agent == null) {
                    throw new NotFoundException("Could not find agent with key %s", a);
                }
                labels.put(a, agent.getFirstNameLastInitial());
            }
            filters.put("a", labels);
        }
        return filters;

    }

    public static Query<Opportunity> buildSearchQuery(final Query<Opportunity> query,
                                                      String searchQuery) {
        searchQuery = searchQuery.replaceAll("[-()]", "");
        searchQuery = spaces.matcher(searchQuery).replaceAll(" ");
        searchQuery = or.matcher(searchQuery).replaceAll("|");
        val terms = space.split(searchQuery);
        val delegate = query
                .and(new Query<>(Opportunity.class,
                        _ -> {
                            throw new UnsupportedOperationException();
                        },
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
        val support = request.getParameter("support") != null;
        val review = request.getParameter("review") != null;
        val asap = request.getParameter("asap") != null;
        val sort = SortField.from(request);
        val loggedIn = Startup.getAgent(request);
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
        val pls = request.getParameterValues("pl");
        if (pls != null && pls.length > 0) {
            query = query
                    .and(withProductLineIdIn(Arrays.stream(pls).map(Integer::valueOf).collect(toList())));
        }
        val ss = request.getParameterValues("s");
        if (ss != null && ss.length > 0) {
            query = query.and(withSiteIdIn(Arrays.stream(ss).map(Integer::valueOf).collect(toList())));
        }
        val contactTypes = request.getParameterValues("type");
        if (contactTypes != null && contactTypes.length > 0) {
            query = query.and(Opportunity.withContactTypes(Arrays.stream(contactTypes)
                    .map(ContactType::valueOf)
                    .collect(Collectors.toCollection(
                            () -> EnumSet.noneOf(ContactType.class)))));
        }

        val sources = request.getParameterValues("src");
        if (sources != null && sources.length > 0) {
            query = query.and(Opportunity.withSources(Arrays.stream(sources)
                    .map(SaleSource::valueOf)
                    .collect(Collectors.toCollection(
                            () -> EnumSet.noneOf(SaleSource.class)))));
        }

        val q = request.getParameter("q");
        var onlySold = false;

        val stage = request.getParameter("st");
        if (Strings.isNotEmpty(stage)) {
            query = switch (stage) {
                case "CLOSED" -> query.and(isClosed);
                case "SOLD" -> {
                    onlySold = true;
                    yield query.and(isSold);
                }
                case "DEAD" -> query.and(isDead);
                default -> query.and(isActive);
            };
        } else if (isEmpty(q) && !(support || review)) {
            query = query.and(isActive);
        }

        if (support || review || asap) {
            val as = request.getParameterValues("a");
            if (as != null && as.length > 0) {
                if (review && loggedIn.isTeamLeader()) {
                    var viewableKeys = loggedIn.getViewableAgents().stream().map(a -> a.key)
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
            if (asap) {
                query = query.and(uncontacted).orderBy("created", ASCENDING);
            }
        }
        val ec = getParameter(request, Range.class, "ec");
        if (ec != null) {
            if (onlySold) {
                query = query.and(Opportunity.soldInInterval(ec.toDateTimeInterval()));

            } else {
                query = query.and(Opportunity.estimatedCloseInInterval(ec.toDateTimeInterval()));
            }
        }
        val sd = getParameter(request, Range.class, "sd");
        if (sd != null) {
            query = query.and(Opportunity.soldInInterval(sd.toDateTimeInterval()));
        }
        val c = getParameter(request, Range.class, "c");
        if (c != null) {
            query = query.and(Opportunity.createdInInterval(c.toDateTimeInterval()));
        }
        if (isEmpty(q)) {
            return query;
        }
        return buildSearchQuery(query, q);
    }

    @Override
    protected void delete(final HttpServletRequest request, final HttpServletResponse response) {
        throw new UnsupportedOperationException();
    }

    @Override
    protected Json update(final Key<Opportunity> key, final HttpServletRequest request,
                          final HttpServletResponse response, final Opportunity opportunity, final JsonMap data) {
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
        val map = (JsonMap) super.getAll(request);
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
            val raw = request.getParameter("sort");
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
            public DateInterval toDateInterval() {
                return new DateTimeInterval(LocalDate.now(),LocalDate.now().plusDays(1)).toDateInterval();
            }
        },
        WEEK() {
            @Override
            public DateInterval toDateInterval() {
                val start = LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.of(1)));
                return new DateInterval(start, start.plusWeeks(1));
            }
        },
        MONTH() {
            @Override
            public DateInterval toDateInterval() {
                val start = LocalDate.now().withDayOfMonth(1);
                return new DateInterval(start, start.plusMonths(1));
            }
        },
        YEAR() {
            @Override
            public DateInterval toDateInterval() {
                val start = LocalDate.now().withDayOfYear(1);
                return new DateInterval(start, start.plusYears(1));
            }
        },
        N30() {
            public DateInterval toDateInterval() {
                val start = LocalDate.now();
                return new DateInterval(start, start.plusDays(30));
            }
        },
        N90() {
            public DateInterval toDateInterval() {
                val start = LocalDate.now();
                return new DateInterval(start, start.plusDays(90));
            }
        },
        L30() {
            @Override
            public DateInterval toDateInterval() {
                val start = LocalDate.now();
                return new DateInterval(start.minusDays(30), start);
            }
        },
        L90() {
            @Override
            public DateInterval toDateInterval() {
                val start = LocalDate.now();
                return new DateInterval(start.minusDays(90), start);
            }
        };

        abstract public DateInterval toDateInterval();

        public DateTimeInterval toDateTimeInterval() {
            return toDateInterval().atStartOfDay();
        }
    }
}
