package net.inetalliance.sonar;

import com.callgrove.obj.Agent;
import net.inetalliance.angular.auth.Auth;
import net.inetalliance.log.Log;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.info.Info;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonString;
import net.inetalliance.util.security.auth.Authorized;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;

import static net.inetalliance.funky.StringFun.isNotEmpty;
import static net.inetalliance.log.Log.getInstance;
import static net.inetalliance.potion.Locator.$;

@WebServlet({"/login", "/logout"})
public class AgentAuth
        extends Auth {

    private static final transient Log log = getInstance(AgentAuth.class);

    public AgentAuth() {
        super();
    }

    protected Json toJson(final HttpServletRequest request, final Authorized authorized) {
        return Info.$(Agent.class)
                .toJson($(new Agent(authorized.getPhone())))
                .$("roles", JsonList.collect(authorized.getRoles(), JsonString::new));
    }

}
