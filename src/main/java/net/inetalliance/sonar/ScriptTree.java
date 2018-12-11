package net.inetalliance.sonar;

import com.callgrove.obj.ProductLine;
import com.callgrove.obj.ScriptNode;
import com.callgrove.obj.ScriptRoot;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.funky.functors.F1;
import net.inetalliance.funky.functors.types.str.FormatValue;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.obj.IdPo;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.inetalliance.potion.Locator.count;

@WebServlet("/api/script-tree/*")
public class ScriptTree
		extends AngularServlet {

	private static final F1<ScriptNode, Json> toJson = new F1<ScriptNode, Json>() {
		@Override
		public Json $(final ScriptNode scriptNode) {
			return new JsonMap()
					.$("id", scriptNode.id)
					.$("type", scriptNode.getType().getFormattedName())
					.$("prompt", scriptNode.getPrompt())
					.$("left", scriptNode.getLeft() == null ? null : scriptNode.getLeft().id)
					.$("right", scriptNode.getRight() == null ? null : scriptNode.getRight().id);
		}
	};
	private static Pattern pattern = Pattern.compile("/api/script-tree/(\\d+)/(\\d+)");
	private final F1<String, ProductLine> productLineLookup;
	private final F1<String, ScriptNode> scriptNodeLookup;

	public ScriptTree() {
		productLineLookup = Info.$(ProductLine.class).lookup;
		scriptNodeLookup = Info.$(ScriptNode.class).lookup;
	}

	@Override
	protected void get(final HttpServletRequest request, final HttpServletResponse response)
			throws Exception {
		final Matcher matcher = pattern.matcher(request.getRequestURI());
		if (matcher.find()) {
			final ProductLine productLine = productLineLookup.$(matcher.group(1));
			final ScriptNode rootNode = scriptNodeLookup.$(matcher.group(2));
			if (productLine == null) {
				throw new NotFoundException("Product line %s not found", matcher.group(1));
			} else if (rootNode == null) {
				throw new NotFoundException("Root not found", matcher.group(2));
			} else if (count(ScriptRoot.Q.withProductLine(productLine)
					.and(ScriptRoot.Q.withRoot(rootNode))) == 0) {
				throw new BadRequestException("Node %s is not a root of a %s script", rootNode.id, productLine.getName());
			}
			final Collection<ScriptNode> nodes = rootNode.getTree();
			final JsonMap json = new JsonMap(F1.map(nodes, IdPo.F.id.chain(FormatValue.$), toJson));
			json.put("root", rootNode.id);
			respond(response, json);
		} else {
			throw new BadRequestException("Expected uri to match %s", pattern.pattern());
		}
	}
}
