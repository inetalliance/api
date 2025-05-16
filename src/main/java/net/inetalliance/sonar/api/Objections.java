package net.inetalliance.sonar.api;

import com.callgrove.obj.Objection;
import com.callgrove.obj.ObjectionCategory;
import com.callgrove.obj.ProductLine;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import lombok.val;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.angular.list.Searchable;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.potion.query.Search;
import net.inetalliance.sonar.ListableModel;
import net.inetalliance.sql.DbVendor;
import net.inetalliance.sql.Namer;
import net.inetalliance.types.json.Json;

import static com.ameriglide.phenix.core.Strings.isNotEmpty;
import static net.inetalliance.sql.OrderBy.Direction.ASCENDING;

@WebServlet("/api/objection/*")
public class Objections
        extends ListableModel<Objection>
        implements Searchable<Objection> {

    public Objections() {
        super(Objection.class);
    }

    public static void main(String[] args) {
        for (val s : Info.$(Objection.class).getCreate(DbVendor.POSTGRES, Namer.simple)) {
            System.out.println(s);
        }
    }

    @Override
    public Query<Objection> search(final String query) {
        return new Search<>(Objection.class, query.split(" "));
    }

    @Override
    public Query<Objection> all(final Class<Objection> type, final HttpServletRequest request) {

        var q = super.all(type, request);

        val c = request.getParameter("c");
        if (isNotEmpty(c)) {
            val objectionCategory = Locator
                    .$(new ObjectionCategory(Integer.valueOf(c)));
            if (objectionCategory == null) {
                throw new NotFoundException("Could not find objection category %s", c);
            }
            q = q.and(Objection.withCategory(objectionCategory));
        }
        val p = request.getParameter("p");
        if (isNotEmpty(p)) {
            val productLine = Locator.$(new ProductLine(Integer.valueOf(p)));
            if (productLine == null) {
                throw new NotFoundException("Could not find product line %s", p);
            }
            q = q.and(Objection.withProductLine(productLine));
        }

        return q.orderBy("id", ASCENDING);
    }

    @Override
    public Json toJson(final HttpServletRequest request, Objection arg) {
        return Info.$(arg).toJson(arg).$("categoryName", arg.getCategory().getName());
    }
}
