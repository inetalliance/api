package net.inetalliance.sonar.reporting;

import com.ameriglide.phenix.core.Log;
import com.callgrove.Callgrove;
import com.callgrove.obj.*;
import com.callgrove.types.ContactType;
import com.callgrove.types.SaleSource;
import lombok.val;
import net.inetalliance.angular.exception.UnauthorizedException;
import net.inetalliance.beejax.messages.Category;
import net.inetalliance.beejax.messages.ProductChain;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sql.DateTimeInterval;
import net.inetalliance.sql.TwoTuple;
import net.inetalliance.types.Currency;
import net.inetalliance.types.Named;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.types.json.JsonString;
import net.inetalliance.util.ProgressMeter;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Stream;

import static com.callgrove.Callgrove.getReportingInterval;
import static com.callgrove.obj.Opportunity.*;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toSet;
import static net.inetalliance.potion.Locator.*;
import static net.inetalliance.sql.Aggregate.SUM;

public abstract class Revenue<R extends Named>
        extends CachedGroupingRangeReport<R, Category> {

    private static final Log log = new Log();

    private static final Map<Integer, Integer> bjxIds = new HashMap<>();
    private static final Map<String[], Collection<Category>> cache = new HashMap<>();

    Revenue() {
        super("tags", "p");
    }

    private static Integer getCallgroveSiteId(final Integer beejaxId) {
        return bjxIds.computeIfAbsent(beejaxId,
                id -> Optional.ofNullable($1(Site.withBeejaxId(id))).map(s -> s.id).orElse(null));
    }

    private static TwoTuple<Integer, Integer> convertChain(final ProductChain chain) {
        return new TwoTuple<>(getCallgroveSiteId(chain.site), chain.id);
    }

    static Map<Integer, Query<? super Opportunity>> getCategoryQueries(
            final Set<Category> categories) {
        return getCategoryQueries(null, categories);
    }

    private static Map<Integer, Query<? super Opportunity>> getCategoryQueries(
            final ProgressMeter meter,
            final Set<Category> categories) {
        final Map<Integer, Query<? super Opportunity>> categoryQueries = new HashMap<>(
                categories.size());
        for (val category : categories) {
            if (meter != null) {
                meter.setLabel("Fetching product ids for \"%s\"", category.name);
            }
            val productIds = Callgrove.beejax.getProductsWithTag(category.id);
            categoryQueries.put(category.id, withSiteAndBeejaxProductIdIn(
                    productIds.stream().map(Revenue::convertChain).filter(t -> t.a != null)
                            .collect(toSet())));

            if (meter != null) {
                meter.increment();
            }
        }
        return categoryQueries;
    }

    static Category getCategory(final String[] params, final Integer key) {
        return cache.computeIfAbsent(params, p -> Callgrove.beejax.categoryLookup(
                        Stream.of(p).map(Integer::valueOf).collect(toSet())))
                .stream()
                .filter(c -> key.equals(c.id))
                .findFirst()
                .orElse(null);

    }

    protected abstract JsonMap addRowInfo(final JsonMap json, final R row);

    @Override
    protected JsonMap generate(final Set<SaleSource> sources,
                               final Set<ContactType> contactTypes,
                               final Agent loggedIn, final ProgressMeter meter, final LocalDate start,
                               final LocalDate end,
                               final Set<Category> categories, Collection<CallCenter> callCenters,
                               final Map<String, String[]> extras) {
        if (loggedIn == null || !(loggedIn.isManager() || loggedIn.isTeamLeader())) {
            log.warn(() -> "%s tried to access %s".formatted(loggedIn == null ? "Nobody?" : loggedIn.key,
                    getClass().getSimpleName()));
            throw new UnauthorizedException();
        }

        val interval = getReportingInterval(start, end);
        val productLine = Info.$(ProductLine.class).lookup(
                getSingleExtra(extras, "p", null));

        val categoryQueries = getCategoryQueries(meter,
                categories);

        var oppQuery =
                soldInInterval(interval).and(withSources(sources)).and(withContactTypes(contactTypes));
        if (productLine != null) {
            oppQuery = oppQuery.and(withProductLine(productLine));
        }
        val finalOppQuery = oppQuery;
        val rows = new JsonList();
        Locator.forEach(allRows(categories, loggedIn, interval.start()), row -> {
            val rowData = new JsonList(categories.size());
            val rowQuery = finalOppQuery.and(oppsForRow(row));
            var opps = 0;
            for (val category : categories) {
                val query = rowQuery.and(categoryQueries.get(category.id));
                val count = count(query);
                opps += count;
                rowData.add(addRowInfo(new JsonMap().$("type", CellType.DATA)
                        .$("category", category.id)
                        .$("count", count)
                        .$("amount", count == 0
                                ? 0D
                                : $$(query, SUM, Currency.class, "amount").doubleValue()), row));
            }
            if (productLine != null) {
                val noProductQuery = rowQuery.and(hasBeejaxProduct.negate());
                var count = count(noProductQuery);
                opps += count;
                rowData.add(addRowInfo(new JsonMap().$("type", CellType.NO_PRODUCT)
                                .$("count", count)
                                .$("amount", count == 0
                                        ? 0
                                        : $$(noProductQuery, SUM, Currency.class, "amount").doubleValue()),
                        row));
                val notMatchingQuery =
                        rowQuery.and(hasBeejaxProduct)
                                .and(Query.or(Opportunity.class, categoryQueries.values()).negate());
                count = count(notMatchingQuery);
                opps += count;
                rowData.add(addRowInfo(new JsonMap().$("type", CellType.NOT_MATCHING)
                                .$("count", count)
                                .$("amount", count == 0
                                        ? 0
                                        : $$(notMatchingQuery, SUM, Currency.class, "amount").doubleValue()),
                        row));
            }
            if (opps > 0) {
                rows.add(new JsonMap().$("name", row.getName()).$("data", rowData));
            }
            meter.increment(row.getName());
        });
        val labels =
                categories.stream().map(this::getGroupLabel).map(JsonString::new)
                        .collect(toCollection(JsonList::new));
        if (productLine != null) {
            labels.add(productLine.getName() + " No Product");
            labels.add("Other " + productLine.getName());
        }
        return new JsonMap().$("labels", labels).$("rows", rows);
    }

    protected abstract Query<Opportunity> oppsForRow(final R row);

    @Override
    protected String getGroupLabel(final Category category) {
        return category.name;
    }

    /**
     * Fetch all categories at once from beejax and cache the results
     */
    @Override
    protected Category getGroup(final String[] params, final String arg) {
        return getCategory(params, Integer.valueOf(arg));
    }

    @Override
    protected int getJobSize(final Agent loggedIn, final Set<Category> categories,
                             final DateTimeInterval interval) {
        return count(allRows(categories, loggedIn, interval.start())) + categories.size();
    }

    public enum CellType {
        DATA,
        NO_PRODUCT,
        NOT_MATCHING
    }
}
