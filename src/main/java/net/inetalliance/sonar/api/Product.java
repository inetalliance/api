package net.inetalliance.sonar.api;


import com.callgrove.Callgrove;
import com.callgrove.obj.Opportunity;
import com.callgrove.obj.Site;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.val;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.angular.list.Listable;
import net.inetalliance.angular.list.Searchable;
import net.inetalliance.beejax.messages.Coupon;
import net.inetalliance.potion.Locator;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;

import java.util.regex.Pattern;

import static com.ameriglide.phenix.core.Strings.isEmpty;

@WebServlet("/api/product/*")
public class Product
    extends AngularServlet {

  private static final Pattern pattern = Pattern.compile("/api/product(?:/(.*))?");

  public static JsonMap json(final net.inetalliance.beejax.messages.Product product) {
    return new JsonMap().$("id", product.id)
        .$("name", product.name)
        .$("oemCode", product.oemCode)
        .$("adminUrl", product.adminUrl)
        .$("rootUrl", product.rootUrl)
        .$("baseUrl", product.baseUrl)
        .$("thumbnailUrl", product.thumbnailUrl)
        .$("imageUrl", product.imageUrl)
        .$("manufacturer", product.manufacturer)
        .$("productLine", product.productLine)
        .$("msrp", product.msrp)
        .$("price", product.price)
        .$("minCouponPrice", product.minCouponPrice)
        .$("coupons", JsonList.collect(product.coupons, Product::couponJson))
        .$("tags", Json.Factory.$(product.tags));

  }

  private static JsonMap couponJson(final Coupon coupon) {
    return new JsonMap().$("type", coupon.type)
        .$("amount", coupon.amount)
        .$("discount", coupon.discount)
        .$("description", coupon.description);
  }

  @Override
  protected void get(final HttpServletRequest request, final HttpServletResponse response)
      throws Exception {
    val matcher = pattern.matcher(request.getRequestURI());
    if (matcher.matches()) {
      val key = matcher.group(1);
      if (isEmpty(key)) {
        val site = Locator.$(new Site(getParameter(request, "site", -1)));
        if (site == null) {
          throw new NotFoundException();
        }
        if (site.getBeejaxId() == null) {
          throw new NotFoundException("%s has no beejax id", site.getName());
        }
        val query = request.getParameter(Searchable.parameter);
        if (query == null) {
          respond(response, new JsonMap().$("products", JsonList.empty).$("hasMore", false));
        } else {

          val page = getParameter(request, Listable.page, 1);
          val pageSize = getParameter(request, Listable.pageSize, 20);
          val info =
              Callgrove.beejax
                  .productSearch(site.getBeejaxId(), 0, query, pageSize, (page - 1) * pageSize);
          respond(response,
              new JsonMap().$("products", JsonList.collect(info.products, Product::json))
                  .$("hasMore", info.remaining > 0));
        }
      } else {
          var site = Locator.$(new Site(getParameter(request, "site", -1)));
        final Integer productId;
        if (site == null) {
          val o = Locator.$(new Opportunity(Integer.valueOf(key)));
          if (o == null) {
            throw new NotFoundException("could not find opportunity with id %s", key);
          }
          site = o.getSite();
          productId = o.getBeejaxProductId();
          if (productId == null) {
            throw new BadRequestException("opportunity %d does not have a beejax product set",
                o.id);
          }
        } else {
          productId = Integer.valueOf(key);
        }
        if (site.getBeejaxId() == null) {
          throw new BadRequestException("site %d is not configured for beejax communication",
              site.getId());
        }
        respond(response, json(Callgrove.beejax.lookupProduct(site.getBeejaxId(), productId)));
      }
    } else {
      throw new BadRequestException("request should match %s", pattern.pattern());
    }
  }
}
