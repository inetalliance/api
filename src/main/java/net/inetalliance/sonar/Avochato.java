package net.inetalliance.sonar;

import com.ameriglide.phenix.core.Log;
import com.callgrove.obj.Opportunity;
import com.callgrove.obj.Site;
import net.inetalliance.potion.Locator;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.types.struct.maps.CaseInsensitiveStringMultivalueMap;
import net.inetalliance.types.struct.maps.MultivalueMap;
import net.inetalliance.types.util.UrlUtil;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;

import static java.net.http.HttpResponse.BodyHandlers.ofInputStream;

public class Avochato {

    private static final Log log = new Log();
    private static final String host = "www.avochato.com";
    private static Avochato instance;

    private Avochato() {
        // singleton
    }

    public static Avochato getInstance() {
        if (instance == null) {
            instance = new Avochato();
        }
        return instance;
    }

    public final JsonList searchContacts(final Opportunity opportunity)
            throws IOException, URISyntaxException {
        final Site site = Locator.$(opportunity.getSite());
        if (!site.hasAvochato()) {
            return JsonList.empty;
        }
        final MultivalueMap<String, String> params = new CaseInsensitiveStringMultivalueMap<>(3);
        params.add("auth_id", site.getAvochatoAuthId()); // "leMALzrBz2");
        params.add("auth_secret", site.getAvochatoAuthSecret()); // "dcd743c8a3e645b2");
        params.add("query", opportunity.getContact().getPhone());

        final URI uri = new URI("https", host, "/v1/contacts", UrlUtil.toQueryString(params), null);
        try (var http = HttpClient.newHttpClient()) {
            var req = HttpRequest.newBuilder()
                    .uri(uri)
                    .method("GET", HttpRequest.BodyPublishers.noBody())
                    .setHeader("Accept", "application/json")
                    .setHeader("Host", host)
                    .build();
            var res = http.send(req, ofInputStream());
            if (res.statusCode() == 200) {
                return JsonMap.parse(res.body())
                        .getMap("data")
                        .getList("contacts")
                        .stream().map(json -> {
                            final JsonMap map = ((JsonMap) json);
                            return map.$("url", String
                                    .format("https://www.avochato.com/accounts/%s/contacts/%s", site.getAvochatoKey(),
                                            map.get("id")));
                        }).collect(JsonList.collect);
            } else {
                log.error("Avochato returned: %s", res.statusCode());
                return JsonList.empty;
            }

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

    }
}
