package net.inetalliance.sonar.reports;

import com.callgrove.Callgrove;
import com.callgrove.obj.Agent;
import com.callgrove.obj.Opportunity;
import com.callgrove.obj.ProductLine;
import com.callgrove.obj.Site;
import com.callgrove.types.SaleSource;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.list.Listable;
import net.inetalliance.beejax.messages.Category;
import net.inetalliance.beejax.messages.Product;
import net.inetalliance.beejax.messages.ProductChain;
import net.inetalliance.funky.functors.F1;
import net.inetalliance.funky.functors.types.str.StringFun;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sonar.reports.Revenue.CellType;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.Interval;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.callgrove.obj.Opportunity.Q.*;
import static net.inetalliance.potion.Locator.$$;
import static net.inetalliance.sonar.reports.CachedGroupingRangeReport.getInterval;
import static net.inetalliance.sonar.reports.Revenue.CellType.*;
import static net.inetalliance.sonar.reports.Revenue.getCategoryLookup;

@WebServlet("/api/revenueDetail")
public class RevenueDetail
		extends AngularServlet {
	private static final F1<String, Agent> agentLookup = Info.$(Agent.class).lookup;
	private static final F1<String, ProductLine> productLineLookup = Info.$(ProductLine.class).lookup;
	private static final F1<String, Site> siteLookup = Info.$(Site.class).lookup;

	@Override
	protected void get(final HttpServletRequest request, final HttpServletResponse response)
			throws Exception {
		final String mode = request.getParameter("mode");
		if (mode == null) {
			throw new BadRequestException("Missing 'mode' parameter");
		}
		final SaleSource source = StringFun.empty.$(mode) || "all".equals(mode) ?
				null : StringFun.camelCaseToEnum(SaleSource.class).$(mode);
		final Interval interval = getInterval(request);
		final ProductLine productLine = productLineLookup.$(request.getParameter("p"));
		final String[] tagParams = request.getParameterValues("tags");
		final Set<Category> categories = new HashSet<>(getCategoryLookup(tagParams).copy(tagParams));
		final Map<Integer, Query<Opportunity>> categoryQueries = Revenue.getCategoryQueries(categories);

		Query<Opportunity> query = soldInInterval(interval).and(withSaleSource(source));

		final String categoryParam = request.getParameter("category");
		if (categoryParam != null) {
			query = query.and(categoryQueries.get(Integer.parseInt(categoryParam)));
		}

		final Agent agent = agentLookup.$(request.getParameter("agent"));
		if (agent != null) {
			query = query.and(Opportunity.Q.withAgent(agent));
		}

		final Site site = siteLookup.$(request.getParameter("site"));
		if (site != null) {
			query = query.and(Opportunity.Q.withSite(site));
		}

		if (productLine != null) {
			query = query.and(withProductLine(productLine));
			final CellType cellType = getParameter(request, CellType.class, "type", DATA);
			if (cellType == NO_PRODUCT) {
				query = query.and(hasBeejaxProduct.negate());
			}
			if (cellType == NOT_MATCHING) {
				query = query
						.and(hasBeejaxProduct)
						.and(Query.or(Opportunity.class, categoryQueries.values()).negate());
			}
		}
		final Set<Opportunity> results = $$(query);

		final Set<ProductChain> chains =
				productChain.copyTo(hasBeejaxProduct.filter(results), new HashSet<>(results.size()));
		final Set<Product> products = chains.isEmpty() ?
				Collections.emptySet()
				: Callgrove.beejax.lookupProducts(chains);
		final Map<Integer, Product> productsById = F1.map(products, productId);

		final F1<Opportunity, Json> toJson = new F1<Opportunity, Json>() {
			@Override
			public Json $(final Opportunity opportunity) {
				final Product product = productsById.get(opportunity.getBeejaxProductId());
				final JsonMap json = new JsonMap()
						.$("id", opportunity.id)
						.$("contact", opportunity.getContactName())
						.$("amount", opportunity.getAmount().doubleValue())
						.$("date", opportunity.getSaleDate())
						.$("product", product == null ? null : new JsonMap()
								.$("name", product.name)
								.$("imageUrl", product.imageUrl)
								.$("rootUrl", product.rootUrl)
								.$("adminUrl", product.adminUrl)
								.$("baseUrl", product.baseUrl));
				if (site == null) {
					json.$("site", opportunity.getSiteAbbreviation());
				}
				if (agent == null) {
					json.$("agent", opportunity.getAssignedTo().getLastNameFirstInitial());
				}
				if (productLine == null) {
					json.$("productLine", opportunity.getProductLine().getName());
				}
				return json;
			}
		};
		respond(response, Listable.Impl.formatResult(toJson.map(results)).$("hasProducts", !chains.isEmpty()));
	}

	private static F1<Opportunity, ProductChain> productChain = new F1<Opportunity, ProductChain>() {
		@Override
		public ProductChain $(final Opportunity opportunity) {
			final ProductChain chain = new ProductChain();
			chain.site = Locator.$(opportunity.getSite()).getBeejaxId();
			chain.id = opportunity.getBeejaxProductId();
			return chain;
		}
	};

	private static F1<Product, Integer> productId = new F1<Product, Integer>() {
		@Override
		public Integer $(final Product product) {
			return product.id;
		}
	};
}
