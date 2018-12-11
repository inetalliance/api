package net.inetalliance.sonar;

import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.dispatch.Dispatchable;
import net.inetalliance.angular.list.Listable;
import net.inetalliance.funky.functors.F1;
import net.inetalliance.funky.functors.types.ClassFun;
import net.inetalliance.funky.functors.types.str.StringFun;
import net.inetalliance.potion.obj.IdPo;
import net.inetalliance.types.Named;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Collection;
import java.util.regex.Pattern;

public abstract class SelectMembers<G extends IdPo & Named, M extends Named>
  extends AngularServlet
  implements Dispatchable, Listable<G> {
  private final Pattern pattern;
  private final Class<G> groupType;
  public static final F1<Class, String> namer = ClassFun.simpleName.chain(StringFun.camel);

  public SelectMembers(final Class<G> groupType, final Class<M> memberType) {
    this.groupType = groupType;
    this.pattern = Pattern.compile(String.format("/api/%s/%s", namer.$(groupType), namer.$(memberType)));
  }

  @Override
  public Pattern getPattern() {
    return pattern;
  }

  private final F1<M, JsonMap> memberJson = new F1<M, JsonMap>() {
    @Override
    public JsonMap $(final M member) {
      return new JsonMap()
        .$("name", member.getName())
        .$("label", getLabel(member))
        .$("id", toId(member));
    }
  };
  private final F1<G, Json>
    groupJson = new F1<G, Json>() {
    @Override
    public Json $(final G group) {
      final Collection<M> members = getMembers(group);
      return members.isEmpty() ? null : new JsonMap()
        .$("name", group.getName())
        .$("id", group.id)
        .$("members", memberJson.map(members));
    }
  };

  protected abstract Json toId(final M member);

  protected abstract String getLabel(final M member);

  protected abstract Collection<M> getMembers(final G group);

  @Override
  protected void get(final HttpServletRequest request, final HttpServletResponse response)
    throws Exception {
    respond(response, Impl.$(groupType, this, request));
  }

  @Override
  public F1<G, ? extends Json> toJson(final HttpServletRequest httpServletRequest) {
    return groupJson;
  }
}
