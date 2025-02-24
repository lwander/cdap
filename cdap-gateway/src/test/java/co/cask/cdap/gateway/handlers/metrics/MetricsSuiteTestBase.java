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
package co.cask.cdap.gateway.handlers.metrics;

import co.cask.cdap.api.metrics.MetricStore;
import co.cask.cdap.api.metrics.MetricsCollectionService;
import co.cask.cdap.app.metrics.MapReduceMetrics;
import co.cask.cdap.app.store.Store;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.discovery.EndpointStrategy;
import co.cask.cdap.common.discovery.RandomEndpointStrategy;
import co.cask.cdap.common.guice.ConfigModule;
import co.cask.cdap.common.guice.InMemoryDiscoveryModule;
import co.cask.cdap.common.guice.NamespaceAdminTestModule;
import co.cask.cdap.common.guice.NonCustomLocationUnitTestModule;
import co.cask.cdap.data.runtime.DataFabricModules;
import co.cask.cdap.data.runtime.DataSetServiceModules;
import co.cask.cdap.data.runtime.DataSetsModules;
import co.cask.cdap.data2.datafabric.dataset.service.DatasetService;
import co.cask.cdap.data2.datafabric.dataset.service.executor.DatasetOpExecutorService;
import co.cask.cdap.data2.metadata.writer.MetadataPublisher;
import co.cask.cdap.data2.metadata.writer.NoOpMetadataPublisher;
import co.cask.cdap.explore.guice.ExploreClientModule;
import co.cask.cdap.internal.app.store.DefaultStore;
import co.cask.cdap.metrics.guice.MetricsClientRuntimeModule;
import co.cask.cdap.metrics.guice.MetricsHandlerModule;
import co.cask.cdap.metrics.query.MetricsQueryService;
import co.cask.cdap.security.auth.context.AuthenticationContextModules;
import co.cask.cdap.security.authorization.AuthorizationEnforcementModule;
import co.cask.cdap.security.authorization.AuthorizationTestModule;
import co.cask.cdap.security.impersonation.NoOpOwnerAdmin;
import co.cask.cdap.security.impersonation.OwnerAdmin;
import co.cask.cdap.security.impersonation.UGIProvider;
import co.cask.cdap.security.impersonation.UnsupportedUGIProvider;
import co.cask.cdap.spi.data.StructuredTableAdmin;
import co.cask.cdap.spi.data.table.StructuredTableRegistry;
import co.cask.cdap.store.StoreDefinition;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ObjectArrays;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.util.Modules;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicHeader;
import org.apache.tephra.TransactionManager;
import org.apache.twill.discovery.Discoverable;
import org.apache.twill.discovery.DiscoveryServiceClient;
import org.apache.twill.filesystem.LocationFactory;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.rules.TemporaryFolder;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Provides base test class for MetricsServiceTestsSuite.
 */
public abstract class MetricsSuiteTestBase {

  @ClassRule
  public static final TemporaryFolder TEMP_FOLDER = new TemporaryFolder();

  // Control for test suite for whether to run BeforeClass/AfterClass
  public static int nestedStartCount;

  private static final String API_KEY = "SampleTestApiKey";
  private static final String CLUSTER = "SampleTestClusterName";
  private static final Header AUTH_HEADER = new BasicHeader(Constants.Gateway.API_KEY, API_KEY);

  private static MetricsQueryService metrics;
  private static final String hostname = "127.0.0.1";
  private static int port;

  protected static MetricsCollectionService collectionService;
  protected static Store store;
  protected static LocationFactory locationFactory;
  private static CConfiguration conf;

  private static TransactionManager transactionManager;
  private static DatasetOpExecutorService dsOpService;
  private static DatasetService datasetService;

  protected static MetricStore metricStore;

  @BeforeClass
  public static void beforeClass() throws Exception {
    if (nestedStartCount++ > 0) {
      return;
    }
    conf = CConfiguration.create();
    conf.set(Constants.CFG_LOCAL_DATA_DIR, TEMP_FOLDER.newFolder().getAbsolutePath());
    conf.set(Constants.Metrics.ADDRESS, hostname);
    conf.setBoolean(Constants.Dangerous.UNRECOVERABLE_RESET, true);
    conf.setBoolean(Constants.Metrics.CONFIG_AUTHENTICATION_REQUIRED, true);
    conf.set(Constants.Metrics.CLUSTER_NAME, CLUSTER);

    Injector injector = startMetricsService(conf);
    store = injector.getInstance(Store.class);
    locationFactory = injector.getInstance(LocationFactory.class);
    metricStore = injector.getInstance(MetricStore.class);
  }

  @AfterClass
  public static void afterClass() {
    if (--nestedStartCount != 0) {
      return;
    }
    stopMetricsService(conf);
  }

  @After
  public void after() {
    metricStore.deleteAll();
  }

