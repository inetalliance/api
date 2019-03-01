package net.inetalliance.sonar.reporting;

import com.callgrove.*;
import com.callgrove.obj.*;
import com.callgrove.types.*;
import net.inetalliance.angular.*;
import net.inetalliance.angular.exception.*;
import net.inetalliance.angular.list.*;
import net.inetalliance.beejax.messages.*;
import net.inetalliance.funky.*;
import net.inetalliance.potion.*;
import net.inetalliance.potion.info.*;
import net.inetalliance.potion.query.*;
import net.inetalliance.types.json.*;
import org.joda.time.*;

import javax.servlet.annotation.*;
import javax.servlet.http.*;
import java.util.*;
import java.util.function.*;

import static com.callgrove.Callgrove.*;
import static com.callgrove.obj.Opportunity.*;
import static java.util.stream.Collectors.*;
import static net.inetalliance.potion.Locator.*;
import static net.inetalliance.sonar.reporting.Revenue.CellType.*;

@WebServlet("/reporting/reports/revenueDetail")
public class RevenueDetail
		extends AngularServlet {

	@Override
	protected void get(final HttpServletRequest request, final HttpServletResponse response)
			throws Exception {
		final String mode = request.getParameter("mode");
		if (mode == null) {
			throw new BadRequestException("Missing 'mode' parameter");
		}
		final SaleSource source =
				StringFun.isEmpty(mode) || "all".equals(mode) ? null : StringFun.camelCaseToEnum(SaleSource.class, mode);
		final Interval interval = getInterval(request);
		final Info<ProductLine> productLineInfo = Info.$(ProductLine.class);
		final ProductLine productLine = productLineInfo.lookup(request.getParameter("p"));
		final String[] tagParams = request.getParameterValues("tags");
		final Set<Category> categories =
				Arrays.stream(tagParams).map(t -> Revenue.getCategory(tagParams, Integer.valueOf(t))).collect(toSet());
		final Map<Integer, Query<? super Opportunity>> categoryQueries = Revenue.getCategoryQueries(categories);

		Query<Opportunity> query = soldInInterval(interval).and(withSaleSource(source));

		final String categoryParam = request.getParameter("category");
		if (categoryParam != null) {
			query = query.and(categoryQueries.get(Integer.parseInt(categoryParam)));
		}

		final Info<Agent> agentInfo = Info.$(Agent.class);
		final Agent agent = agentInfo.lookup(request.getParameter("agent"));
		if (agent != null) {
			query = query.and(Opportunity.withAgent(agent));
		}

		final Info<Site> siteInfo = Info.$(Site.class);
		final Site site = siteInfo.lookup(request.getParameter("site"));
		if (site != null) {
			query = query.and(Opportunity.withSite(site));
		}

		if (productLine != null) {
			query = query.and(withProductLine(productLine));
			final Revenue.CellType cellType = getParameter(request, Revenue.CellType.class, "type", DATA);
			if (cellType == NO_PRODUCT) {
				query = query.and(hasBeejaxProduct.negate());
			}
			if (cellType == NOT_MATCHING) {
				query = query.and(hasBeejaxProduct).and(Query.or(Opportunity.class, categoryQueries.values()).negate());
			}
		}
		final Set<Opportunity> results = $$(query);

		final Set<ProductChain> chains =
				results.stream().filter(hasBeejaxProduct).map(RevenueDetail::productChain).collect(toSet());

		final Set<Product> products = chains.isEmpty() ? Set.of() : Callgrove.beejax.lookupProducts(chains);
		final Map<Integer, Product> productsById = products.stream().collect(Funky.toMap(p -> p.id, Function.identity()));

		final Function<Opportunity, Json> toJson = opportunity -> {
			final Product product = productsById.get(opportunity.getBeejaxProductId());
			final JsonMap json = new JsonMap().$("id", opportunity.id)
			                                  .$("contact", opportunity.getContactName())
			                                  .$("amount", opportunity.getAmount().doubleValue())
			                                  .$("date", opportunity.getSaleDate())
			                                  .$("product", product == null
					                                  ? null
					                                  : new JsonMap().$("name", product.name)
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
		};
		respond(response,
		        Listable.formatResult(results.stream().map(toJson).collect(toSet())).$("hasProducts", !chains.isEmpty()));
	}

	private static ProductChain productChain(final Opportunity opportunity) {
		final ProductChain chain = new ProductChain();
		chain.site = Locator.$(opportunity.getSite()).getBeejaxId();
		chain.id = opportunity.getBeejaxProductId();
		return chain;
	}

}
