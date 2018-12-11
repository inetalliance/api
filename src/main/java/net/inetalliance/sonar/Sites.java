package net.inetalliance.sonar;

import com.callgrove.obj.Site;
import com.callgrove.obj.SiteGroup;
import net.inetalliance.angular.Key;
import net.inetalliance.angular.list.Searchable;
import net.inetalliance.funky.functors.F1;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Autocomplete;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import java.util.Collection;

import static net.inetalliance.sql.OrderBy.Direction.*;

@WebServlet({"/api/site/*", "/reporting/site/*"})
public class Sites
  extends ListableModel.Named<Site>
  implements Searchable<Site> {

  public static final F1<Site, Json> json;

  static {
    final Info<Site> info = Info.$(Site.class);
    json = new F1<Site, Json>() {
      @Override
      public Json $(final Site site) {
        final JsonMap json = new JsonMap()
          .$("name")
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
      }
    };
  }

  public Sites() {
    super(Site.class);
  }

  @Override
  public Query<Site> search(final String query) {
    return Autocomplete.$(Site.class, query.split(" "));
  }

  @Override
  public Query<Site> all(final Class<Site> type, final HttpServletRequest request) {
    return (request.getParameter("active") == null
      ? Query.all(type)
      : Site.Q.isActive)
      .orderBy("name", ASCENDING);
  }

  @Override
  protected Json toJson(final Key<Site> key, final Site site, final HttpServletRequest request) {
    return json.$(site);
  }

  @Override
  public F1<Site, ? extends Json> toJson(final HttpServletRequest request) {
    return json;
  }
}



