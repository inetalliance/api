package net.inetalliance.sonar.api;

import com.callgrove.obj.*;
import net.inetalliance.angular.*;
import net.inetalliance.angular.list.*;
import net.inetalliance.potion.info.*;
import net.inetalliance.potion.query.*;
import net.inetalliance.sonar.*;
import net.inetalliance.types.json.*;

import javax.servlet.annotation.*;
import javax.servlet.http.*;
import java.util.*;
import java.util.function.*;

import static net.inetalliance.sql.OrderBy.Direction.*;

@WebServlet({"/api/site/*"})
public class Sites
		extends ListableModel.Named<Site>
		implements Searchable<Site> {

	public static final Function<Site, Json> json;

	static {
		final Info<Site> info = Info.$(Site.class);
		json = site -> {
			final JsonMap json = new JsonMap().$("name")
			                                  .$("abbreviation")
			                                  .$("uri")
			                                  .$("tollfree")
			                                  .$("productsDirectory")
			                                  .$("emailTemplate")
			                                  .$("emailTemplateStyles")
			                                  .$("id");
			info.fill(site, json);
			final Collection<SiteGroup> siteGroups = site.getSiteGroups();
			if (!siteGroups.isEmpty()) {
				json.$("group", siteGroups.iterator().next().getName());
			}
			return json.$("domain", site.getDomain());
		};
	}

	public Sites() {
		super(Site.class);
	}

	@Override
	public Query<Site> all(final Class<Site> type, final HttpServletRequest request) {
		return (request.getParameter("active") == null ? Query.all(type) : Site.isActive).orderBy("name", ASCENDING);
	}

	@Override
	protected Json toJson(final Key<Site> key, final Site site, final HttpServletRequest request) {
		return json.apply(site);
	}

	@Override
	public Query<Site> search(final String query) {
		return new Autocomplete<>(Site.class, query.split(" "));
	}

	@Override
	public Json toJson(final HttpServletRequest request, Site site) {
		return json.apply(site);
	}
}



