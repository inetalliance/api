package net.inetalliance.sonar.api;

import com.callgrove.obj.NodeType;
import com.callgrove.obj.ProductLine;
import com.callgrove.obj.ScriptNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.val;
import net.inetalliance.angular.Key;
import net.inetalliance.angular.TypeModel;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.angular.list.Listable;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ScriptRoot
        extends TypeModel<com.callgrove.obj.ScriptRoot>
        implements Listable<com.callgrove.obj.ScriptRoot> {

    private static final Pattern pattern = Pattern
            .compile("/api/productLine/(\\d+)/scriptRoot/?(\\d+)?");
    private final Info<ProductLine> productLineInfo;

    ScriptRoot() {
        super(com.callgrove.obj.ScriptRoot.class, pattern);
        this.productLineInfo = Info.$(ProductLine.class);
    }

    private static JsonMap json(final com.callgrove.obj.ScriptRoot root) {
        return new JsonMap().$("id", root.id)
                .$("productLine", root.getProductLine().getId())
                .$("root", root.getRoot().id)
                .$("name", root.getName())
                .$("created", root.getCreated());
    }

    @Override
    public Json toJson(final HttpServletRequest request, com.callgrove.obj.ScriptRoot root) {
        return json(root);
    }

    @Override
    public Query<com.callgrove.obj.ScriptRoot> all(final Class<com.callgrove.obj.ScriptRoot> type,
                                                   final HttpServletRequest request) {
        val productLine = getProductLine(request);
        return com.callgrove.obj.ScriptRoot.withProductLine(productLine);
    }

    private ProductLine getProductLine(final HttpServletRequest request) {
        val matcher = pattern.matcher(request.getRequestURI());
        if (matcher.find()) {
            val productLine = productLineInfo.lookup(matcher.group(1));
            if (productLine == null) {
                throw new NotFoundException("Cannot find product line");
            }
            return productLine;
        } else {
            throw new BadRequestException("uri should match %s", pattern.pattern());
        }
    }

    @Override
    public JsonMap create(final Key<com.callgrove.obj.ScriptRoot> key,
                          final HttpServletRequest request,
                          final HttpServletResponse response, final JsonMap data) {
        var root = Locator.$(new ScriptNode(data.getInteger("root")));
        if (root == null) {
            root = new ScriptNode();
            root.setType(NodeType.BRANCH);
            root.setPrompt("Start of script");
            Locator.create(request.getRemoteUser(), root);
        } else {
            root = root.duplicate(request.getRemoteUser());
        }
        return super.create(key, request, response, data.$("productLine", getProductLine(request).id)
                .$("root", root.id)
                .$("created", LocalDateTime.now()));
    }

    @Override
    protected Json toJson(final Key<com.callgrove.obj.ScriptRoot> key,
                          final com.callgrove.obj.ScriptRoot root,
                          final HttpServletRequest request) {
        return json(root);
    }

    @Override
    protected Key<com.callgrove.obj.ScriptRoot> getKey(final Matcher m) {
        return Key.$(com.callgrove.obj.ScriptRoot.class, URLDecoder.decode(m.group(2), StandardCharsets.UTF_8));
    }
}
