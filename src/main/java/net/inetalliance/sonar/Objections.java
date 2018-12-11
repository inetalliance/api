package net.inetalliance.sonar;

import com.callgrove.obj.Objection;
import com.callgrove.obj.ObjectionCategory;
import com.callgrove.obj.ProductLine;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.angular.list.Searchable;
import net.inetalliance.funky.functors.F1;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.potion.query.Search;
import net.inetalliance.sql.DbVendor;
import net.inetalliance.sql.Namer;
import net.inetalliance.types.json.Json;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;

import static net.inetalliance.funky.functors.types.str.StringFun.empty;
import static net.inetalliance.sql.OrderBy.Direction.ASCENDING;

@WebServlet("/api/objection/*")
public class Objections
  extends ListableModel<Objection>
  implements Searchable<Objection> {

  public Objections() {
    super(Objection.class);
  }

  @Override
  public Query<Objection> search(final String query) {
    return Search.$(Objection.class, query.split("[ ]"));
  }

  @Override
  public Query<Objection> all(final Class<Objection> type, final HttpServletRequest request) {

    Query<Objection> q = super.all(type, request);

    final String c = request.getParameter("c");
    if (!empty.$(c)) {
      final ObjectionCategory objectionCategory = Locator.$(new ObjectionCategory(new Integer(c)));
      if (objectionCategory == null) {
        throw new NotFoundException("Could not find objection category %s", c);
      }
      q = q.and(Objection.Q.withCategory(objectionCategory));
    }
    final String p = request.getParameter("p");
    if (!empty.$(p)) {
      final ProductLine productLine = Locator.$(new ProductLine(new Integer(p)));
      if (productLine == null) {
        throw new NotFoundException("Could not find product line %s", p);
      }
      q = q.and(Objection.Q.withProductLine(productLine));
    }

    return q.orderBy("id", ASCENDING);
  }

  @Override
  public F1<Objection, ? extends Json> toJson(final HttpServletRequest request) {
    return new F1<Objection, Json>() {
      @Override
      public Json $(final Objection arg) {
        return Info.$(arg).toJson().$(arg).$("categoryName", arg.getCategory().getName());
      }
    };
  }

  public static void main(String[] args) {
    for (final String s : Info.$(Objection.class).getCreate(DbVendor.POSTGRES, Namer.simple)) {
      System.out.println(s);
    }
  }
}
