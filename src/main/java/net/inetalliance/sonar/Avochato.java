package net.inetalliance.sonar;

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
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.PoolingClientConnectionManager;

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
	private HttpClient client;

	private Avochato() {
		// singleton
	}

	private static Avochato instance;

	public static Avochato getInstance() {
		if (instance == null) {
			instance = new Avochato();
		}
		return instance;
	}

	private HttpClient getClient()
			throws UnrecoverableKeyException, NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
		if (this.client == null) {
			TrustStrategy acceptingTrustStrategy = (cert, authType) -> true;
			SSLSocketFactory sf = new SSLSocketFactory(acceptingTrustStrategy, SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
			SchemeRegistry registry = new SchemeRegistry();
			registry.register(new Scheme("https", 443, sf));
			ClientConnectionManager ccm = new PoolingClientConnectionManager(registry);
			this.client = new DefaultHttpClient(ccm);
		}
		return client;
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
		request.addHeader("Accept", "application/json");
		request.addHeader("Host", host);

		final HttpResponse response = getClient().execute(request);
		final int code = response.getStatusLine().getStatusCode();
		if (code != 200) {
			log.error("Avochato returned: %s", code);
			return JsonList.empty;
		}
		return JsonMap.parse(response.getEntity().getContent()).getMap("data").getList("contacts").stream().map(json -> {
			final JsonMap map = ((JsonMap) json);
			return map.$("url", String.format("https://www.avochato.com/accounts/%s/contacts/%s", site.getAvochatoKey(),
			                                  map.get("id")));
		}).collect(JsonList.collect);
	}
}
