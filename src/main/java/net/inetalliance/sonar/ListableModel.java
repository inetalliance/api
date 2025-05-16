package net.inetalliance.sonar;

import static net.inetalliance.sql.OrderBy.Direction.ASCENDING;

import java.util.regex.Pattern;

import com.ameriglide.phenix.core.Classes;
import jakarta.servlet.http.HttpServletRequest;
import net.inetalliance.angular.TypeModel;
import net.inetalliance.angular.list.Listable;
import net.inetalliance.potion.info.Info;
import net.inetalliance.potion.obj.IdPo;
import net.inetalliance.potion.query.Query;
import net.inetalliance.types.json.Json;

public class ListableModel<T>
    extends TypeModel<T>
    implements Listable<T> {

  private final Info<T> info;

  protected ListableModel(final Class<T> type) {
    this(type, Pattern.compile(
        "/(?:api|reporting)/" + Classes.camel(type) + "(?:/(" + getKeyPattern(
            type) + "))?"));
  }

  protected ListableModel(final Class<T> type, final Pattern pattern) {
    super(type, pattern);
    info = Info.$(type);
  }

  private static String getKeyPattern(final Class<?> type) {
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
