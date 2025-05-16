package net.inetalliance.sonar.api;

import static net.inetalliance.sql.OrderBy.Direction.ASCENDING;

import com.callgrove.obj.Site;
import com.callgrove.obj.SiteGroup;
import java.util.Collection;
import java.util.function.Function;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import net.inetalliance.angular.Key;
import net.inetalliance.angular.list.Searchable;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Autocomplete;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sonar.ListableModel;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;

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
    return (request.getParameter("active") == null ? Query.all(type) : Site.isActive)
        .orderBy("name", ASCENDING);
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



