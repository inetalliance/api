package net.inetalliance.sonar.reports;

import com.callgrove.Callgrove;
import com.callgrove.obj.Agent;
import com.callgrove.obj.Opportunity;
import com.callgrove.obj.ProductLine;
import com.callgrove.obj.Site;
import com.callgrove.types.ContactType;
import com.callgrove.types.SaleSource;
import net.inetalliance.angular.exception.UnauthorizedException;
import net.inetalliance.beejax.messages.Category;
import net.inetalliance.beejax.messages.ProductChain;
import net.inetalliance.funky.functors.F1;
import net.inetalliance.funky.functors.P1;
import net.inetalliance.funky.functors.adaptor.MapFunctor;
import net.inetalliance.funky.functors.math.IntegerMath;
import net.inetalliance.log.Log;
import net.inetalliance.log.progress.ProgressMeter;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sql.TwoTuple;
import net.inetalliance.types.Currency;
import net.inetalliance.types.Named;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.DateMidnight;
import org.joda.time.Interval;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.callgrove.obj.Opportunity.Q.*;
import static net.inetalliance.potion.Locator.*;
import static net.inetalliance.sql.Aggregate.*;
import static net.inetalliance.sql.TwoTuple.F.*;
import static net.inetalliance.sql.TwoTuple.*;

public abstract class Revenue<R extends Named>
  extends CachedGroupingRangeReport<R, Category> {

  private static final transient Log log = Log.getInstance(Revenue.class);
  private static final F1<ProductChain, TwoTuple<Integer, Integer>> chainConverter;
  public static final F1<String, ProductLine> productLineLookup = Info.$(ProductLine.class).lookup;

  static {
    final F1<Integer, Integer> beejaxToCallgroveSiteIdConverter = new F1<Integer, Integer>() {
      @Override
      public Integer $(final Integer beejaxId) {
        final Site site = $1(Site.Q.withBeejaxId(beejaxId));
        return site == null ? null : site.id;
      }
    }.memoize(count(Query.all(Site.class)));
    chainConverter = new F1<ProductChain, TwoTuple<Integer, Integer>>() {
      @Override
      public TwoTuple<Integer, Integer> $(final ProductChain chain) {
        return tuple(beejaxToCallgroveSiteIdConverter.$(chain.site), chain.id);
      }
    };
  }

  public Revenue() {
    super("tags", "p");
  }

  protected abstract JsonMap addRowInfo(final JsonMap json, final R row);

  @Override
  protected JsonMap generate(final EnumSet<SaleSource> sources,
                             final EnumSet<ContactType> contactTypes,
                             final Agent loggedIn, final ProgressMeter meter,
                             final DateMidnight start, final DateMidnight end,
                             final Set<Category> categories,
                             final Map<String, String> extras) {
    if (loggedIn == null || !(loggedIn.isManager() || loggedIn.isTeamLeader())) {
      log.warning("%s tried to access %s",
        loggedIn == null ? "Nobody?" : loggedIn.key,
        getClass().getSimpleName());
      throw new UnauthorizedException();
    }

    final Interval interval = getReportingInterval(start, end);
    final ProductLine productLine = productLineLookup.$(extras.get("p"));

    final Map<Integer, Query<Opportunity>> categoryQueries = getCategoryQueries(meter, categories);

    Query<Opportunity> oppQuery = soldInInterval(interval)
      .and(withSources(sources))
      .and(withContactTypes(contactTypes));
    if (productLine != null) {
      oppQuery = oppQuery.and(withProductLine(productLine));
    }
    final Query<Opportunity> finalOppQuery = oppQuery;
    final JsonList rows = new JsonList();
    Locator.forEach(allRows(loggedIn), new P1<R>() {
      @Override
      public void $(final R row) {
        final JsonList rowData = new JsonList(categories.size());
        final Query<Opportunity> rowQuery = finalOppQuery.and(oppsForRow(row));
        int opps = 0;
        for (final Category category : categories) {
          final Query<Opportunity> query = rowQuery.and(categoryQueries.get(category.id));
          final int count = count(query);
          opps += count;
          rowData.add(addRowInfo(new JsonMap()
              .$("type", CellType.DATA)
              .$("category", category.id)
              .$("count", count)
              .$("amount", count == 0 ? 0 : $$(query, SUM, Currency.class, "amount").doubleValue()),
            row));
        }
        if (productLine != null) {
          final Query<Opportunity> noProductQuery = rowQuery.and(hasBeejaxProduct.negate());
          int count = count(noProductQuery);
          opps += count;
          rowData.add(addRowInfo(new JsonMap()
              .$("type", CellType.NO_PRODUCT)
              .$("count", count)
              .$("amount",
                count == 0 ? 0 : $$(noProductQuery, SUM, Currency.class, "amount").doubleValue()),
            row));
          final Query<Opportunity> notMatchingQuery =
            rowQuery
              .and(hasBeejaxProduct)
              .and(Query.or(Opportunity.class, categoryQueries.values()).negate());
          count = count(notMatchingQuery);
          opps += count;
          rowData.add(addRowInfo(new JsonMap()
              .$("type", CellType.NOT_MATCHING)
              .$("count", count)
              .$("amount",
                count == 0 ? 0 : $$(notMatchingQuery, SUM, Currency.class, "amount").doubleValue()),
            row));
        }
        if (opps > 0) {
          rows.add(new JsonMap()
            .$("name", row.getName())
            .$("data", rowData));
        }
        meter.increment(row.getName());
      }
    });
    final JsonList labels = JsonList.$(groupLabels.map(categories));
    if (productLine != null) {
      labels.add(productLine.getName() + " No Product");
      labels.add("Other " + productLine.getName());
    }
    return new JsonMap()
      .$("labels", labels)
      .$("rows", rows);
  }

  public static Map<Integer, Query<Opportunity>> getCategoryQueries(
    final Set<Category> categories) {
    return getCategoryQueries(null, categories);
  }

  private static Map<Integer, Query<Opportunity>> getCategoryQueries(final ProgressMeter meter,
                                                                     final Set<Category>
                                                                       categories) {
    final Map<Integer, Query<Opportunity>> categoryQueries = new HashMap<>(categories.size());
    for (final Category category : categories) {
      if (meter != null) {
        meter.setLabel("Fetching product ids for \"%s\"", category.name);
      }
      final Collection<ProductChain> productIds = Callgrove.beejax.getProductsWithTag(category.id);
      categoryQueries.put(category.id,
        withSiteAndBeejaxProductIdIn(hasA.filter(chainConverter.map(productIds))));
      if (meter != null) {
        meter.increment();
      }
    }
    return categoryQueries;
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
  protected F1<String, Category> getGroupLookup(final String[] params) {
    return getCategoryLookup(params);
  }

  public static F1<String, Category> getCategoryLookup(final String[] params) {
    final Set<Integer> ids = new HashSet<>(params.length);
    ids.addAll(IntegerMath.$.parse.copy(params));
    final Collection<Category> categories = Callgrove.beejax.categoryLookup(ids);
    final Map<String, Category> byId = F1.map(categories, new F1<Category, String>() {
      @Override
      public String $(final Category category) {
        return category.id.toString();
      }
    });
    return MapFunctor.$(byId);
  }

  public enum CellType {
    DATA,
    NO_PRODUCT,
    NOT_MATCHING
  }

  @Override
  protected int getJobSize(final Agent loggedIn, final int numGroups) {
    return count(allRows(loggedIn)) + numGroups;
  }
}
