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

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import co.cask.cdap.api.logging.AppenderContext;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.guice.LocalLocationModule;
import co.cask.cdap.common.logging.LoggingContext;
import co.cask.cdap.common.logging.ServiceLoggingContext;
import co.cask.cdap.common.namespace.guice.NamespaceQueryAdminModule;
import co.cask.cdap.data.runtime.DataSetsModules;
import co.cask.cdap.data.runtime.SystemDatasetRuntimeModule;
import co.cask.cdap.gateway.handlers.CommonHandlers;
import co.cask.cdap.logging.appender.LogAppender;
import co.cask.cdap.logging.appender.LogMessage;
import co.cask.cdap.logging.framework.distributed.DistributedAppenderContext;
import co.cask.cdap.logging.guice.LogQueryServerModule;
import co.cask.cdap.logging.logbuffer.LogBufferService;
import co.cask.cdap.logging.read.FileLogReader;
import co.cask.cdap.logging.read.LogReader;
import co.cask.cdap.logging.service.LogQueryService;
import co.cask.cdap.logging.service.LogSaverStatusService;
import co.cask.cdap.master.spi.environment.MasterEnvironment;
import co.cask.cdap.master.spi.environment.MasterEnvironmentContext;
import co.cask.cdap.messaging.guice.MessagingClientModule;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.security.auth.context.AuthenticationContextModules;
import co.cask.cdap.security.authorization.AuthorizationEnforcementModule;
import co.cask.cdap.security.impersonation.CurrentUGIProvider;
import co.cask.cdap.security.impersonation.UGIProvider;
import com.google.common.util.concurrent.Service;
import com.google.inject.AbstractModule;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.PrivateModule;
import com.google.inject.Scopes;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.name.Names;
import io.cdap.http.HttpHandler;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Main class for running log saver and log query service in Kubernetes.
 */
public class LogsServiceMain extends AbstractServiceMain<EnvironmentOptions> {

  /**
   * Main entry point.
   */
  public static void main(String[] args) throws Exception {
    main(LogsServiceMain.class, args);
  }

  @Override
  protected List<Module> getServiceModules(MasterEnvironment masterEnv, EnvironmentOptions options) {
    return Arrays.asList(
      new NamespaceQueryAdminModule(),
      new AuthorizationEnforcementModule().getDistributedModules(),
      new AuthenticationContextModules().getMasterModule(),
      new MessagingClientModule(),
      getDataFabricModule(),
      // Always use local table implementations, which use LevelDB.
      // In K8s, there won't be HBase and the cdap-site should be set to use SQL store for StructuredTable.
      new SystemDatasetRuntimeModule().getStandaloneModules(),
      new DataSetsModules().getStandaloneModules(),
      new LocalLocationModule(),
      // log handler is co-located with log saver
      new LogQueryServerModule(),
      new PrivateModule() {
        @Override
        protected void configure() {
          // Current impersonation is not supported
          bind(UGIProvider.class).to(CurrentUGIProvider.class).in(Scopes.SINGLETON);
          bind(LogReader.class).to(FileLogReader.class);
          expose(LogReader.class);

          bind(Integer.class).annotatedWith(Names.named(Constants.LogSaver.LOG_SAVER_INSTANCE_ID))
            .toInstance(0);
          bind(Integer.class).annotatedWith(Names.named(Constants.LogSaver.LOG_SAVER_INSTANCE_COUNT))
            .toInstance(1);
          bind(AppenderContext.class).to(DistributedAppenderContext.class);

          bind(LogBufferService.class).in(Scopes.SINGLETON);
          expose(LogBufferService.class);

          // Bind the status service
          Multibinder<HttpHandler> handlerBinder = Multibinder.newSetBinder
            (binder(), HttpHandler.class, Names.named(Constants.LogSaver.LOG_SAVER_STATUS_HANDLER));
          CommonHandlers.add(handlerBinder);
          bind(LogSaverStatusService.class).in(Scopes.SINGLETON);
          expose(LogSaverStatusService.class);
        }
      }
    );
  }

  @Override
  protected void addServices(Injector injector, List<? super Service> services,
                             List<? super AutoCloseable> closeableResources, MasterEnvironment masterEnv,
                             MasterEnvironmentContext masterEnvContext, EnvironmentOptions options) {
    // log saver
    services.add(injector.getInstance(LogBufferService.class));
    // log handler
    services.add(injector.getInstance(LogQueryService.class));
  }

  @Nullable
  @Override
  protected LoggingContext getLoggingContext(EnvironmentOptions options) {
    return new ServiceLoggingContext(NamespaceId.SYSTEM.getNamespace(),
                                     Constants.Logging.COMPONENT_NAME,
                                     Constants.Service.LOGSAVER);
  }

  @Override
  protected Module getLogAppenderModule() {
    return new AbstractModule() {
      @Override
      protected void configure() {
        bind(LogAppender.class).to(SysOutLogAppender.class).in(Scopes.SINGLETON);
      }
    };
  }

  /**
   * A {@link LogAppender} that just log with the current log appender.
   */
  private static final class SysOutLogAppender extends LogAppender {

    @Override
    protected void appendEvent(LogMessage logMessage) {
      ILoggerFactory loggerFactory = LoggerFactory.getILoggerFactory();
      if (loggerFactory instanceof LoggerContext) {
        ch.qos.logback.classic.Logger logger = ((LoggerContext) loggerFactory).getLogger(logMessage.getLoggerName());
        Iterator<Appender<ILoggingEvent>> iterator = logger.iteratorForAppenders();
        while (iterator.hasNext()) {
          Appender<ILoggingEvent> appender = iterator.next();
          if (appender != this) {
            appender.doAppend(logMessage);
          }
        }
      } else {
        System.out.println(logMessage);
      }
    }
  }
}
