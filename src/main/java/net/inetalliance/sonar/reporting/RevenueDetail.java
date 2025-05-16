package net.inetalliance.sonar.reporting;

import com.ameriglide.phenix.core.Enums;
import com.ameriglide.phenix.core.Strings;
import com.callgrove.Callgrove;
import com.callgrove.obj.Agent;
import com.callgrove.obj.Opportunity;
import com.callgrove.obj.ProductLine;
import com.callgrove.obj.Site;
import com.callgrove.types.SaleSource;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.val;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.list.Listable;
import net.inetalliance.beejax.messages.Product;
import net.inetalliance.beejax.messages.ProductChain;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static com.callgrove.Callgrove.getInterval;
import static com.callgrove.obj.Opportunity.*;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static net.inetalliance.potion.Locator.$$;
import static net.inetalliance.sonar.reporting.Revenue.CellType.*;

@WebServlet("/reporting/reports/revenueDetail")
public class RevenueDetail
        extends AngularServlet {

    private static ProductChain productChain(final Opportunity opportunity) {
        val chain = new ProductChain();
        chain.site = Locator.$(opportunity.getSite()).getBeejaxId();
        chain.id = opportunity.getBeejaxProductId();
        return chain;
    }

    @Override
    protected void get(final HttpServletRequest request, final HttpServletResponse response)
            throws Exception {
        val mode = request.getParameter("mode");
        if (mode == null) {
            throw new BadRequestException("Missing 'mode' parameter");
        }
        val source =
                Strings.isEmpty(mode) || "all".equals(mode) ? null
                        : Enums.decamel(SaleSource.class, mode);
        val interval = getInterval(request);
        val productLineInfo = Info.$(ProductLine.class);
        val productLine = productLineInfo.lookup(request.getParameter("p"));
        val tagParams = request.getParameterValues("tags");
        val categories =
                Arrays.stream(tagParams).map(t -> Revenue.getCategory(tagParams, Integer.valueOf(t)))
                        .collect(toSet());
        val categoryQueries = Revenue
                .getCategoryQueries(categories);

        var query = soldInInterval(interval).and(withSaleSource(source));

        val categoryParam = request.getParameter("category");
        if (categoryParam != null) {
            query = query.and(categoryQueries.get(Integer.parseInt(categoryParam)));
        }

        val agentInfo = Info.$(Agent.class);
        val agent = agentInfo.lookup(request.getParameter("agent"));
        if (agent != null) {
            query = query.and(Opportunity.withAgent(agent));
        }

        val siteInfo = Info.$(Site.class);
        val site = siteInfo.lookup(request.getParameter("site"));
        if (site != null) {
            query = query.and(Opportunity.withSite(site));
        }

        if (productLine != null) {
            query = query.and(withProductLine(productLine));
            val cellType = getParameter(request, Revenue.CellType.class, "type", DATA);
            if (cellType == NO_PRODUCT) {
                query = query.and(hasBeejaxProduct.negate());
            }
            if (cellType == NOT_MATCHING) {
                query = query.and(hasBeejaxProduct)
                        .and(Query.or(Opportunity.class, categoryQueries.values()).negate());
            }
        }
        val results = $$(query);

        val chains =
                results.stream().filter(hasBeejaxProduct).map(RevenueDetail::productChain).collect(toSet());

        final Set<Product> products =
                chains.isEmpty() ? Set.of() : Callgrove.beejax.lookupProducts(chains);
        final Map<Integer, Product> productsById = products.stream()
                .collect(toMap(p -> p.id, Function.identity()));

        final Function<Opportunity, Json> toJson = opportunity -> {
            val product = productsById.get(opportunity.getBeejaxProductId());
            val json = new JsonMap().$("id", opportunity.id)
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
                Listable.formatResult(results.stream().map(toJson).collect(toSet()))
                        .$("hasProducts", !chains.isEmpty()));
    }

}
