package net.inetalliance.sonar.api;

import static net.inetalliance.sql.OrderBy.Direction.ASCENDING;

import com.callgrove.obj.EmailTemplate;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import net.inetalliance.angular.list.Searchable;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.potion.query.Search;
import net.inetalliance.sonar.ListableModel;
import net.inetalliance.types.json.Json;

@WebServlet("/api/emailTemplate/*")
public class EmailTemplates
    extends ListableModel<EmailTemplate>
    implements Searchable<EmailTemplate> {

  public EmailTemplates() {
    super(EmailTemplate.class);
  }

  @Override
  public Query<EmailTemplate> search(final String query) {
    return new Search<>(EmailTemplate.class, query.split("[ ]"));
  }

  @Override
  public Query<EmailTemplate> all(final Class<EmailTemplate> type,
      final HttpServletRequest request) {

    Query<EmailTemplate> q = super.all(type, request);
    return q.orderBy("name", ASCENDING);
  }

  @Override
  public Json toJson(final HttpServletRequest request, final EmailTemplate template) {
    return Info.$(template).toJson(template);
  }
}
