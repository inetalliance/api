package net.inetalliance.sonar.api;

import com.callgrove.obj.Site;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.potion.Locator;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.types.util.FileUtil;

import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.UUID;

@MultipartConfig
@WebServlet("/api/uploadAttachment")
public class UploadAttachment
		extends AngularServlet {

	@Override
	protected void post(final HttpServletRequest request, final HttpServletResponse response)
			throws Exception {
		final Part file = request.getPart("file");
		final String filename = getFilename(file);
		final InputStream input = file.getInputStream();
		final String siteId = request.getParameter("site");
		if (siteId == null) {
			throw new NotFoundException();
		}
		final Site site = Locator.$(new Site(Integer.parseInt(siteId)));
		if (site == null) {
			throw new NotFoundException();
		}

		final String url = UUID.randomUUID().toString() + "/" + filename;
		final File destination = new File("/x/attachments/" + site.getDomain() + "/" + url);
		final File uuidDir = destination.getParentFile();
		final File siteDir = destination.getParentFile().getParentFile();
		if (!siteDir.exists()) {
			siteDir.mkdir();
		}
		if (!uuidDir.exists()) {
			uuidDir.mkdir();
		}
		destination.createNewFile();

		final FileOutputStream output = new FileOutputStream(destination);
		FileUtil.copy(input, output, true);

		respond(response, new JsonList(Collections.singleton(
				new JsonMap().$("path", url).$("filename", filename).$("contentType", file.getContentType()))));
	}

	private static String getFilename(Part part) {
		for (String cd : part.getHeader("content-disposition").split(";")) {
			if (cd.trim().startsWith("filename")) {
				String filename = cd.substring(cd.indexOf('=') + 1).trim().replace("\"", "");
				return filename.substring(filename.lastIndexOf('/') + 1).substring(filename.lastIndexOf('\\') + 1); // MSIE
				// fix.
			}
		}
		return null;
	}
}
