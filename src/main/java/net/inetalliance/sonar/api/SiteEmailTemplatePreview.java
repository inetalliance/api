package net.inetalliance.sonar.api;

import com.callgrove.obj.Site;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.potion.Locator;
import net.inetalliance.types.www.ContentType;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@WebServlet("/api/siteEmailTemplatePreview/*")
public class SiteEmailTemplatePreview
	extends AngularServlet {
	private static Pattern pattern = Pattern.compile("/api/siteEmailTemplatePreview/(\\d+)");

	public Pattern getPattern() {
		return pattern;
	}

	public SiteEmailTemplatePreview() {
	}

	@Override
	protected void get(final HttpServletRequest request, final HttpServletResponse response)
		throws Exception {
		final Matcher matcher = pattern.matcher(request.getRequestURI());
		if (matcher.matches()) {
			final String id = matcher.group(1);
			final Site site = Locator.$(new Site(Integer.parseInt(id)));
			if (site == null) {
				throw new NotFoundException("Can't find that site");
			}
			final String content = site.getEmailTemplate() == null ? "" : String.format
				("<html><head>%s</head><body>%s</body></html>",
					site.getEmailTemplateStyles() == null ? "" : String.format
						("<style>%s</style>", site.getEmailTemplateStyles()), site.getEmailTemplate());
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
