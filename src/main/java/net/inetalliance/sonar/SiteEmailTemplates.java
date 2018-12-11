package net.inetalliance.sonar;

import com.callgrove.obj.Site;
import net.inetalliance.funky.functors.F1;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;

import static net.inetalliance.sql.OrderBy.Direction.*;

@WebServlet("/api/siteEmailTemplate/*")
public class SiteEmailTemplates
  extends ListableModel<Site> {

  public SiteEmailTemplates() {
    super(Site.class, Pattern.compile("/api/siteEmailTemplate(?:/(\\d+))?"));
  }

  @Override
  public Query<Site> all(final Class<Site> type,
                         final HttpServletRequest request) {

    Query<Site> q = super.all(type, request);
    return q.orderBy("name", ASCENDING);
  }

  @Override
  public F1<Site, ? extends Json> toJson(final HttpServletRequest request) {
    return new F1<Site, Json>() {
      @Override
      public Json $(final Site site) {
        return new JsonMap()
          .$("id", site.getId())
          .$("name", site.getName())
          .$("emailTemplate", site.getEmailTemplate())
          .$("emailTemplateStyles", site.getEmailTemplateStyles());
      }
    };
  }
}
