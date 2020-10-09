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
    private final Nylas nylas = new Nylas();

    public AgentAuth() {
        super();
    }

    protected Json toJson(final HttpServletRequest request, final Authorized authorized) {
        final Object nylasAccount = request.getSession().getAttribute("nylasAccount");
        if (nylasAccount == null) {
            log.warning("No mail account for %s (sudo?)", authorized.getName());
        }
        return Info.$(Agent.class)
                .toJson($(new Agent(authorized.getPhone())))
                .$("nylasAccount", nylasAccount == null ? null : nylasAccount.toString())
                .$("roles", JsonList.collect(authorized.getRoles(), JsonString::new));
    }


    @Override
    protected void onLogin(final HttpServletRequest request, final Authorized authorized) {
        final var password = request.getParameter("password");
        final var agent = Locator.$(new Agent(authorized.getPhone()));
        String grant = null;
        if(isNotEmpty(agent.getNylasCode())) {
            try {
                grant = nylas.token(agent);
            } catch (Nylas.InvalidGrantException e) {
                nylas.authorize(agent,password);
                try {
                    grant = nylas.token(agent);
                } catch (Nylas.InvalidGrantException invalidGrantException) {
                    log.error("Received invalid grant from Nylas on retry for %s",agent.getFullName());
                }
            }
        } else {
            nylas.authorize(agent, password);
            try {
                grant = nylas.token(agent);
            } catch (Nylas.InvalidGrantException e) {
                log.error("Received invalid grant from Nylas on new account for %s",agent.getFullName());
            }
        }
        if(isNotEmpty(grant)) {
            log.info("%s logged in with valid Nylas grant", agent.getFullName());
        } else {
            log.info("%s logged in without valid Nylas grant", agent.getFullName());
        }
        request.getSession().setAttribute("nylasAccount",grant);
    }

}
