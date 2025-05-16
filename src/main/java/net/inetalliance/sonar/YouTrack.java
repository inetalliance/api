package net.inetalliance.sonar;

import com.ameriglide.phenix.core.Log;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.types.www.ContentType;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static net.inetalliance.types.json.Json.pretty;

public class YouTrack {

    public static final String api = "https://youtrack.inetalliance.net/rest";
    private static final Log log = new Log();


    public YouTrack() {
        super();
    }

    public static void main(final String[] args) {
        try {
            final YouTrack youTrack = new YouTrack();
            final JsonMap jsonMap =
                    youTrack.get(
                            "/issue?filter=project%3A%7BAmeriGlide+Customer+Service%7D+Opportunity+ID%3A+227433");
            //%7D+Opportunity+ID" +
            //"%3A+" + 227433 );+ "&with=id&max=1024");
            System.out.println(pretty(jsonMap));
        } catch (Throwable t) {
            log.error(t);
        }
    }

    public synchronized JsonMap get(final String path)
            throws IOException {
        var req = HttpRequest.newBuilder()
                .uri(java.net.URI.create(api + path))
                .method("GET", HttpRequest.BodyPublishers.noBody())
                .setHeader("Accept", "application/json")
                .build();
        try (var http = HttpClient.newHttpClient()) {
            log.debug(()->"[YouTrack] requesting %s".formatted(path));
            var res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            switch (res.statusCode()) {
                case 200 -> {
                    log.debug("[YouTrack] Got response");
                    return JsonMap.parse(res.body());
                }
                case 401 -> {
                    var login = HttpRequest.newBuilder()
                            .uri(java.net.URI.create("%s/user/login".formatted(api)))
                            .method("POST", HttpRequest.BodyPublishers.ofString("login=api&password=4sa7ya,o"))
                            .setHeader("Content-Type", ContentType.URL_ENCODED.value)
                            .build();
                    var loginRes = http.send(login, HttpResponse.BodyHandlers.ofString());
                    if (loginRes.statusCode() == 200) {
                        log.debug("[YouTrack] Logged in successfully as api");
                        return JsonMap.parse(loginRes.body());
                    }
                    throw new BadRequestException("Could not login to youtrack: %s",
                            loginRes.statusCode());
                }
                default -> throw new BadRequestException("Got back:", res.statusCode());
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}

