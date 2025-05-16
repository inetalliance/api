package net.inetalliance.sonar.api;

import com.callgrove.Callgrove;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.val;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.list.Listable;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;

import java.util.HashSet;

@WebServlet("/api/categoryLookup")
public class CategoryLookup
        extends AngularServlet {

    public static JsonMap json(final net.inetalliance.beejax.messages.Category category) {
        return new JsonMap().$("id", category.id).$("name", category.name)
                .$("products", category.products);
    }

    @Override
    protected void get(final HttpServletRequest request, final HttpServletResponse response)
            throws Exception {
        val keys = request.getParameterValues("id");
        if (keys.length == 0) {
            throw new BadRequestException("No ids specified");
        }
        val ids = new HashSet<Integer>(keys.length);
        for (val key : keys) {
            if (key != null && !key.isEmpty()) {
                ids.add(Integer.parseInt(key));
            }
        }
        respond(response,
                Listable.formatResult(
                        JsonList.collect(Callgrove.beejax.categoryLookup(ids), CategoryLookup::json)));
    }
}
