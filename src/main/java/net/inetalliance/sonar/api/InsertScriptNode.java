package net.inetalliance.sonar.api;

import com.callgrove.obj.NodeType;
import com.callgrove.obj.ScriptNode;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.info.Info;
import net.inetalliance.types.json.JsonMap;

@WebServlet("/api/insertScript")
public class InsertScriptNode
    extends AngularServlet {

  private final Info<ScriptNode> info;

  public InsertScriptNode() {
    info = Info.$(ScriptNode.class);
  }

  protected void post(final HttpServletRequest request, final HttpServletResponse response)
      throws Exception {
    final ScriptNode from = info.lookup(request.getParameter("from"));
    if (from == null) {
      throw new NotFoundException();
    }
    final ScriptNode next = info.lookup(request.getParameter("next"));
    if (next == null) {
      throw new NotFoundException();
    }
    final NodeType type = getParameter(request, NodeType.class, "type");
    if (type == null) {
      throw new BadRequestException("Missing 'type' parameter");
    }
    final boolean wasRight;
    if (next.equals(from.getRight())) {
      wasRight = true;
    } else if (next.equals(from.getLeft())) {
      wasRight = false;
    } else {
      throw new BadRequestException("'next' was not left or right of 'from'");
    }

    final ScriptNode node = new ScriptNode();
    node.setType(type);
    if ("right".equalsIgnoreCase(request.getParameter("side"))) {
      node.setRight(next);
    } else {
      node.setLeft(next);
    }
    Locator.create(request.getRemoteUser(), node);

    Locator.update(from, request.getRemoteUser(), copy -> {
      if (wasRight) {
        copy.setRight(node);
      } else {
        copy.setLeft(node);
      }
    });

    respond(response, new JsonMap().$("id", node.id));
  }
}
