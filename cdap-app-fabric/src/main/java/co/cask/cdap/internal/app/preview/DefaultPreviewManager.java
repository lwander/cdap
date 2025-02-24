/*
 * Copyright © 2016-2019 Cask Data, Inc.
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

package co.cask.cdap.internal.app.preview;

import co.cask.cdap.api.security.store.SecureStore;
import co.cask.cdap.app.guice.AppFabricServiceRuntimeModule;
import co.cask.cdap.app.guice.ProgramRunnerRuntimeModule;
import co.cask.cdap.app.preview.PreviewManager;
import co.cask.cdap.app.preview.PreviewRequest;
import co.cask.cdap.app.preview.PreviewRunner;
import co.cask.cdap.app.preview.PreviewRunnerModule;
import co.cask.cdap.common.BadRequestException;
import co.cask.cdap.common.NotFoundException;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.guice.ConfigModule;
import co.cask.cdap.common.guice.IOModule;
import co.cask.cdap.common.guice.LocalLocationModule;
import co.cask.cdap.common.guice.preview.PreviewDiscoveryRuntimeModule;
import co.cask.cdap.common.utils.DirUtils;
import co.cask.cdap.common.utils.Networks;
import co.cask.cdap.config.PreferencesService;
import co.cask.cdap.config.guice.ConfigStoreModule;
import co.cask.cdap.data.runtime.DataSetServiceModules;
import co.cask.cdap.data.runtime.DataSetsModules;
import co.cask.cdap.data.runtime.preview.PreviewDataModules;
import co.cask.cdap.data2.dataset2.DatasetFramework;
import co.cask.cdap.internal.app.runtime.artifact.ArtifactRepository;
import co.cask.cdap.internal.app.runtime.artifact.ArtifactStore;
import co.cask.cdap.internal.app.runtime.artifact.DefaultArtifactRepository;
import co.cask.cdap.internal.provision.ProvisionerModule;
import co.cask.cdap.logging.guice.LocalLogAppenderModule;
import co.cask.cdap.logging.read.FileLogReader;
import co.cask.cdap.logging.read.LogReader;
import co.cask.cdap.messaging.guice.MessagingServerRuntimeModule;
import co.cask.cdap.metadata.MetadataReaderWriterModules;
import co.cask.cdap.metrics.guice.MetricsClientRuntimeModule;
import co.cask.cdap.proto.ProgramType;
import co.cask.cdap.proto.artifact.AppRequest;
import co.cask.cdap.proto.id.ApplicationId;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.proto.id.ProgramId;
import co.cask.cdap.security.auth.context.AuthenticationContextModules;
import co.cask.cdap.security.authorization.AuthorizerInstantiator;
import co.cask.cdap.security.guice.preview.PreviewSecureStoreModule;
import co.cask.cdap.security.spi.authorization.AuthorizationEnforcer;
import co.cask.cdap.security.spi.authorization.PrivilegesManager;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.common.util.concurrent.Service;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.name.Named;
import com.google.inject.name.Names;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.apache.hadoop.mapreduce.MRConfig;
import org.apache.tephra.TransactionSystemClient;
import org.apache.twill.discovery.DiscoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.annotation.ParametersAreNonnullByDefault;

/**
 * Class responsible for creating the injector for preview and starting it.
 */
