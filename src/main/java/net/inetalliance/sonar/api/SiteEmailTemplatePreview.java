package net.inetalliance.sonar.api;

import com.callgrove.obj.Site;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.val;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.potion.Locator;
import net.inetalliance.types.www.ContentType;

import java.util.regex.Pattern;

@WebServlet("/api/siteEmailTemplatePreview/*")
public class SiteEmailTemplatePreview
        extends AngularServlet {

    private static final Pattern pattern = Pattern.compile("/api/siteEmailTemplatePreview/(\\d+)");

    public SiteEmailTemplatePreview() {
    }

    public Pattern getPattern() {
        return pattern;
    }

    @Override
    protected void get(final HttpServletRequest request, final HttpServletResponse response)
            throws Exception {
        val matcher = pattern.matcher(request.getRequestURI());
        if (matcher.matches()) {
            val id = matcher.group(1);
            val site = Locator.$(new Site(Integer.parseInt(id)));
            if (site == null) {
                throw new NotFoundException("Can't find that site");
            }
            val content = site.getEmailTemplate() == null
                    ? ""
                    : String.format("<html><head>%s</head><body>%s</body></html>",
                    site.getEmailTemplateStyles() == null
                            ? ""
                            : String.format("<style>%s</style>", site.getEmailTemplateStyles()),
                    site.getEmailTemplate());
            response.setContentLength(content.length());
            response.setContentType(ContentType.HTML.toString());
            try (val writer = response.getWriter()) {
                writer.write(content);
                writer.flush();
            }
        }
    }
}
