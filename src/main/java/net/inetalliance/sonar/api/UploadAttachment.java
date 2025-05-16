package net.inetalliance.sonar.api;

import com.ameriglide.phenix.core.Log;
import com.callgrove.obj.Site;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import lombok.val;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.potion.Locator;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.UUID;

@MultipartConfig
@WebServlet("/api/uploadAttachment")
public class UploadAttachment
        extends AngularServlet {
    private static final Log log = new Log();

    private static String getFilename(Part part) {
        for (var cd : part.getHeader("content-disposition").split(";")) {
            if (cd.trim().startsWith("filename")) {
                var filename = cd.substring(cd.indexOf('=') + 1).trim().replace("\"", "");
                return filename.substring(filename.lastIndexOf('/') + 1)
                        .substring(filename.lastIndexOf('\\') + 1); // MSIE
                // fix.
            }
        }
        return null;
    }

    @Override
    protected void post(final HttpServletRequest request, final HttpServletResponse response)
            throws Exception {
        val file = request.getPart("file");
        val filename = getFilename(file);
        val input = file.getInputStream();
        val siteId = request.getParameter("site");
        if (siteId == null) {
            throw new NotFoundException();
        }
        val site = Locator.$(new Site(Integer.parseInt(siteId)));
        if (site == null) {
            throw new NotFoundException();
        }

        val url = "%s/%s".formatted(UUID.randomUUID(), filename);
        val destination = new File("/x/attachments/" + site.getDomain() + "/" + url);
        val uuidDir = destination.getParentFile();
        val siteDir = destination.getParentFile().getParentFile();
        if (!siteDir.exists()) {
            if (!siteDir.mkdir()) {
                log.warn(() -> "Could not create dir %s".formatted(siteDir.getAbsolutePath()));
            }
        }
        if (!uuidDir.exists()) {
            if (!uuidDir.mkdir()) {
                log.warn(() -> "Could not create dir %s".formatted(uuidDir.getAbsolutePath()));
            }
        }

        Files.copy(input, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);

        respond(response, new JsonList(Collections.singleton(
                new JsonMap().$("path", url).$("filename", filename)
                        .$("contentType", file.getContentType()))));
    }
}
