package net.inetalliance.sonar;

import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.log.Log;
import net.inetalliance.types.json.JsonMap;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.PoolingClientConnectionManager;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static net.inetalliance.log.Log.getInstance;
import static net.inetalliance.types.json.Json.pretty;

public class YouTrack {

  public static final String api = "https://youtrack.inetalliance.net/rest";

  private HttpClient client;

  public YouTrack() {
    super();
    client = new DefaultHttpClient(new PoolingClientConnectionManager());
  }

  public synchronized JsonMap get(final String path)
    throws IOException {
    final HttpGet request = new HttpGet(api + path);
    request.addHeader("Accept", "application/json");
    log.info("[YouTrack] requesting " + path);
    final HttpResponse response = client.execute(request);
    final int code = response.getStatusLine().getStatusCode();
    switch(code) {
      case 200:
        log.info("[YouTrack] Got response");
        return JsonMap.parse(response.getEntity().getContent());
      case 401:
        EntityUtils.consumeQuietly(response.getEntity());
        log.info("[YouTrack] Auth required, logging in");
        final HttpPost login = new HttpPost(api + "/user/login");
        final List<NameValuePair> data = new ArrayList<>(2);
        data.add(new BasicNameValuePair("login","api"));
        data.add(new BasicNameValuePair("password","4sa7ya,o"));
        login.setEntity(new UrlEncodedFormEntity(data));
        final HttpResponse authResponse = client.execute(login);
        if(authResponse.getStatusLine().getStatusCode() == 200) {
          log.info("[YouTrack] Logged in successfully as api");
          EntityUtils.consumeQuietly(authResponse.getEntity());
          return get(path);
        }
        throw new BadRequestException("Could not login to youtrack: %s",authResponse.getStatusLine().getReasonPhrase());
      default:
        throw new BadRequestException("Got back:",response.getStatusLine().getReasonPhrase());

    }

  }

  public void shutdown() {
    client.getConnectionManager().shutdown();
  }

  public static void main(final String[] args)
    throws IOException {
    try {
      final YouTrack youTrack = new YouTrack();
      final JsonMap jsonMap =
        youTrack.get("/issue?filter=project%3A%7BAmeriGlide+Customer+Service%7D+Opportunity+ID%3A+227433");
        //%7D+Opportunity+ID" +
          //"%3A+" + 227433 );+ "&with=id&max=1024");
      System.out.println(pretty(jsonMap));
      youTrack.shutdown();
    }catch (Throwable t) {
      log.error(t);
    }
  }

  private static final transient Log log = getInstance(YouTrack.class);
}
