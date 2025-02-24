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

package co.cask.cdap.app.preview;

import co.cask.cdap.api.metrics.MetricsCollectionService;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.discovery.ResolvingDiscoverable;
import co.cask.cdap.common.http.CommonNettyHttpServiceBuilder;
import co.cask.cdap.common.logging.LoggingContextAccessor;
import co.cask.cdap.common.logging.ServiceLoggingContext;
import co.cask.cdap.common.metrics.MetricsReporterHook;
import co.cask.cdap.gateway.handlers.preview.PreviewHttpHandler;
import co.cask.cdap.internal.app.services.AppFabricServer;
import co.cask.cdap.proto.id.NamespaceId;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.inject.Inject;
import io.cdap.http.NettyHttpService;
import org.apache.twill.common.Cancellable;
import org.apache.twill.discovery.Discoverable;
import org.apache.twill.discovery.DiscoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;

/**
 * HTTP Server for preview.
 */
public class PreviewHttpServer extends AbstractIdleService {

  private static final Logger LOG = LoggerFactory.getLogger(AppFabricServer.class);

  private final DiscoveryService discoveryService;
  private final NettyHttpService httpService;
  private Cancellable cancelHttpService;

  @Inject
  PreviewHttpServer(CConfiguration cConf, DiscoveryService discoveryService, PreviewHttpHandler previewHttpHandler,
                    MetricsCollectionService metricsCollectionService) {
    this.discoveryService = discoveryService;
    this.httpService = new CommonNettyHttpServiceBuilder(cConf, Constants.Service.PREVIEW_HTTP)
      .setHost(cConf.get(Constants.Preview.ADDRESS))
      .setPort(cConf.getInt(Constants.Preview.PORT))
      .setHttpHandlers(previewHttpHandler)
      .setConnectionBacklog(cConf.getInt(Constants.Preview.BACKLOG_CONNECTIONS))
      .setExecThreadPoolSize(cConf.getInt(Constants.Preview.EXEC_THREADS))
      .setBossThreadPoolSize(cConf.getInt(Constants.Preview.BOSS_THREADS))
      .setWorkerThreadPoolSize(cConf.getInt(Constants.Preview.WORKER_THREADS))
      .setHandlerHooks(Collections.singletonList(
        new MetricsReporterHook(metricsCollectionService, Constants.Service.PREVIEW_HTTP)))
      .build();
  }

  /**
   * Configures the AppFabricService pre-start.
   */
  @Override
  protected void startUp() throws Exception {
    LoggingContextAccessor.setLoggingContext(new ServiceLoggingContext(NamespaceId.SYSTEM.getNamespace(),
                                                                       Constants.Logging.COMPONENT_NAME,
                                                                       Constants.Service.PREVIEW_HTTP));

    httpService.start();
    cancelHttpService = discoveryService.register(
      ResolvingDiscoverable.of(new Discoverable(Constants.Service.PREVIEW_HTTP, httpService.getBindAddress())));
    LOG.info("Preview HTTP server started on {}", httpService.getBindAddress());
  }

  @Override
  protected void shutDown() throws Exception {
    try {
      cancelHttpService.cancel();
    } finally {
      httpService.stop();
    }
    LOG.info("Preview HTTP server stopped");
  }
}
