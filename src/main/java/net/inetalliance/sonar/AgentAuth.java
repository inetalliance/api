package net.inetalliance.sonar;

import com.callgrove.obj.Agent;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import net.inetalliance.angular.auth.Auth;
import net.inetalliance.potion.info.Info;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonString;
import net.inetalliance.util.security.auth.Authorized;

import static net.inetalliance.potion.Locator.$;

@WebServlet({"/login", "/logout"})
public class AgentAuth
        extends Auth {

    public AgentAuth() {
        super();
    }

    protected Json toJson(final HttpServletRequest request, final Authorized authorized) {
        return Info.$(Agent.class)
                .toJson($(new Agent(authorized.getPhone())))
                .$("roles", JsonList.collect(authorized.getRoles(), JsonString::new));
    }

}
