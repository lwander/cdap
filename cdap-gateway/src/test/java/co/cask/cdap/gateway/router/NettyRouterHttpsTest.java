/*
 * Copyright © 2014-2019 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.gateway.router;

import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.conf.SConfiguration;
import co.cask.cdap.common.guice.InMemoryDiscoveryModule;
import co.cask.cdap.internal.guice.AppFabricTestModule;
import co.cask.cdap.security.auth.AccessTokenTransformer;
import co.cask.cdap.security.guice.SecurityModules;
import com.google.common.net.InetAddresses;
import com.google.inject.Guice;
import com.google.inject.Injector;
import io.cdap.common.http.HttpRequests;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.AllowAllHostnameVerifier;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.BasicClientConnectionManager;
import org.apache.twill.discovery.DiscoveryService;
import org.apache.twill.discovery.DiscoveryServiceClient;
import org.junit.Assert;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.security.SecureRandom;
import javax.net.SocketFactory;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

/**
 * Tests Netty Router running on HTTPS.
 */
public class NettyRouterHttpsTest extends NettyRouterTestBase {

  @Override
  protected RouterService createRouterService(String hostname, DiscoveryService discoveryService) {
    return new HttpsRouterService(hostname, discoveryService);
  }

  @Override
  protected String getProtocol() {
    return "https";
  }

  @Override
  protected HttpURLConnection openURL(URL url) throws Exception {
    HttpsURLConnection urlConn = (HttpsURLConnection) url.openConnection();
    HttpRequests.disableCertCheck(urlConn);
    return urlConn;
  }

  @Override
  protected DefaultHttpClient getHTTPClient() throws Exception {
    SSLContext sslContext = SSLContext.getInstance("TLS");

    // set up a TrustManager that trusts everything
    sslContext.init(null, InsecureTrustManagerFactory.INSTANCE.getTrustManagers(), new SecureRandom());

    SSLSocketFactory sf = new SSLSocketFactory(sslContext, new AllowAllHostnameVerifier());
    Scheme httpsScheme = new Scheme("https", 10101, sf);
    SchemeRegistry schemeRegistry = new SchemeRegistry();
    schemeRegistry.register(httpsScheme);

    // apache HttpClient version >4.2 should use BasicClientConnectionManager
    ClientConnectionManager cm = new BasicClientConnectionManager(schemeRegistry);
    return new DefaultHttpClient(cm);
  }

  @Override
  protected SocketFactory getSocketFactory() throws Exception {
    SSLContext sc = SSLContext.getInstance("TLS");
    sc.init(null, InsecureTrustManagerFactory.INSTANCE.getTrustManagers(), new SecureRandom());
    return sc.getSocketFactory();
  }

  private static class HttpsRouterService extends RouterService {
    private final String hostname;
    private final DiscoveryService discoveryService;

    private NettyRouter router;

    private HttpsRouterService(String hostname, DiscoveryService discoveryService) {
      this.hostname = hostname;
      this.discoveryService = discoveryService;
    }

    @Override
    protected void startUp() {
      CConfiguration cConf = CConfiguration.create();
      SConfiguration sConf = SConfiguration.create();
      cConf.setBoolean(Constants.Security.SSL.EXTERNAL_ENABLED, true);

      URL certUrl = getClass().getClassLoader().getResource("cert.jks");
      Assert.assertNotNull(certUrl);

      Injector injector = Guice.createInjector(new SecurityModules().getInMemoryModules(),
                                               new InMemoryDiscoveryModule(),
                                               new AppFabricTestModule(cConf));
      DiscoveryServiceClient discoveryServiceClient = injector.getInstance(DiscoveryServiceClient.class);
      AccessTokenTransformer accessTokenTransformer = injector.getInstance(AccessTokenTransformer.class);
      cConf.set(Constants.Router.ADDRESS, hostname);
      cConf.setInt(Constants.Router.ROUTER_PORT, 0);
      cConf.setInt(Constants.Router.CONNECTION_TIMEOUT_SECS, CONNECTION_IDLE_TIMEOUT_SECS);

      sConf.set(Constants.Security.Router.SSL_KEYSTORE_PATH, certUrl.getPath());

      router =
        new NettyRouter(cConf, sConf, InetAddresses.forString(hostname),
                        new RouterServiceLookup(cConf, (DiscoveryServiceClient) discoveryService,
                                                new RouterPathLookup()),
                        new SuccessTokenValidator(), accessTokenTransformer, discoveryServiceClient);
      router.startAndWait();
    }

    @Override
    protected void shutDown() {
      router.stopAndWait();
    }

    public InetSocketAddress getRouterAddress() {
      return router.getBoundAddress().orElseThrow(IllegalStateException::new);
    }
  }
}
