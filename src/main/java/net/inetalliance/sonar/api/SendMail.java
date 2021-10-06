package net.inetalliance.sonar.api;

import com.callgrove.obj.Agent;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.ForbiddenException;
import net.inetalliance.log.Log;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.util.mail.MailMessage;
import net.inetalliance.util.mail.PostOffice;

import javax.mail.internet.InternetAddress;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.UnsupportedEncodingException;

@WebServlet("/api/sendMail")
public class SendMail extends AngularServlet {
    public SendMail() {
        super();
    }
    @Override
    protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        var agent = Startup.getAgent(request);
        if(agent == null) {
            throw new ForbiddenException();
        }
        var email = JsonMap.parse(request.getInputStream());
        log.info(Json.pretty(email));
        var msg = new MailMessage(toAddress(email.getList("from").stream().findFirst().map(j->(JsonMap)j).orElseGet(()-> {
            return new JsonMap().$("email",agent.getEmail()).$("name",agent.getFullName());
        })));
        var to = email.getList("to");
        if (to != null) {
            to.stream().map(j -> (JsonMap) j).map(SendMail::toAddress).forEach(msg::addTo);
        }
        var cc = email.getList("cc");
        if (cc != null) {
            cc.stream().map(j -> (JsonMap) j).map(SendMail::toAddress).forEach(msg::addCc);
        }
        var bcc = email.getList("bcc");
        if (bcc != null) {
            bcc.stream().map(j -> (JsonMap) j).map(SendMail::toAddress).forEach(msg::addBcc);
        }
        msg.setSubject(email.get("subject"));
        msg.setBody(null, email.get("body"));
        try {
            PostOffice.send(msg);
        } catch (Throwable t) {
            log.error(t);
            response.sendError(500, t.getMessage());
        }
    }

    private static InternetAddress toAddress(JsonMap address) {
        try {
            return new InternetAddress(address.get("email"), address.get("name"));
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private static final transient Log log = Log.getInstance(SendMail.class);
}
