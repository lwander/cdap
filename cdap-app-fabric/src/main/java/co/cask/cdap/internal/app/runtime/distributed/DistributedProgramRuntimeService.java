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

package co.cask.cdap.internal.app.runtime.distributed;

import co.cask.cdap.app.guice.AppFabricServiceRuntimeModule;
import co.cask.cdap.app.runtime.AbstractProgramRuntimeService;
import co.cask.cdap.app.runtime.ProgramController;
import co.cask.cdap.app.runtime.ProgramRunnerFactory;
import co.cask.cdap.app.runtime.ProgramStateWriter;
import co.cask.cdap.app.store.Store;
import co.cask.cdap.common.app.RunIds;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.io.Locations;
import co.cask.cdap.common.twill.TwillAppNames;
import co.cask.cdap.internal.app.runtime.AbstractListener;
import co.cask.cdap.internal.app.runtime.artifact.ArtifactDetail;
import co.cask.cdap.internal.app.runtime.artifact.ArtifactRepository;
import co.cask.cdap.internal.app.runtime.service.SimpleRuntimeInfo;
import co.cask.cdap.internal.app.store.RunRecordMeta;
import co.cask.cdap.proto.Containers;
import co.cask.cdap.proto.DistributedProgramLiveInfo;
import co.cask.cdap.proto.ProgramLiveInfo;
import co.cask.cdap.proto.ProgramRunStatus;
import co.cask.cdap.proto.ProgramType;
import co.cask.cdap.proto.id.ArtifactId;
import co.cask.cdap.proto.id.ProgramId;
import co.cask.cdap.proto.id.ProgramRunId;
import co.cask.cdap.security.impersonation.Impersonator;
import com.google.common.base.Throwables;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Table;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import org.apache.twill.api.ResourceReport;
import org.apache.twill.api.RunId;
import org.apache.twill.api.TwillController;
import org.apache.twill.api.TwillRunResources;
import org.apache.twill.api.TwillRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

import static co.cask.cdap.proto.Containers.ContainerInfo;

/**
 *
 */
public final class DistributedProgramRuntimeService extends AbstractProgramRuntimeService {

  private static final Logger LOG = LoggerFactory.getLogger(DistributedProgramRuntimeService.class);

  private final TwillRunner twillRunner;
  private final Store store;
  private final Impersonator impersonator;
  private final ProgramStateWriter programStateWriter;

  @Inject
  DistributedProgramRuntimeService(CConfiguration cConf,
                                   ProgramRunnerFactory programRunnerFactory,
                                   TwillRunner twillRunner, Store store,
                                   // for running a program, we only need EXECUTE on the program, there should be no
                                   // privileges needed for artifacts
                                   @Named(AppFabricServiceRuntimeModule.NOAUTH_ARTIFACT_REPO)
                                     ArtifactRepository noAuthArtifactRepository,
                                   Impersonator impersonator, ProgramStateWriter programStateWriter) {
    super(cConf, programRunnerFactory, noAuthArtifactRepository);
    this.twillRunner = twillRunner;
    this.store = store;
    this.impersonator = impersonator;
    this.programStateWriter = programStateWriter;
  }

  @Nullable
  @Override
  protected RuntimeInfo createRuntimeInfo(final ProgramController controller, final ProgramId programId,
                                          final Runnable cleanUpTask) {
    if (controller instanceof AbstractTwillProgramController) {
      RunId twillRunId = ((AbstractTwillProgramController) controller).getTwillRunId();

      // Add a listener that publishes KILLED status notification when the YARN application is killed in case that
      // the KILLED status notification is not published from the YARN application container, so we don't need to wait
      // for the run record corrector to mark the status as KILLED.
      controller.addListener(new AbstractListener() {
        @Override
        public void init(ProgramController.State currentState, @Nullable Throwable cause) {
          if (currentState == ProgramController.State.ALIVE) {
            alive();
          } else if (currentState == ProgramController.State.KILLED) {
            killed();
          }
        }

        @Override
        public void alive() {
          cleanUpTask.run();
        }

        @Override
        public void killed() {
          programStateWriter.killed(programId.run(controller.getRunId()));
        }
      }, MoreExecutors.sameThreadExecutor());
      return new SimpleRuntimeInfo(controller, programId, twillRunId);
    }
    return null;
  }

  @Override
  protected void copyArtifact(ArtifactId artifactId,
                              final ArtifactDetail artifactDetail, final File targetFile) throws IOException {
    try {
      impersonator.doAs(artifactId, () -> {
        Locations.linkOrCopy(artifactDetail.getDescriptor().getLocation(), targetFile);
        return null;
      });
    } catch (Exception e) {
      Throwables.propagateIfPossible(e, IOException.class);
      // should not happen
      throw Throwables.propagate(e);
    }
  }

