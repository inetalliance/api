package net.inetalliance.sonar;

import com.callgrove.Callgrove;
import com.callgrove.obj.Opportunity;
import com.callgrove.obj.Site;
import net.inetalliance.log.Log;
import net.inetalliance.potion.Locator;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;
import net.inetalliance.types.struct.maps.CaseInsensitiveStringMultivalueMap;
import net.inetalliance.types.struct.maps.MultivalueMap;
import net.inetalliance.types.util.UrlUtil;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;

public class Avochato {

  private static final transient Log log = Log.getInstance(Avochato.class);
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
      throws IOException, URISyntaxException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyStoreException,
      KeyManagementException {
    final Site site = Locator.$(opportunity.getSite());
    if (!site.hasAvochato()) {
      return JsonList.empty;
    }
    final MultivalueMap<String, String> params = new CaseInsensitiveStringMultivalueMap<>(3);
    params.add("auth_id", site.getAvochatoAuthId()); // "leMALzrBz2");
    params.add("auth_secret", site.getAvochatoAuthSecret()); // "dcd743c8a3e645b2");
    params.add("query", opportunity.getContact().getPhone());

    final URI uri = new URI("https", host, "/v1/contacts", UrlUtil.toQueryString(params), null);
    final HttpGet request = new HttpGet(uri);
    try {
      request.addHeader("Accept", "application/json");
      request.addHeader("Host", host);

      final HttpResponse response = Callgrove.http.execute(request);
      final int code = response.getStatusLine().getStatusCode();
      if (code != 200) {
        log.error("Avochato returned: %s", code);
        return JsonList.empty;
      }
      return JsonMap.parse(response.getEntity().getContent()).getMap("data").getList("contacts")
              .stream().map(json -> {
                final JsonMap map = ((JsonMap) json);
                return map.$("url", String
                        .format("https://www.avochato.com/accounts/%s/contacts/%s", site.getAvochatoKey(),
                                map.get("id")));
              }).collect(JsonList.collect);
    }
    finally {
      request.releaseConnection();
    }
  }
}
