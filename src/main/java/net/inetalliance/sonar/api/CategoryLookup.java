package net.inetalliance.sonar.api;

import com.callgrove.Callgrove;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.list.Listable;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashSet;
import java.util.Set;

@WebServlet("/api/categoryLookup")
public class CategoryLookup
		extends AngularServlet {

	public static final JsonMap json(final net.inetalliance.beejax.messages.Category category) {
		return new JsonMap().$("id", category.id).$("name", category.name).$("products", category.products);
	}

	@Override
	protected void get(final HttpServletRequest request, final HttpServletResponse response)
			throws Exception {
		final String[] keys = request.getParameterValues("id");
		if (keys.length == 0) {
			throw new BadRequestException("No ids specified");
		}
		final Set<Integer> ids = new HashSet<>(keys.length);
		for (final String key : keys) {
			if (key != null && key.length() > 0) {
				ids.add(Integer.parseInt(key));
			}
		}
		respond(response,
		        Listable.formatResult(JsonList.collect(Callgrove.beejax.categoryLookup(ids), CategoryLookup::json)));
	}
}
