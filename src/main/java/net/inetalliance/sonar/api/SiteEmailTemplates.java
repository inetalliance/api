package net.inetalliance.sonar.api;

import static net.inetalliance.sql.OrderBy.Direction.ASCENDING;

import com.callgrove.obj.Site;
import java.util.regex.Pattern;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import net.inetalliance.potion.query.Query;
import net.inetalliance.sonar.ListableModel;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;

@WebServlet("/api/siteEmailTemplate/*")
public class SiteEmailTemplates
    extends ListableModel<Site> {

  public SiteEmailTemplates() {
    super(Site.class, Pattern.compile("/api/siteEmailTemplate(?:/(\\d+))?"));
  }

  @Override
  public Query<Site> all(final Class<Site> type, final HttpServletRequest request) {

    Query<Site> q = super.all(type, request);
    return q.orderBy("name", ASCENDING);
  }

  @Override
  public Json toJson(final HttpServletRequest request, Site site) {
    return new JsonMap().$("id", site.getId())
        .$("name", site.getName())
        .$("emailTemplate", site.getEmailTemplate())
        .$("emailTemplateStyles", site.getEmailTemplateStyles());
  }
}
