/*
 * Copyright © 2017 Cask Data, Inc.
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

package co.cask.cdap.logging.framework.local;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import co.cask.cdap.api.logging.AppenderContext;
import co.cask.cdap.api.metrics.MetricsCollectionService;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.logging.appender.LogAppender;
import co.cask.cdap.logging.appender.LogMessage;
import co.cask.cdap.logging.framework.LocalAppenderContext;
import co.cask.cdap.logging.framework.LogPipelineLoader;
import co.cask.cdap.logging.framework.LogPipelineSpecification;
import co.cask.cdap.logging.pipeline.LogProcessorPipelineContext;
import co.cask.cdap.spi.data.transaction.TransactionRunner;
import com.google.common.util.concurrent.AbstractExecutionThreadService;
import com.google.inject.Inject;
import org.apache.twill.filesystem.LocationFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The {@link LogAppender} used in local mode.
 */
public class LocalLogAppender extends LogAppender {

  private static final ILoggingEvent SHUTDOWN_EVENT = new LoggingEvent();
  // The event queue size has to be large enough to cater for logs emitted before the
  // log processing pipeline is fully functional (e.g. pending DatasetService availability).
  // Otherwise emitting new logs would be blocked.
  private static final int EVENT_QUEUE_SIZE = 65536;

  private final CConfiguration cConf;
  private final TransactionRunner transactionRunner;
  private final LocationFactory locationFactory;
  private final MetricsCollectionService metricsCollectionService;
  private final List<LocalLogProcessorPipeline> pipelines;
  private final AtomicBoolean started;
  private final AtomicBoolean stopped;
  private final Set<Thread> pipelineThreads;

  @Inject
  LocalLogAppender(CConfiguration cConf, TransactionRunner transactionRunner,
                   LocationFactory locationFactory, MetricsCollectionService metricsCollectionService) {
    this.cConf = cConf;
    this.transactionRunner = transactionRunner;
    this.locationFactory = locationFactory;
    this.metricsCollectionService = metricsCollectionService;
    this.pipelines = new ArrayList<>();
    this.started = new AtomicBoolean();
    this.stopped = new AtomicBoolean();
    this.pipelineThreads = Collections.newSetFromMap(new IdentityHashMap<>());
    setName(getClass().getName());
  }

  @Override
  public void start() {
    if (!started.compareAndSet(false, true)) {
      return;
    }

    // Load and starts all configured log processing pipelines
    LogPipelineLoader pipelineLoader = new LogPipelineLoader(cConf);
    Map<String, LogPipelineSpecification<AppenderContext>> specs =
      pipelineLoader.load(() -> new LocalAppenderContext(transactionRunner, locationFactory, metricsCollectionService));

    // Use the event delay as the sync interval
    long syncIntervalMillis = cConf.getLong(Constants.Logging.PIPELINE_EVENT_DELAY_MS);

    for (LogPipelineSpecification<AppenderContext> spec : specs.values()) {
      LogProcessorPipelineContext context =
        new LogProcessorPipelineContext(cConf, spec.getName(), spec.getContext(),
                                        spec.getContext().getMetricsContext(), spec.getContext().getInstanceId());
      LocalLogProcessorPipeline pipeline = new LocalLogProcessorPipeline(context, syncIntervalMillis);
      pipeline.startAndWait();
      pipelineThreads.add(pipeline.getAppenderThread());
      pipelines.add(pipeline);
    }

    super.start();
  }

  @Override
  public void stop() {
    if (!stopped.compareAndSet(false, true)) {
      return;
    }
    // Stop all pipelines
    super.stop();
    for (LocalLogProcessorPipeline pipeline : pipelines) {
      try {
        pipeline.stopAndWait();
      } catch (Throwable t) {
        addError("Exception raised when stopping log processing pipeline " + pipeline.getName(), t);
      }
    }
  }

  @Override
  public void doAppend(ILoggingEvent eventObject) {
    // Ignore logs coming from the log process pipeline, otherwise it'll become an infinite loop of logs.
    // This won't guard against the case that an appender starts a new thread and emit log per
    // event (something like what this class does). If that's the case, the appender itself need to guard against
    // it, similar to what's being done in here.
    // They are still logged via other log appender (e.g. log to cdap.log), but just not being collected
    // via the log collection system.
    if (!pipelineThreads.contains(Thread.currentThread())) {
      super.doAppend(eventObject);
    }
  }

