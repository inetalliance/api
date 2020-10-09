package net.inetalliance.sonar;

import com.callgrove.obj.Agent;
import net.inetalliance.log.Log;
import net.inetalliance.potion.Locator;
import net.inetalliance.sonar.api.Startup;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonMap;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import java.util.Base64;

import java.io.IOException;

import static net.inetalliance.funky.StringFun.isEmpty;
import static net.inetalliance.funky.StringFun.isNotEmpty;

public class Nylas {
    private final static transient Log log = Log.getInstance(Nylas.class);

    public Nylas() {
        ipAddresses();
    }

    private void ipAddresses() {
        final var get = new HttpGet("https://api.nylas.com/a/ccp8e4h3wu7i4s2o5mdi3hlne/ip_addresses");
        get.addHeader("Authorization", "Basic " + Base64.getEncoder()
                .encodeToString("f02ufhmcopkrzbc35ah6w7wto:".getBytes()));
        try {
            final var res = Startup.http.execute(get);
            final var ipAddresses = JsonMap.parse(res.getEntity().getContent());
            if (ipAddresses == null) {
                log.warning("Nylas did not return an IP list");
            } else {
                log.info("Nylas IP addresses: %s", Json.pretty(ipAddresses));
            }
        } catch (IOException e) {
            log.error(e);
        } finally {
            get.releaseConnection();
        }
    }

    private JsonMap postNylas(final String endpoint, final JsonMap data) {
        final var post = new HttpPost(String.format("https://api.nylas.com%s", endpoint));
        post.setEntity(new StringEntity(Json.pretty(data), ContentType.APPLICATION_JSON));
        try  {
            final var res = Startup.http.execute(post);
            return JsonMap.parse(res.getEntity().getContent());
        } catch (IOException e) {
            log.error(e);
        } finally {
            post.releaseConnection();
        }
        return null;
    }

    public void authorize(final Agent agent, final String password) {
        var json = new JsonMap()
                .$("client_id", "ccp8e4h3wu7i4s2o5mdi3hlne")
                .$("name", agent.getFullName())
                .$("email_address", agent.getEmail())
                .$("provider", "imap")
                .$("settings", new JsonMap().$("imap_host", "mail.inetalliance.net")
                        .$("imap_port", 993)
                        .$("imap_username", agent.getEmail())
                        .$("imap_password", password)
                        .$("smtp_host", "mail.inetalliance.net")
                        .$("smtp_username", agent.getEmail())
                        .$("smtp_port", 587)
                        .$("smtp_password", password)
                        .$("ssl_required", true));
        var account = postNylas("/connect/authorize", json);
        if (account != null) {
            final String nylasCode = account.get("code");
            final var newAccount = isEmpty(agent.getNylasCode());
            if (isNotEmpty(nylasCode)) {
                Locator.update(agent, "Nylas", copy -> {
                    copy.setNylasCode(nylasCode);
                });
                if (newAccount) {
                    log.info("New Nylas account created successfully for %s", agent.getFullName());
                } else {
                    log.info("Existing Nylas account reauthorized for %s", agent.getFullName());
                }
            } else if(newAccount){
                log.error("New Nylas account for %s did not contain a code: %s",
                        agent.getFullName(), Json.pretty(account));
            } else {
                log.error("Existing Nylas account reauth for %s did not contain a code: %s",
                        agent.getFullName(), Json.pretty(account));

            }
        }
    }

    public String token(final Agent agent) throws InvalidGrantException {
        final var json = postNylas("/connect/token",
                new JsonMap().$("client_id", "ccp8e4h3wu7i4s2o5mdi3hlne")
                        .$("client_secret", "f02ufhmcopkrzbc35ah6w7wto")
                        .$("code", agent.getNylasCode()));
        final String token = json == null ? null : json.get("access_token");
        if (json == null) {
            log.error("No response received from Nylas /connect/token for %s", agent.getFullName());
            return null;
        } else if (isNotEmpty(token)) {
            return token;
        } else if ("api_error".equals(json.get("type")) && json.get("message")
                .startsWith("No grant with code")) {
            throw new InvalidGrantException();
        } else {
            log.error("%s got an unexpected error from Nylas /connect/token: %s", Json.pretty(json));
            return null;
        }
    }

    public static class InvalidGrantException extends Exception {

    }
}