  public static Injector startMetricsService(CConfiguration conf) throws Exception {
    Injector injector = Guice.createInjector(Modules.override(
      new ConfigModule(conf),
      new NonCustomLocationUnitTestModule(),
      new InMemoryDiscoveryModule(),
      new MetricsHandlerModule(),
      new MetricsClientRuntimeModule().getInMemoryModules(),
      new DataFabricModules().getInMemoryModules(),
      new DataSetsModules().getStandaloneModules(),
      new DataSetServiceModules().getInMemoryModules(),
      new ExploreClientModule(),
      new NamespaceAdminTestModule(),
      new AuthorizationTestModule(),
      new AuthorizationEnforcementModule().getInMemoryModules(),
      new AuthenticationContextModules().getMasterModule()
    ).with(new AbstractModule() {
      @Override
      protected void configure() {
        bind(Store.class).to(DefaultStore.class);
        bind(UGIProvider.class).to(UnsupportedUGIProvider.class);
        bind(OwnerAdmin.class).to(NoOpOwnerAdmin.class);
        // TODO (CDAP-14677): find a better way to inject metadata publisher
        bind(MetadataPublisher.class).to(NoOpMetadataPublisher.class);
      }
    }));

    transactionManager = injector.getInstance(TransactionManager.class);
    transactionManager.startAndWait();
    StructuredTableRegistry structuredTableRegistry = injector.getInstance(StructuredTableRegistry.class);
    structuredTableRegistry.initialize();
    StoreDefinition.createAllTables(injector.getInstance(StructuredTableAdmin.class), structuredTableRegistry);

    dsOpService = injector.getInstance(DatasetOpExecutorService.class);
    dsOpService.startAndWait();

    datasetService = injector.getInstance(DatasetService.class);
    datasetService.startAndWait();

    metrics = injector.getInstance(MetricsQueryService.class);
    metrics.startAndWait();

    collectionService = injector.getInstance(MetricsCollectionService.class);
    collectionService.startAndWait();

    // initialize the dataset instantiator
    DiscoveryServiceClient discoveryClient = injector.getInstance(DiscoveryServiceClient.class);

    EndpointStrategy metricsEndPoints = new RandomEndpointStrategy(
      () -> discoveryClient.discover(Constants.Service.METRICS));

    Discoverable discoverable = metricsEndPoints.pick(1L, TimeUnit.SECONDS);
    Assert.assertNotNull("Could not discover metrics service", discoverable);
    port = discoverable.getSocketAddress().getPort();

    return injector;
  }

  public static void stopMetricsService(CConfiguration conf) {
    collectionService.stopAndWait();
    datasetService.stopAndWait();
    dsOpService.stopAndWait();
    transactionManager.stopAndWait();
    metrics.stopAndWait();
    conf.clear();
  }

  public static URI getEndPoint(String path) throws URISyntaxException {
    return new URI("http://" + hostname + ":" + port + path);
  }

  public static HttpResponse doGet(String resource) throws Exception {
    return doGet(resource, null);
  }

  public static HttpResponse doGet(String resource, Header[] headers) throws Exception {
    DefaultHttpClient client = new DefaultHttpClient();
    HttpGet get = new HttpGet(MetricsSuiteTestBase.getEndPoint(resource));
    if (headers != null) {
      get.setHeaders(ObjectArrays.concat(AUTH_HEADER, headers));
    } else {
      get.setHeader(AUTH_HEADER);
    }
    return client.execute(get);
  }

  public static HttpResponse doPost(String resource, String body) throws Exception {
    return doPost(resource, body, null);
  }

  public static HttpResponse doPost(String resource, String body, Header[] headers) throws Exception {
    DefaultHttpClient client = new DefaultHttpClient();
    HttpPost post = new HttpPost(MetricsSuiteTestBase.getEndPoint(resource));
    if (body != null) {
      post.setEntity(new StringEntity(body));
    }

    if (headers != null) {
      post.setHeaders(ObjectArrays.concat(AUTH_HEADER, headers));
    } else {
      post.setHeader(AUTH_HEADER);
    }
    return client.execute(post);
  }

  protected static Map<String, String> getServiceContext(String namespaceId, String appName, String serviceName,
                                                         String runId, String handlerName) {
    return ImmutableMap.<String, String>builder()
      .put(Constants.Metrics.Tag.NAMESPACE, namespaceId)
      .put(Constants.Metrics.Tag.APP, appName)
      .put(Constants.Metrics.Tag.SERVICE, serviceName)
      .put(Constants.Metrics.Tag.RUN_ID, runId)
      .put(Constants.Metrics.Tag.HANDLER, handlerName).build();
  }

  protected static Map<String, String> getMapReduceTaskContext(String namespaceId, String appName, String jobName,
                                                               MapReduceMetrics.TaskType type,
                                                               String runId, String instanceId) {
    return ImmutableMap.<String, String>builder()
      .put(Constants.Metrics.Tag.NAMESPACE, namespaceId)
      .put(Constants.Metrics.Tag.APP, appName)
      .put(Constants.Metrics.Tag.MAPREDUCE, jobName)
      .put(Constants.Metrics.Tag.MR_TASK_TYPE, type.getId())
      .put(Constants.Metrics.Tag.RUN_ID, runId)
      .put(Constants.Metrics.Tag.INSTANCE_ID, instanceId)
      .build();
  }

  protected static Map<String, String> getWorkerContext(String namespaceId, String appName, String jobName,
                                                        String runId, String instanceId) {
    return ImmutableMap.<String, String>builder()
      .put(Constants.Metrics.Tag.NAMESPACE, namespaceId)
      .put(Constants.Metrics.Tag.APP, appName)
      .put(Constants.Metrics.Tag.WORKER, jobName)
      .put(Constants.Metrics.Tag.RUN_ID, runId)
      .put(Constants.Metrics.Tag.INSTANCE_ID, instanceId)
      .build();
  }
}
