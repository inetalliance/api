package net.inetalliance.sonar.api;

import static net.inetalliance.potion.Locator.count;

import com.callgrove.obj.ProductLine;
import com.callgrove.obj.ScriptNode;
import com.callgrove.obj.ScriptRoot;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.funky.Funky;
import net.inetalliance.potion.info.Info;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;

@WebServlet("/api/script-tree/*")
public class ScriptTree
    extends AngularServlet {

  private static Pattern pattern = Pattern.compile("/api/script-tree/(\\d+)/(\\d+)");
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
    final Matcher matcher = pattern.matcher(request.getRequestURI());
    if (matcher.find()) {
      final ProductLine productLine = productLineInfo.lookup(matcher.group(1));
      final ScriptNode rootNode = scriptNodeInfo.lookup(matcher.group(2));
      if (productLine == null) {
        throw new NotFoundException("Product line %s not found", matcher.group(1));
      } else if (rootNode == null) {
        throw new NotFoundException("Root not found", matcher.group(2));
      } else if (count(ScriptRoot.withProductLine(productLine).and(ScriptRoot.withRoot(rootNode)))
          == 0) {
        throw new BadRequestException("Node %s is not a root of a %s script", rootNode.id,
            productLine.getName());
      }
      final Collection<ScriptNode> nodes = rootNode.getTree();
      final JsonMap json = new JsonMap(
          nodes.stream().collect(Funky.toMap(n -> n.id.toString(), ScriptTree::toJson)));
      json.put("root", rootNode.id);
      respond(response, json);
    } else {
      throw new BadRequestException("Expected uri to match %s", pattern.pattern());
    }
  }
}
