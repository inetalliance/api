package net.inetalliance.sonar;

import com.callgrove.obj.ProductLine;
import com.callgrove.obj.ScriptNode;
import com.callgrove.obj.ScriptRoot;
import net.inetalliance.angular.Key;
import net.inetalliance.angular.TypeModel;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.funky.functors.F1;
import net.inetalliance.funky.functors.P1;
import net.inetalliance.funky.functors.types.str.StringFun;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.info.Info;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.callgrove.obj.ScriptNode.Q.withLeft;
import static com.callgrove.obj.ScriptNode.Q.withRight;
import static net.inetalliance.potion.Locator.$1;
import static net.inetalliance.potion.Locator.count;

@WebServlet("/api/script/*")
public class Script
  extends TypeModel<ScriptNode> {

  private static final F1<ScriptNode, JsonMap> pathJson = new F1<ScriptNode, JsonMap>() {
    @Override
    public JsonMap $(final ScriptNode scriptNode) {
      return new JsonMap()
        .$("id", scriptNode.id)
        .$("type", scriptNode.getType().getFormattedName())
        .$("prompt", scriptNode.getPrompt());
    }
  };

  public Script() {
    super(ScriptNode.class, Pattern.compile("/api/script/?([^/]*)?"));
  }

  @Override
  protected Key<ScriptNode> getKey(final Matcher m) {
    final String match = StringFun.utf8UrlDecode.$(m.group(1));
    final String[] tokens = match.split("\\+");
    return Key.$(ScriptNode.class, tokens[tokens.length - 1]);
  }

  @Override
  protected Json delete(final HttpServletRequest request, final ScriptNode parent) {
    final String branch = request.getParameter("branch");
    if (branch == null) {
      throw new BadRequestException("Missing \"branch\" parameter");
    }
    final ScriptNode node;
    final boolean isLeft;

    // delete reference to the node, passing through to the child if the node only has one child.
    if ("left".equalsIgnoreCase(branch)) {
      node = parent.getLeft();
      isLeft = true;
    } else if ("right".equalsIgnoreCase(branch)) {
      node = parent.getRight();
      isLeft = false;
    } else {
      throw new BadRequestException("Invalid \"branch\" parameter");
    }

    final int referenceCount = count(withLeft(node)) + count(withRight(node));

    if (node.getLeft() != null && node.getRight() != null && referenceCount == 1) {
      return new JsonMap().$("error",
        "Cannot remove this script node because it has two children. Please remove its children first.");
    }

    // delete reference to the node, passing through to the child if the node only has one child.
    Locator.update(parent, getRemoteUser(request), new P1<ScriptNode>() {
      @Override
      public void $(final ScriptNode copy) {
        final ScriptNode newValue = node.isPassThrough() ? node.getLeft() : null;
        if (isLeft) {
          copy.setLeft(newValue);
          // rotate right over to left if we've got one
          if (parent.getRight() != null) {
            copy.setLeft(parent.getRight());
            copy.setRight(null);
          }
        } else {
          copy.setRight(newValue);
        }
      }
    });

    // determine if we can delete this node
    if (referenceCount == 1) {
      // no references (we just removed the last one). kill it!
      Locator.delete(getRemoteUser(request), node);
    }
    return toJson(Key.$(ScriptNode.class, Integer.toString(parent.id)), parent, request);
  }

  @Override
  protected Json toJson(final Key<ScriptNode> key, final ScriptNode node, final HttpServletRequest request) {
    final JsonMap json = (JsonMap) super.toJson(key, node, request);
    final Info<ScriptNode> info = Info.$(ScriptNode.class);
    final Matcher matcher = pattern.matcher(request.getRequestURI());
    if (!matcher.find()) {
      throw new BadRequestException("pattern somehow not matching in toJson()???");
    }
    ProductLine productLine = null;
    if ("get".equalsIgnoreCase(request.getMethod())) {
      final String pathString = StringFun.utf8UrlDecode.$(matcher.group(1));
      if (pathString.length() > 0) {
        Collection<ScriptNode> path = info.lookup.copy(pathString.split("\\+"));
        final ScriptRoot root = $1(ScriptRoot.Q.withRoot(path.iterator().next()));
        if (root == null) {
          throw new NotFoundException();
        }
        productLine = root.getProductLine();
        json
          .$("productLine", new JsonMap().$("id", productLine.id).$("name", productLine.getName()))
          .$("root", root.getName())
          .$("path", pathJson.map(path));
      }
    }

    final ScriptNode left = node.getLeft();
    if (left != null) {
      json.$("leftType", left.getType());
      if (!StringFun.empty.$(left.getPrompt())) {
        json.$("leftPrompt", left.getPrompt());
      }
    }
    final ScriptNode right = node.getRight();
    if (right != null) {
      json.$("rightType", right.getType());
      if (!StringFun.empty.$(right.getPrompt())) {
        json.$("rightPrompt", right.getPrompt());
      }
    }

    // find all nodes this one could link to
    if (productLine != null && (left == null || right == null)) {
      final Collection<ScriptNode> allPossible = productLine.getRoot().getTree();
      allPossible.remove(node);
      json.$("potentialChildren", pathJson.map(allPossible));
    }
    return json;
  }

  @Override
  protected void postCreate(final ScriptNode scriptNode, final HttpServletRequest request,
                            final HttpServletResponse response) {
    final String productLineKey = request.getParameter("productLine");
    if (productLineKey != null) {
      final ProductLine productLine = Info.$(ProductLine.class).lookup.$(productLineKey);
      Locator.update(productLine, getRemoteUser(request), new P1<ProductLine>() {
        @Override
        public void $(final ProductLine copy) {
          copy.setRoot(scriptNode);
        }
      });
    } else {
      final String parentId = request.getParameter("parent");
      final String branch = request.getParameter("branch");
      if (parentId != null && branch != null) {
        final ScriptNode parent = Info.$(ScriptNode.class).lookup.$(parentId);
        Locator.update(parent, getRemoteUser(request), new P1<ScriptNode>() {
          @Override
          public void $(final ScriptNode copy) {
            if ("left".equalsIgnoreCase(branch)) {
              copy.setLeft(scriptNode);
            } else {
              copy.setRight(scriptNode);
            }
          }
        });
      }
    }
  }
}
