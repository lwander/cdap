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

package co.cask.cdap.internal.app.runtime.schedule;

import co.cask.cdap.common.ServiceUnavailableException;
import co.cask.cdap.common.service.RetryOnStartFailureService;
import co.cask.cdap.common.service.RetryStrategies;
import com.google.common.util.concurrent.AbstractService;
import com.google.common.util.concurrent.Service;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Scheduler service to run in Distributed CDAP. Waits for Dataset service to be available.
 */
public final class DistributedTimeSchedulerService extends AbstractTimeSchedulerService {

  private static final Logger LOG = LoggerFactory.getLogger(DistributedTimeSchedulerService.class);
  private final Service serviceDelegate;
  private final CountDownLatch startUpLatch;

  @Inject
  public DistributedTimeSchedulerService(TimeScheduler timeScheduler) {
    super(timeScheduler);
    this.startUpLatch = new CountDownLatch(1);
    this.serviceDelegate = new RetryOnStartFailureService(() -> new AbstractService() {
      @Override
      protected void doStart() {
        try {
          startSchedulers();
          notifyStarted();
          startUpLatch.countDown();
        } catch (ServiceUnavailableException e) {
          // This is expected during startup. Log with a debug without the stacktrace
          LOG.debug("Service not available for schedule to start due to {}", e.getMessage());
          notifyFailed(e);
        } catch (SchedulerException e) {
          LOG.warn("Scheduler Exception thrown ", e);
          notifyFailed(e);
        }
      }

      @Override
      protected void doStop() {
        try {
          stopScheduler();
          notifyStopped();
        } catch (SchedulerException e) {
          notifyFailed(e);
        }
      }
    }, RetryStrategies.exponentialDelay(200, 5000, TimeUnit.MILLISECONDS));
  }

  @Override
  protected void startUp() throws Exception {
    LOG.info("Starting scheduler.");
    // RetryOnStartFailureservice#startAndWait returns before its service's startAndWait completes
    serviceDelegate.startAndWait();
    startUpLatch.await();
  }

  @Override
  protected void shutDown() throws Exception {
    LOG.info("Stopping scheduler.");
    serviceDelegate.stopAndWait();
  }
}
