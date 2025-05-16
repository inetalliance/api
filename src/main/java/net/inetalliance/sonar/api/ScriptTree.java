package net.inetalliance.sonar.api;

import com.callgrove.obj.ProductLine;
import com.callgrove.obj.ScriptNode;
import com.callgrove.obj.ScriptRoot;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.val;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.potion.info.Info;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;

import java.util.regex.Pattern;

import static java.util.stream.Collectors.toMap;
import static net.inetalliance.potion.Locator.count;

@WebServlet("/api/script-tree/*")
public class ScriptTree
        extends AngularServlet {

    private static final Pattern pattern = Pattern.compile("/api/script-tree/(\\d+)/(\\d+)");
    private final Info<ScriptNode> scriptNodeInfo;
    private final Info<ProductLine> productLineInfo;

    public ScriptTree() {
        productLineInfo = Info.$(ProductLine.class);
        scriptNodeInfo = Info.$(ScriptNode.class);
    }

    private static Json toJson(ScriptNode scriptNode) {
        return new JsonMap().$("id", scriptNode.id)
                .$("type", scriptNode.getType().getFormattedName())
                .$("prompt", scriptNode.getPrompt())
                .$("left", scriptNode.getLeft() == null ? null : scriptNode.getLeft().id)
                .$("right", scriptNode.getRight() == null ? null : scriptNode.getRight().id);
    }

    @Override
    protected void get(final HttpServletRequest request, final HttpServletResponse response)
            throws Exception {
        val matcher = pattern.matcher(request.getRequestURI());
        if (matcher.find()) {
            val productLine = productLineInfo.lookup(matcher.group(1));
            val rootNode = scriptNodeInfo.lookup(matcher.group(2));
            if (productLine == null) {
                throw new NotFoundException("Product line %s not found", matcher.group(1));
            } else if (rootNode == null) {
                throw new NotFoundException("Root not found", matcher.group(2));
            } else if (count(ScriptRoot.withProductLine(productLine).and(ScriptRoot.withRoot(rootNode)))
                    == 0) {
                throw new BadRequestException("Node %s is not a root of a %s script", rootNode.id,
                        productLine.getName());
            }
            val nodes = rootNode.getTree();
            val json = new JsonMap(
                    nodes.stream().collect(toMap(n -> n.id.toString(), ScriptTree::toJson)));
            json.put("root", rootNode.id);
            respond(response, json);
        } else {
            throw new BadRequestException("Expected uri to match %s", pattern.pattern());
        }
    }
}
