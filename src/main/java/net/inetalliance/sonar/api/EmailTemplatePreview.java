package net.inetalliance.sonar.api;

import com.callgrove.obj.EmailTemplate;
import java.io.PrintWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.potion.Locator;
import net.inetalliance.types.www.ContentType;

@WebServlet("/api/emailTemplatePreview/*")
public class EmailTemplatePreview
    extends AngularServlet {

  private static final String BASE_CSS =
      "body { font-family: \"Helvetica Neue\",Helvetica,Arial," + "sans-serif; }";
  private static Pattern pattern = Pattern.compile("/api/emailTemplatePreview/(\\d+)");

  public EmailTemplatePreview() {
  }

  public Pattern getPattern() {
    return pattern;
  }

  @Override
  protected void get(final HttpServletRequest request, final HttpServletResponse response)
      throws Exception {
    final Matcher matcher = pattern.matcher(request.getRequestURI());
    if (matcher.matches()) {
      final String id = matcher.group(1);
      final EmailTemplate emailTemplate = Locator.$(new EmailTemplate(Integer.parseInt(id)));
      final String content = emailTemplate == null
          ? ""
          : String.format("<html><head><style>%s</style></head><body>%s</body></html>", BASE_CSS,
              emailTemplate.getText());
      response.setContentLength(content.length());
      response.setContentType(ContentType.HTML.toString());
      PrintWriter writer = response.getWriter();
      try {
        writer.write(content);
        writer.flush();
      } finally {
        writer.close();
      }
    }
  }
}
