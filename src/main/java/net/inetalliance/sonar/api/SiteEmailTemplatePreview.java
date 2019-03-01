package net.inetalliance.sonar.api;

import com.callgrove.obj.*;
import net.inetalliance.angular.*;
import net.inetalliance.angular.exception.*;
import net.inetalliance.potion.*;
import net.inetalliance.types.www.*;

import javax.servlet.annotation.*;
import javax.servlet.http.*;
import java.io.*;
import java.util.regex.*;

@WebServlet("/api/siteEmailTemplatePreview/*")
public class SiteEmailTemplatePreview
		extends AngularServlet {
	private static Pattern pattern = Pattern.compile("/api/siteEmailTemplatePreview/(\\d+)");

	public SiteEmailTemplatePreview() {
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
			final Site site = Locator.$(new Site(Integer.parseInt(id)));
			if (site == null) {
				throw new NotFoundException("Can't find that site");
			}
			final String content = site.getEmailTemplate() == null
					? ""
					: String.format("<html><head>%s</head><body>%s</body></html>", site.getEmailTemplateStyles() == null
							? ""
							: String.format("<style>%s</style>", site.getEmailTemplateStyles()), site.getEmailTemplate());
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