  @Override
  public synchronized RuntimeInfo lookup(ProgramId programId, final RunId runId) {
    RuntimeInfo runtimeInfo = super.lookup(programId, runId);
    if (runtimeInfo != null) {
      return runtimeInfo;
    }

    // Lookup the Twill RunId for the given run
    ProgramRunId programRunId = programId.run(runId.getId());
    RunRecordMeta record = store.getRun(programRunId);
    if (record == null) {
      return null;
    }
    if (record.getTwillRunId() == null) {
      LOG.warn("Twill RunId does not exist for the program {}, runId {}", programId, runId.getId());
      return null;
    }

    RunId twillRunIdFromRecord = org.apache.twill.internal.RunIds.fromString(record.getTwillRunId());
    return lookupFromTwillRunner(twillRunner, programRunId, twillRunIdFromRecord);
  }

  @Override
  public synchronized Map<RunId, RuntimeInfo> list(ProgramType type) {
    Map<RunId, RuntimeInfo> result = new HashMap<>(super.list(type));

    // Table holds the Twill RunId and TwillController associated with the program matching the input type
    Table<ProgramId, RunId, TwillController> twillProgramInfo = HashBasedTable.create();

    List<RuntimeInfo> runtimeInfos = getRuntimeInfos();

    // Goes through all live application and fill the twillProgramInfo table
    for (TwillRunner.LiveInfo liveInfo : twillRunner.lookupLive()) {
      String appName = liveInfo.getApplicationName();
      ProgramId programId = TwillAppNames.fromTwillAppName(appName, false);
      if (programId == null) {
        continue;
      }
      if (!type.equals(programId.getType())) {
        continue;
      }

      for (TwillController controller : liveInfo.getControllers()) {
        RunId twillRunId = controller.getRunId();

        // If it already in the runtime info, no need to lookup
        if (runtimeInfos.stream().anyMatch(info -> twillRunId.equals(info.getTwillRunId()))) {
          continue;
        }

        twillProgramInfo.put(programId, twillRunId, controller);
      }
    }

    if (twillProgramInfo.isEmpty()) {
      return ImmutableMap.copyOf(result);
    }

    final Set<RunId> twillRunIds = twillProgramInfo.columnKeySet();
    Collection<RunRecordMeta> activeRunRecords = store.getRuns(ProgramRunStatus.RUNNING, record ->
      record.getTwillRunId() != null
        && twillRunIds.contains(org.apache.twill.internal.RunIds.fromString(record.getTwillRunId()))).values();

    for (RunRecordMeta record : activeRunRecords) {
      String twillRunId = record.getTwillRunId();
      if (twillRunId == null) {
        // This is unexpected. Just log and ignore the run record
        LOG.warn("No twill runId for in run record {}.", record);
        continue;
      }

      RunId twillRunIdFromRecord = org.apache.twill.internal.RunIds.fromString(twillRunId);
      // Get the CDAP RunId from RunRecord
      RunId runId = RunIds.fromString(record.getPid());
      // Get the Program and TwillController for the current twillRunId
      Map<ProgramId, TwillController> mapForTwillId = twillProgramInfo.columnMap().get(twillRunIdFromRecord);
      Map.Entry<ProgramId, TwillController> entry = mapForTwillId.entrySet().iterator().next();

      // Create RuntimeInfo for the current Twill RunId
      if (result.computeIfAbsent(runId, rid -> createRuntimeInfo(entry.getKey(), rid, entry.getValue())) == null) {
        LOG.warn("Unable to create runtime info for program {} with run id {}", entry.getKey(), runId);
      }
    }

    return ImmutableMap.copyOf(result);
  }

  @Override
  public ProgramLiveInfo getLiveInfo(ProgramId program) {
    String twillAppName = TwillAppNames.toTwillAppName(program);
    Iterator<TwillController> controllers = twillRunner.lookup(twillAppName).iterator();
    // this will return an empty Json if there is no live instance
    if (controllers.hasNext()) {
      TwillController controller = controllers.next();
      if (controllers.hasNext()) {
        LOG.warn("Expected at most one live instance of Twill app {} but found at least two.", twillAppName);
      }
      ResourceReport report = controller.getResourceReport();
      if (report != null) {
        DistributedProgramLiveInfo liveInfo = new DistributedProgramLiveInfo(program, report.getApplicationId());

        Containers.ContainerType containerType = Containers.ContainerType.valueOf(program.getType().name());

        for (Map.Entry<String, Collection<TwillRunResources>> entry : report.getResources().entrySet()) {
          for (TwillRunResources resources : entry.getValue()) {
            liveInfo.addContainer(new ContainerInfo(containerType,
                                                    entry.getKey(),
                                                    resources.getInstanceId(),
                                                    resources.getContainerId(),
                                                    resources.getHost(),
                                                    resources.getMemoryMB(),
                                                    resources.getVirtualCores(),
                                                    resources.getDebugPort()));
          }
        }

        // Add a list of announced services and their discoverables to the liveInfo.
        liveInfo.addServices(report.getServices());
        return liveInfo;
      }
    }

    return super.getLiveInfo(program);
  }

  @Override
  protected void startUp() {
    LOG.debug("started distributed program runtime service");
  }
}
