/*
 * Copyright © 2019 Cask Data, Inc.
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

package co.cask.cdap.master.environment.k8s;

import co.cask.cdap.app.guice.AppFabricServiceRuntimeModule;
import co.cask.cdap.app.guice.AuthorizationModule;
import co.cask.cdap.app.guice.MonitorHandlerModule;
import co.cask.cdap.app.guice.ProgramRunnerRuntimeModule;
import co.cask.cdap.app.store.ServiceStore;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.guice.DFSLocationModule;
import co.cask.cdap.common.logging.LoggingContext;
import co.cask.cdap.common.logging.ServiceLoggingContext;
import co.cask.cdap.common.service.RetryOnStartFailureService;
import co.cask.cdap.common.service.RetryStrategies;
import co.cask.cdap.data.runtime.DataSetServiceModules;
import co.cask.cdap.data.runtime.DataSetsModules;
import co.cask.cdap.data2.audit.AuditModule;
import co.cask.cdap.data2.datafabric.dataset.service.DatasetService;
import co.cask.cdap.data2.datafabric.dataset.service.executor.DatasetOpExecutorService;
import co.cask.cdap.data2.metadata.writer.MessagingMetadataPublisher;
import co.cask.cdap.data2.metadata.writer.MetadataPublisher;
import co.cask.cdap.explore.guice.ExploreClientModule;
import co.cask.cdap.internal.app.namespace.LocalStorageProviderNamespaceAdmin;
import co.cask.cdap.internal.app.namespace.StorageProviderNamespaceAdmin;
import co.cask.cdap.internal.app.services.AppFabricServer;
import co.cask.cdap.master.spi.environment.MasterEnvironment;
import co.cask.cdap.master.spi.environment.MasterEnvironmentContext;
import co.cask.cdap.messaging.guice.MessagingClientModule;
import co.cask.cdap.metrics.guice.MetricsStoreModule;
import co.cask.cdap.operations.OperationalStatsService;
import co.cask.cdap.operations.guice.OperationalStatsModule;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.security.authorization.AuthorizationEnforcementModule;
import co.cask.cdap.security.authorization.AuthorizerInstantiator;
import co.cask.cdap.security.guice.SecureStoreServerModule;
import co.cask.cdap.security.store.SecureStoreService;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.common.util.concurrent.Service;
import com.google.inject.AbstractModule;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.Scopes;
import com.google.inject.util.Modules;
import org.apache.twill.api.TwillRunner;
import org.apache.twill.api.TwillRunnerService;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * The main class to run app-fabric and other supporting services.
 */
public class AppFabricServiceMain extends AbstractServiceMain<EnvironmentOptions> {

  /**
   * Main entry point
   */
  public static void main(String[] args) throws Exception {
    main(AppFabricServiceMain.class, args);
  }

  @Override
  protected List<Module> getServiceModules(MasterEnvironment masterEnv, EnvironmentOptions options) {
    return Arrays.asList(
      // Always use local table implementations, which use LevelDB.
      // In K8s, there won't be HBase and the cdap-site should be set to use SQL store for StructuredTable.
      new DataSetServiceModules().getStandaloneModules(),
      // The Dataset set modules are only needed to satisfy dependency injection
      new DataSetsModules().getStandaloneModules(),
      new MetricsStoreModule(),
      new MessagingClientModule(),
      new ExploreClientModule(),
      new AuditModule(),
      new AuthorizationModule(),
      new AuthorizationEnforcementModule().getMasterModule(),
      Modules.override(new AppFabricServiceRuntimeModule().getDistributedModules()).with(new AbstractModule() {
        @Override
        protected void configure() {
          bind(StorageProviderNamespaceAdmin.class).to(LocalStorageProviderNamespaceAdmin.class);
        }
      }),
      new ProgramRunnerRuntimeModule().getDistributedModules(),
      new MonitorHandlerModule(false),
      new SecureStoreServerModule(),
      new OperationalStatsModule(),
      getDataFabricModule(),
      new DFSLocationModule(),
      new AbstractModule() {
        @Override
        protected void configure() {
          bind(TwillRunnerService.class).toProvider(
            new SupplierProviderBridge<>(masterEnv.getTwillRunnerSupplier())).in(Scopes.SINGLETON);
          bind(TwillRunner.class).to(TwillRunnerService.class);

          // TODO (CDAP-14677): find a better way to inject metadata publisher
          bind(MetadataPublisher.class).to(MessagingMetadataPublisher.class);
        }
      }
    );
  }

  @Override
  protected void addServices(Injector injector, List<? super Service> services,
                             List<? super AutoCloseable> closeableResources,
                             MasterEnvironment masterEnv, MasterEnvironmentContext masterEnvContext,
                             EnvironmentOptions options) {
    closeableResources.add(injector.getInstance(AuthorizerInstantiator.class));
    services.add(injector.getInstance(OperationalStatsService.class));
    services.add(injector.getInstance(SecureStoreService.class));
    services.add(injector.getInstance(DatasetOpExecutorService.class));
    services.add(injector.getInstance(ServiceStore.class));

    // Start both the remote TwillRunnerService and regular TwillRunnerService
    TwillRunnerService remoteTwillRunner = injector.getInstance(Key.get(TwillRunnerService.class,
                                                                        Constants.AppFabric.RemoteExecution.class));
    services.add(new TwillRunnerServiceWrapper(remoteTwillRunner));
    services.add(new TwillRunnerServiceWrapper(injector.getInstance(TwillRunnerService.class)));
    services.add(new RetryOnStartFailureService(() -> injector.getInstance(DatasetService.class),
                                                RetryStrategies.exponentialDelay(200, 5000, TimeUnit.MILLISECONDS)));
    services.add(injector.getInstance(AppFabricServer.class));

    // Optionally adds the master environment task
    masterEnv.getTask().ifPresent(task -> services.add(new MasterTaskExecutorService(task, masterEnvContext)));
  }

  @Nullable
  @Override
  protected LoggingContext getLoggingContext(EnvironmentOptions options) {
    return new ServiceLoggingContext(NamespaceId.SYSTEM.getNamespace(),
                                     Constants.Logging.COMPONENT_NAME,
                                     Constants.Service.APP_FABRIC_HTTP);
  }

  /**
   * A Guava {@link Service} that wraps the {@link TwillRunnerService#start()} and {@link TwillRunnerService#stop()}
   * calls.
   */
  private static final class TwillRunnerServiceWrapper extends AbstractIdleService {

    private final TwillRunnerService twillRunner;

    private TwillRunnerServiceWrapper(TwillRunnerService twillRunner) {
      this.twillRunner = twillRunner;
    }

    @Override
    protected void startUp() {
      twillRunner.start();
    }

    @Override
    protected void shutDown() {
      twillRunner.stop();
    }
  }
}
