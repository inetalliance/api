package net.inetalliance.sonar.api;

import com.callgrove.obj.NodeType;
import com.callgrove.obj.ProductLine;
import com.callgrove.obj.ScriptNode;
import net.inetalliance.angular.Key;
import net.inetalliance.angular.TypeModel;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.angular.list.Listable;
import net.inetalliance.funky.StringFun;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;
import org.joda.time.DateTime;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ScriptRoot
	extends TypeModel<com.callgrove.obj.ScriptRoot>
	implements Listable<com.callgrove.obj.ScriptRoot> {

	private final Info<ProductLine> productLineInfo;

	private static JsonMap json(final com.callgrove.obj.ScriptRoot root) {
		return new JsonMap()
			.$("id", root.id)
			.$("productLine", root.getProductLine().getId())
			.$("root", root.getRoot().id)
			.$("name", root.getName())
			.$("created", root.getCreated());
	}

	private static final Pattern pattern = Pattern.compile("/api/productLine/(\\d+)/scriptRoot/?(\\d+)?");

	ScriptRoot() {
		super(com.callgrove.obj.ScriptRoot.class, pattern);
		this.productLineInfo = Info.$(ProductLine.class);
	}

	@Override
	public Json toJson(final HttpServletRequest request, com.callgrove.obj.ScriptRoot root) {
		return json(root);
	}

	@Override
	public Query<com.callgrove.obj.ScriptRoot> all(final Class<com.callgrove.obj.ScriptRoot> type,
	                                               final HttpServletRequest request) {
		final ProductLine productLine = getProductLine(request);
		return com.callgrove.obj.ScriptRoot.withProductLine(productLine);
	}

	private ProductLine getProductLine(final HttpServletRequest request) {
		final Matcher matcher = pattern.matcher(request.getRequestURI());
		if (matcher.find()) {
			final ProductLine productLine = productLineInfo.lookup(matcher.group(1));
			if (productLine == null) {
				throw new NotFoundException("Cannot find product line");
			}
			return productLine;
		} else {
			throw new BadRequestException("uri should match %s", pattern.pattern());
		}
	}

	@Override
	public JsonMap create(final Key<com.callgrove.obj.ScriptRoot> key, final HttpServletRequest request,
	                      final HttpServletResponse response, final JsonMap data) {
		ScriptNode root = Locator.$(new ScriptNode(data.getInteger("root")));
		if (root == null) {
			root = new ScriptNode();
			root.setType(NodeType.BRANCH);
			root.setPrompt("Start of script");
			Locator.create(request.getRemoteUser(), root);
		} else {
			root = root.duplicate(request.getRemoteUser());
		}
		return super.create(key, request, response, data
			.$("productLine", getProductLine(request).id)
			.$("root", root.id)
			.$("created", new DateTime()));
	}

	@Override
	protected Json toJson(final Key<com.callgrove.obj.ScriptRoot> key,
	                      final com.callgrove.obj.ScriptRoot root, final HttpServletRequest request) {
		return json(root);
	}

	@Override
	protected Key<com.callgrove.obj.ScriptRoot> getKey(final Matcher m) {
		return Key.$(com.callgrove.obj.ScriptRoot.class, StringFun.utf8UrlDecode(m.group(2)));
	}
}
