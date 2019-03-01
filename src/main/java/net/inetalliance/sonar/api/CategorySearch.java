package net.inetalliance.sonar.api;

import com.callgrove.Callgrove;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.list.Listable;
import net.inetalliance.angular.list.Searchable;
import net.inetalliance.beejax.messages.CategorySearchResponse;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.stream.Collectors;

@WebServlet("/api/categorySearch")
public class CategorySearch
		extends AngularServlet {

	@Override
	protected void get(final HttpServletRequest request, final HttpServletResponse response)
			throws Exception {
		final String query = request.getParameter(Searchable.parameter);
		if (query == null) {
			respond(response, new JsonMap().$("categories", JsonList.empty).$("hasMore", false));
		} else {
			final int page = getParameter(request, Listable.page, 1);
			final int pageSize = getParameter(request, Listable.pageSize, 20);
			final CategorySearchResponse results = Callgrove.beejax.categorySearch(query, pageSize, (page - 1) * pageSize);
			respond(response, Listable.formatResult(
					results.categories.stream().map(CategoryLookup::json).collect(Collectors.toList())));
		}
	}
}
