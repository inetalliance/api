package net.inetalliance.sonar;

import net.inetalliance.angular.TypeModel;
import net.inetalliance.angular.list.Listable;
import net.inetalliance.funky.functors.F1;
import net.inetalliance.funky.functors.types.str.StringFun;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.obj.IdPo;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;

import javax.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;

import static net.inetalliance.sql.OrderBy.Direction.*;

public class ListableModel<T>
  extends TypeModel<T>
  implements Listable<T> {

  protected final F1<T, JsonMap> json;

  protected ListableModel(final Class<T> type) {
    this(type, Pattern.compile("/(?:api|reporting)/" + StringFun.lowerFirst.$(type.getSimpleName ()) +
      "(?:/(" + getKeyPattern(type) + "))?"));
  }

  protected ListableModel(final Class<T> type, final Pattern pattern) {
    super(type, pattern);
    json = Info.$(type).toJson();
  }

  private static String getKeyPattern(final Class type) {
    return IdPo.class.isAssignableFrom(type) ? "\\d+" : ".+";
  }

  @Override
  public F1<T, ? extends Json> toJson(final HttpServletRequest request) {
    return json;
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
