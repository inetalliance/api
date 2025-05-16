package net.inetalliance.sonar.api;

import com.ameriglide.phenix.core.Classes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Getter;
import lombok.val;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.list.Listable;
import net.inetalliance.potion.obj.IdPo;
import net.inetalliance.types.Named;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;

import java.util.Collection;
import java.util.regex.Pattern;

public abstract class SelectMembers<G extends IdPo & Named, M extends Named>
        extends AngularServlet
        implements Listable<G> {

    @Getter
    private final Pattern pattern;
    private final Class<G> groupType;

    SelectMembers(final Class<G> groupType, final Class<M> memberType) {
        this.groupType = groupType;
        this.pattern = Pattern.compile(String.format("/api/%s/%s", name(groupType), name(memberType)));
    }

    public static String name(Class<?> c) {
        return Classes.camel(c);
    }

    private JsonMap memberJson(final M member) {
        return new JsonMap().$("name", member.getName()).$("label", getLabel(member))
                .$("id", toId(member));
    }

    private Json groupJson(final G group) {
        val members = getMembers(group);
        return members.isEmpty()
                ? null
                : new JsonMap().$("name", group.getName())
                .$("id", group.id)
                .$("members", JsonList.collect(members, this::memberJson));
    }

    protected abstract Json toId(final M member);

    protected abstract String getLabel(final M member);

    protected abstract Collection<M> getMembers(final G group);

    @Override
    protected void get(final HttpServletRequest request, final HttpServletResponse response)
            throws Exception {
        respond(response, Listable.$(groupType, this, request));
    }

    @Override
    public Json toJson(final HttpServletRequest httpServletRequest, final G g) {
        return groupJson(g);
    }
}