  @Override
  protected void appendEvent(LogMessage logMessage) {
    logMessage.prepareForDeferredProcessing();
    logMessage.getCallerData();

    for (LocalLogProcessorPipeline pipeline : pipelines) {
      pipeline.append(logMessage);
    }
  }

  /**
   * The log processing pipeline for writing logs to configured logger context
   */
  private final class LocalLogProcessorPipeline extends AbstractExecutionThreadService {

    private final LogProcessorPipelineContext context;
    private final long syncIntervalMillis;
    private final BlockingQueue<ILoggingEvent> eventQueue;
    private long lastSyncTime;
    private Thread appenderThread;

    private LocalLogProcessorPipeline(LogProcessorPipelineContext context, long syncIntervalMillis) {
      this.context = context;
      this.syncIntervalMillis = syncIntervalMillis;
      this.eventQueue = new ArrayBlockingQueue<>(EVENT_QUEUE_SIZE);
    }

    /**
     * Returns name of the pipeline.
     */
    String getName() {
      return context.getName();
    }

    Thread getAppenderThread() {
      return appenderThread;
    }

    @Override
    protected Executor executor() {
      // Copy from parent, but using a different thread name
      // Can't override the getServiceName() method as it is missing from some Guava version.
      return command -> new Thread(command, "LocalLogProcessor-" + getName()).start();
    }

    @Override
    protected void startUp() throws Exception {
      addInfo("Starting log processing pipeline " + getName());
      context.start();
      addInfo("Log processing pipeline " + getName() + " started");
      appenderThread = Thread.currentThread();
    }

    @Override
    protected void shutDown() throws Exception {
      addInfo("Stopping log processing pipeline " + getName());
      // Write all pending events out
      for (ILoggingEvent event : eventQueue) {
        if (event == SHUTDOWN_EVENT) {
          continue;
        }
        context.getEffectiveLogger(event.getLoggerName()).callAppenders(event);
      }
      context.stop();
      addInfo("Log processing pipeline " + getName() + " stopped");
    }

    @Override
    protected void triggerShutdown() {
      eventQueue.offer(SHUTDOWN_EVENT);
    }

    @Override
    protected void run() {
      try {
        ILoggingEvent event = eventQueue.take();

        while (isRunning()) {
          callAppenders(event);
          long pollTimeout = syncIntervalMillis - (event.getTimeStamp() - lastSyncTime);

          // If it is not time to sync yet, poll for next event until the next sync time.
          // Otherwise just assign `null` to event to trigger sync immediately
          event = (pollTimeout > 0) ? eventQueue.poll(pollTimeout, TimeUnit.MILLISECONDS) : null;
          if (event == null) {
            sync(System.currentTimeMillis());
            // After sync'ed everything, block until there is more event
            event = eventQueue.take();
          }
        }

        // Pipeline stopped in between the event was dequeue and before callAppenders.
        // We need to append this event before returning.
        callAppenders(event);
      } catch (InterruptedException e) {
        // Just ignore it. Not resetting the interrupt flag so that shutdown can operate without interruption.
      }
    }

    /**
     * Appends the given {@link ILoggingEvent} to the pipeline.
     */
    void append(ILoggingEvent event) {
      // Don't append if the pipeline is already stopped.
      if (!isRunning()) {
        return;
      }

      Logger logger = context.getEffectiveLogger(event.getLoggerName());
      if (event.getLevel().isGreaterOrEqual(logger.getEffectiveLevel())) {
        try {
          // This will block until the queue has free space.
          eventQueue.put(event);
        } catch (InterruptedException e) {
          // Should never happen. Just ignore the exception and reset the flag
          Thread.currentThread().interrupt();
        }
      }
    }

    private void callAppenders(ILoggingEvent event) {
      if (event == SHUTDOWN_EVENT) {
        return;
      }
      Logger logger = context.getEffectiveLogger(event.getLoggerName());
      try {
        logger.callAppenders(event);
      } catch (Throwable t) {
        addError("Exception raised when appending to logger " + logger.getName() +
                   " with message " + event.getFormattedMessage(), t);
      }
    }

    private void sync(long now) {
      try {
        context.sync();
        lastSyncTime = now;
      } catch (IOException e) {
        addError("Exception raised when syncing log processing pipeline " + getName(), e);
      }
    }
  }
}
