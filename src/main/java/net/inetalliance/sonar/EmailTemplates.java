package net.inetalliance.sonar;

import com.callgrove.obj.EmailTemplate;
import net.inetalliance.angular.list.Searchable;
import net.inetalliance.funky.functors.F1;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.potion.query.Search;
import net.inetalliance.types.json.Json;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;

import static net.inetalliance.sql.OrderBy.Direction.*;

@WebServlet("/api/emailTemplate/*")
public class EmailTemplates
  extends ListableModel<EmailTemplate>
  implements Searchable<EmailTemplate> {

  public EmailTemplates() {
    super(EmailTemplate.class);
  }

  @Override
  public Query<EmailTemplate> search(final String query) {
    return Search.$(EmailTemplate.class, query.split("[ ]"));
  }

  @Override
  public Query<EmailTemplate> all(final Class<EmailTemplate> type,
                                  final HttpServletRequest request) {

    Query<EmailTemplate> q = super.all(type, request);
    return q.orderBy("name", ASCENDING);
  }

  @Override
  public F1<EmailTemplate, ? extends Json> toJson(final HttpServletRequest request) {
    return new F1<EmailTemplate, Json>() {
      @Override
      public Json $(final EmailTemplate arg) {
        return Info.$(arg).toJson().$(arg);
      }
    };
  }
}
