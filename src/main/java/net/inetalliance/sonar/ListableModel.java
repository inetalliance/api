package net.inetalliance.sonar;

import net.inetalliance.angular.TypeModel;
import net.inetalliance.angular.list.Listable;
import net.inetalliance.funky.StringFun;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.obj.IdPo;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.json.Json;

import javax.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;

import static net.inetalliance.sql.OrderBy.Direction.ASCENDING;

public class ListableModel<T>
	extends TypeModel<T>
	implements Listable<T> {
	private final Info<T> info;

	protected ListableModel(final Class<T> type) {
		this(type, Pattern.compile("/(?:api|reporting)/" + StringFun.camel(type.getSimpleName()) +
			"(?:/(" + getKeyPattern(type) + "))?"));
	}

	protected ListableModel(final Class<T> type, final Pattern pattern) {
		super(type, pattern);
		info = Info.$(type);
	}

	private static String getKeyPattern(final Class type) {
		return IdPo.class.isAssignableFrom(type) ? "\\d+" : ".+";
	}

	@Override
	public Json toJson(final HttpServletRequest request, final T t) {
		return info.toJson(t);
	}

	public static class Named<T extends net.inetalliance.types.Named>
		extends ListableModel<T> {
		protected Named(final Class<T> type) {
			super(type);
		}

		@Override
		public Query<T> all(final Class<T> type, final HttpServletRequest request) {
			return Query.all(type).orderBy("name", ASCENDING);
		}
	}
}
