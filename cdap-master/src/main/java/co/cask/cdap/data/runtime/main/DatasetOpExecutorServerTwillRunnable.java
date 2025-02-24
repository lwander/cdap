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

package co.cask.cdap.data.runtime.main;

import co.cask.cdap.app.guice.EntityVerifierModule;
import co.cask.cdap.app.store.Store;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.guice.ConfigModule;
import co.cask.cdap.common.guice.DFSLocationModule;
import co.cask.cdap.common.guice.IOModule;
import co.cask.cdap.common.guice.KafkaClientModule;
import co.cask.cdap.common.guice.ZKClientModule;
import co.cask.cdap.common.guice.ZKDiscoveryModule;
import co.cask.cdap.common.logging.LoggingContextAccessor;
import co.cask.cdap.common.logging.ServiceLoggingContext;
import co.cask.cdap.common.namespace.guice.NamespaceQueryAdminModule;
import co.cask.cdap.common.twill.AbstractMasterTwillRunnable;
import co.cask.cdap.data.runtime.DataFabricModules;
import co.cask.cdap.data.runtime.DataSetServiceModules;
import co.cask.cdap.data.runtime.DataSetsModules;
import co.cask.cdap.data2.audit.AuditModule;
import co.cask.cdap.data2.datafabric.dataset.service.executor.DatasetOpExecutorService;
import co.cask.cdap.data2.metadata.writer.MessagingMetadataPublisher;
import co.cask.cdap.data2.metadata.writer.MetadataPublisher;
import co.cask.cdap.explore.guice.ExploreClientModule;
import co.cask.cdap.internal.app.store.DefaultStore;
import co.cask.cdap.logging.appender.LogAppenderInitializer;
import co.cask.cdap.logging.guice.KafkaLogAppenderModule;
import co.cask.cdap.messaging.guice.MessagingClientModule;
import co.cask.cdap.metadata.MetadataService;
import co.cask.cdap.metadata.MetadataServiceModule;
import co.cask.cdap.metadata.MetadataSubscriberService;
import co.cask.cdap.metrics.guice.MetricsClientRuntimeModule;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.security.auth.context.AuthenticationContextModules;
import co.cask.cdap.security.authorization.AuthorizationEnforcementModule;
import co.cask.cdap.security.authorization.RemotePrivilegesManager;
import co.cask.cdap.security.guice.SecureStoreClientModule;
import co.cask.cdap.security.impersonation.DefaultOwnerAdmin;
import co.cask.cdap.security.impersonation.OwnerAdmin;
import co.cask.cdap.security.impersonation.RemoteUGIProvider;
import co.cask.cdap.security.impersonation.UGIProvider;
import co.cask.cdap.security.spi.authorization.PrivilegesManager;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.Service;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Scopes;
import org.apache.hadoop.conf.Configuration;
import org.apache.twill.api.TwillContext;

import java.util.List;

/**
 * Executes user code on behalf of a particular user inside a YARN container, for security.
 */
public class DatasetOpExecutorServerTwillRunnable extends AbstractMasterTwillRunnable {

  private Injector injector;

  public DatasetOpExecutorServerTwillRunnable(String name, String cConfName, String hConfName) {
    super(name, cConfName, hConfName);
  }

  @Override
  protected Injector doInit(TwillContext context) {
    CConfiguration cConf = getCConfiguration();
    Configuration hConf = getConfiguration();

    // Set the host name to the one provided by Twill
    cConf.set(Constants.Dataset.Executor.ADDRESS, context.getHost().getHostName());
    cConf.set(Constants.Metadata.SERVICE_BIND_ADDRESS, context.getHost().getHostName());

    String txClientId = String.format("cdap.service.%s.%d", Constants.Service.DATASET_EXECUTOR,
                                      context.getInstanceId());
    injector = createInjector(cConf, hConf, txClientId);

    injector.getInstance(LogAppenderInitializer.class).initialize();
    LoggingContextAccessor.setLoggingContext(new ServiceLoggingContext(NamespaceId.SYSTEM.getNamespace(),
                                                                       Constants.Logging.COMPONENT_NAME,
                                                                       Constants.Service.DATASET_EXECUTOR));
    return injector;
  }

  @VisibleForTesting
  static Injector createInjector(CConfiguration cConf, Configuration hConf, String txClientId) {
    return Guice.createInjector(
      new ConfigModule(cConf, hConf),
      new IOModule(),
      new ZKClientModule(),
      new ZKDiscoveryModule(),
      new KafkaClientModule(),
      new MessagingClientModule(),
      new MetricsClientRuntimeModule().getDistributedModules(),
      new DFSLocationModule(),
      new NamespaceQueryAdminModule(),
      new DataFabricModules(txClientId).getDistributedModules(),
      new DataSetsModules().getDistributedModules(),
      new DataSetServiceModules().getDistributedModules(),
      new KafkaLogAppenderModule(),
      new ExploreClientModule(),
      new MetadataServiceModule(),
      new AuditModule(),
      new EntityVerifierModule(),
      new SecureStoreClientModule(),
      new AuthorizationEnforcementModule().getDistributedModules(),
      new AuthenticationContextModules().getMasterModule(),
      new AbstractModule() {
        @Override
        protected void configure() {
          bind(Store.class).to(DefaultStore.class);
          bind(UGIProvider.class).to(RemoteUGIProvider.class).in(Scopes.SINGLETON);
          bind(PrivilegesManager.class).to(RemotePrivilegesManager.class);
          bind(OwnerAdmin.class).to(DefaultOwnerAdmin.class);
          // TODO (CDAP-14677): find a better way to inject metadata publisher
          bind(MetadataPublisher.class).to(MessagingMetadataPublisher.class);
        }
      });
  }

  @Override
  protected void addServices(List<? super Service> services) {
    services.add(injector.getInstance(DatasetOpExecutorService.class));
    services.add(injector.getInstance(MetadataService.class));
    services.add(injector.getInstance(MetadataSubscriberService.class));
  }
}