public class DefaultPreviewManager implements PreviewManager {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultPreviewManager.class);
  private static final String PREFIX = "preview-";

  private final CConfiguration cConf;
  private final Configuration hConf;
  private final DiscoveryService discoveryService;
  private final DatasetFramework datasetFramework;
  private final PreferencesService preferencesService;
  private final SecureStore secureStore;
  private final TransactionSystemClient transactionSystemClient;
  private final ArtifactRepository artifactRepository;
  private final ArtifactStore artifactStore;
  private final AuthorizerInstantiator authorizerInstantiator;
  private final PrivilegesManager privilegesManager;
  private final AuthorizationEnforcer authorizationEnforcer;
  private final Cache<ApplicationId, Injector> appInjectors;
  private final Path previewDataDir;

  @Inject
  DefaultPreviewManager(final CConfiguration cConf, Configuration hConf, DiscoveryService discoveryService,
                        @Named(DataSetsModules.BASE_DATASET_FRAMEWORK) DatasetFramework datasetFramework,
                        PreferencesService preferencesService, SecureStore secureStore,
                        TransactionSystemClient transactionSystemClient, ArtifactRepository artifactRepository,
                        ArtifactStore artifactStore, AuthorizerInstantiator authorizerInstantiator,
                        PrivilegesManager privilegesManager, AuthorizationEnforcer authorizationEnforcer) {
    this.cConf = cConf;
    this.hConf = hConf;
    this.datasetFramework = datasetFramework;
    this.discoveryService = discoveryService;
    this.preferencesService = preferencesService;
    this.secureStore = secureStore;
    this.transactionSystemClient = transactionSystemClient;
    this.artifactRepository = artifactRepository;
    this.artifactStore = artifactStore;
    this.authorizerInstantiator = authorizerInstantiator;
    this.privilegesManager = privilegesManager;
    this.authorizationEnforcer = authorizationEnforcer;
    this.previewDataDir = Paths.get(cConf.get(Constants.CFG_LOCAL_DATA_DIR), "preview").toAbsolutePath();

    this.appInjectors = CacheBuilder.newBuilder()
      .maximumSize(cConf.getInt(Constants.Preview.PREVIEW_CACHE_SIZE, 10))
      .removalListener(new RemovalListener<ApplicationId, Injector>() {
        @Override
        @ParametersAreNonnullByDefault
        public void onRemoval(RemovalNotification<ApplicationId, Injector> notification) {
          Injector injector = notification.getValue();
          if (injector != null) {
            PreviewRunner runner = injector.getInstance(PreviewRunner.class);
            if (runner instanceof Service) {
              stopQuietly((Service) runner);
            }
          }
          ApplicationId application = notification.getKey();
          if (application == null) {
            return;
          }
          removePreviewDir(application);
        }
      })
      .build();
  }

  @Override
  public ApplicationId start(NamespaceId namespace, AppRequest<?> appRequest) throws Exception {
    ApplicationId previewApp = namespace.app(PREFIX + System.currentTimeMillis());
    Injector injector = createPreviewInjector(previewApp);
    PreviewRunner runner = injector.getInstance(PreviewRunner.class);
    if (runner instanceof Service) {
      ((Service) runner).startAndWait();
    }
    try {
      runner.startPreview(new PreviewRequest<>(getProgramIdFromRequest(previewApp, appRequest), appRequest));
    } catch (Exception e) {
      if (runner instanceof Service) {
        stopQuietly((Service) runner);
      }
      removePreviewDir(previewApp);
      throw e;
    }
    appInjectors.put(previewApp, injector);
    return previewApp;
  }

  @Override
  public PreviewRunner getRunner(ApplicationId preview) throws NotFoundException {
    Injector injector = appInjectors.getIfPresent(preview);
    if (injector == null) {
      throw new NotFoundException(preview);
    }

    return injector.getInstance(PreviewRunner.class);
  }

  @Override
  public LogReader getLogReader(ApplicationId preview) throws NotFoundException {
    Injector injector = appInjectors.getIfPresent(preview);
    if (injector == null) {
      throw new NotFoundException(preview);
    }

    return injector.getInstance(LogReader.class);
  }

  /**
   * Create injector for the given application id.
   */
  @VisibleForTesting
  Injector createPreviewInjector(ApplicationId applicationId) throws IOException {
    CConfiguration previewCConf = CConfiguration.copy(cConf);
    Path previewDir = Files.createDirectories(previewDataDir.resolve(applicationId.getApplication()));

    previewCConf.set(Constants.CFG_LOCAL_DATA_DIR, previewDir.toString());
    previewCConf.setIfUnset(Constants.CFG_DATA_LEVELDB_DIR, previewDir.toString());
    previewCConf.setBoolean(Constants.Explore.EXPLORE_ENABLED, false);
    // Use No-SQL store for preview data
    previewCConf.set(Constants.Dataset.DATA_STORAGE_IMPLEMENTATION, Constants.Dataset.DATA_STORAGE_NOSQL);

    // Setup Hadoop configuration
    Configuration previewHConf = new Configuration(hConf);
    previewHConf.set(MRConfig.FRAMEWORK_NAME, MRConfig.LOCAL_FRAMEWORK_NAME);
    previewHConf.set(CommonConfigurationKeysPublic.FS_DEFAULT_NAME_KEY,
                     previewDir.resolve("fs").toUri().toString());

    return Guice.createInjector(
      new ConfigModule(previewCConf, previewHConf),
      new IOModule(),
      new AuthenticationContextModules().getMasterModule(),
      new PreviewSecureStoreModule(secureStore),
      new PreviewDiscoveryRuntimeModule(discoveryService),
      new LocalLocationModule(),
      new ConfigStoreModule(),
      new PreviewRunnerModule(artifactRepository, artifactStore, authorizerInstantiator, authorizationEnforcer,
                              privilegesManager, preferencesService),
      new ProgramRunnerRuntimeModule().getStandaloneModules(),
      new PreviewDataModules().getDataFabricModule(transactionSystemClient),
      new PreviewDataModules().getDataSetsModule(datasetFramework),
      new DataSetServiceModules().getStandaloneModules(),
      // Use the in-memory module for metrics collection, which metrics still get persisted to dataset, but
      // save threads for reading metrics from TMS, as there won't be metrics in TMS.
      new MetricsClientRuntimeModule().getInMemoryModules(),
      new LocalLogAppenderModule(),
      new MessagingServerRuntimeModule().getInMemoryModules(),
      new MetadataReaderWriterModules().getInMemoryModules(),
      new ProvisionerModule(),
      new AbstractModule() {
        @Override
        protected void configure() {
          bind(ArtifactRepository.class)
            .annotatedWith(Names.named(AppFabricServiceRuntimeModule.NOAUTH_ARTIFACT_REPO))
            .to(DefaultArtifactRepository.class)
            .in(Scopes.SINGLETON);
          bind(LogReader.class).to(FileLogReader.class).in(Scopes.SINGLETON);
        }

        @Provides
        @Named(Constants.Service.MASTER_SERVICES_BIND_ADDRESS)
        @SuppressWarnings("unused")
        public InetAddress providesHostname(CConfiguration cConf) {
          String address = cConf.get(Constants.Preview.ADDRESS);
          return Networks.resolve(address, new InetSocketAddress("localhost", 0).getAddress());
        }
      }
    );
  }

  private ProgramId getProgramIdFromRequest(ApplicationId preview, AppRequest request) throws BadRequestException {
    if (request.getPreview() == null) {
      throw new BadRequestException("Preview config cannot be null");
    }

    String programName = request.getPreview().getProgramName();
    ProgramType programType = request.getPreview().getProgramType();

    if (programName == null || programType == null) {
      throw new IllegalArgumentException("ProgramName or ProgramType cannot be null.");
    }

    return preview.program(programType, programName);
  }

  private void stopQuietly(Service service) {
    try {
      service.stopAndWait();
    } catch (Exception e) {
      LOG.debug("Error stopping the preview runner.", e);
    }
  }

  private void removePreviewDir(ApplicationId applicationId) {
    Path previewDirPath = previewDataDir.resolve(applicationId.getApplication());

    try {
      DataTracerFactoryProvider.removeDataTracerFactory(applicationId);
      DirUtils.deleteDirectoryContents(previewDirPath.toFile());
    } catch (IOException e) {
      LOG.debug("Error deleting the preview directory {}", previewDirPath, e);
    }
  }
}
