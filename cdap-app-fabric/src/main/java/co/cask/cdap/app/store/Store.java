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

package co.cask.cdap.app.store;

import co.cask.cdap.api.ProgramSpecification;
import co.cask.cdap.api.app.Application;
import co.cask.cdap.api.app.ApplicationSpecification;
import co.cask.cdap.api.artifact.ArtifactId;
import co.cask.cdap.api.worker.Worker;
import co.cask.cdap.api.workflow.Workflow;
import co.cask.cdap.api.workflow.WorkflowToken;
import co.cask.cdap.app.program.Program;
import co.cask.cdap.app.program.ProgramDescriptor;
import co.cask.cdap.common.ApplicationNotFoundException;
import co.cask.cdap.common.NotFoundException;
import co.cask.cdap.common.ProgramNotFoundException;
import co.cask.cdap.internal.app.store.RunRecordMeta;
import co.cask.cdap.internal.app.store.WorkflowTable;
import co.cask.cdap.proto.BasicThrowable;
import co.cask.cdap.proto.ProgramHistory;
import co.cask.cdap.proto.ProgramRunClusterStatus;
import co.cask.cdap.proto.ProgramRunStatus;
import co.cask.cdap.proto.RunCountResult;
import co.cask.cdap.proto.WorkflowNodeStateDetail;
import co.cask.cdap.proto.WorkflowStatistics;
import co.cask.cdap.proto.id.ApplicationId;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.proto.id.ProgramId;
import co.cask.cdap.proto.id.ProgramRunId;
import co.cask.cdap.proto.id.WorkflowId;
import org.apache.twill.api.RunId;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * Responsible for managing {@link Program} and {@link Application} metadata.
 *
 * TODO: set state methods are only called from unit tests. They should be removed in favor of using AppMetadataStore.
 */
public interface Store {

  /**
   * Logs initialization of program run and persists program cluster status to
   * {@link ProgramRunClusterStatus#PROVISIONING}.
   *
   * @param id run id of the program
   * @param runtimeArgs the runtime arguments for this program run
   * @param systemArgs the system arguments for this program run
   * @param sourceId id of the source of program run status, which is proportional to the timestamp of
   *                 when the current program run status is reached
   * @param artifactId artifact id used to create the application the program belongs to
   */
  void setProvisioning(ProgramRunId id, Map<String, String> runtimeArgs,
                       Map<String, String> systemArgs, byte[] sourceId, ArtifactId artifactId);

  /**
   * Persists program run cluster status to {@link ProgramRunClusterStatus#PROVISIONED}.
   *
   * @param id run id of the program
   * @param numNodes number of nodes for the cluster
   * @param sourceId id of the source of program run status, which is proportional to the timestamp of
   *                 when the current program run status is reached
   */
  void setProvisioned(ProgramRunId id, int numNodes, byte[] sourceId);

  /**
   * Logs initialization of program run and persists program status to {@link ProgramRunStatus#STARTING}.
   *
   * @param id run id of the program
   * @param twillRunId Twill run id
   * @param systemArgs the system arguments for this program run
   * @param sourceId id of the source of program run status, which is proportional to the timestamp of
   *                 when the current program run status is reached
   */
  void setStart(ProgramRunId id, @Nullable String twillRunId, Map<String, String> systemArgs, byte[] sourceId);

  /**
   * Logs rejection of a program run and persists program status to {@link ProgramRunStatus#REJECTED}.
   *
   * @param id run id of the program
   * @param runtimeArgs the runtime arguments for this program run
   * @param systemArgs the system arguments for this program run
   * @param sourceId id of the source of program run status, which is proportional to the timestamp of
   *                 when the current program run status is reached
   * @param artifactId artifact id used to create the application the program belongs to
   */
  void setRejected(ProgramRunId id, Map<String, String> runtimeArgs, Map<String, String> systemArgs,
                   byte[] sourceId, ArtifactId artifactId);

  /**
   * Logs start of program run and persists program status to {@link ProgramRunStatus#RUNNING}.
   *
   * @param id run id of the program
   * @param twillRunId Twill run id
   * @param sourceId id of the source of program run status, which is proportional to the timestamp of
   *                 when the current program run status is reached
   */
  void setRunning(ProgramRunId id, long runTime, @Nullable String twillRunId, byte[] sourceId);

  /**
   * Logs end of program run and sets the run status to one of: {@link ProgramRunStatus#COMPLETED},
   * or {@link ProgramRunStatus#KILLED}.
   *
   * @param id run id of the program
   * @param endTime end timestamp in seconds
   * @param runStatus {@link ProgramRunStatus} of program run
   * @param sourceId id of the source of program run status, which is proportional to the timestamp of
   *                 when the current program run status is reached
   */
  void setStop(ProgramRunId id, long endTime, ProgramRunStatus runStatus, byte[] sourceId);

  /**
   * Logs end of program run and sets the run status to {@link ProgramRunStatus#FAILED} with a failure cause.
   *
   * @param id run id of the program
   * @param endTime end timestamp in seconds
   * @param runStatus {@link ProgramRunStatus} of program run
   * @param failureCause failure cause if the program failed to execute
   * @param sourceId id of the source of program run status, which is proportional to the timestamp of
   *                 when the current program run status is reached
   */
  void setStop(ProgramRunId id, long endTime, ProgramRunStatus runStatus,
               @Nullable BasicThrowable failureCause, byte[] sourceId);

  /**
   * Logs suspend of a program run and sets the run status to {@link ProgramRunStatus#SUSPENDED}.
   *
   * @param id run id of the program
   * @param sourceId id of the source of program run status, which is proportional to the timestamp of
   *                 when the current program run status is reached
   * @param suspendTime time when the program is suspended
   */
  void setSuspend(ProgramRunId id, byte[] sourceId, long suspendTime);

  /**
   * Logs resume of a program run and sets the run status to {@link ProgramRunStatus#RUNNING}.
   *
   * @param id run id of the program
   * @param sourceId id of the source of program run status, which is proportional to the timestamp of
   *                 when the current program run status is reached
   * @param resumeTime time when the program is resumed from suspended state
   */
  void setResume(ProgramRunId id, byte[] sourceId, long resumeTime);

  /**
   * Loads a given program.
   *
   * @param program id of the program
   * @return An instance of {@link ProgramDescriptor} if found.
   * @throws IOException
   */
  ProgramDescriptor loadProgram(ProgramId program) throws IOException, ApplicationNotFoundException,
                                                          ProgramNotFoundException;

  /**
   * Fetches run records for particular program.
   * Returned ProgramRunRecords are sorted by their startTime.
   *
   * @param id        id of the program
   * @param status    status of the program running/completed/failed or all
   * @param startTime fetch run history that has started after the startTime in seconds
   * @param endTime   fetch run history that has started before the endTime in seconds
   * @param limit     max number of entries to fetch for this history call
   * @return          map of logged runs
   */
  Map<ProgramRunId, RunRecordMeta> getRuns(ProgramId id, ProgramRunStatus status,
                                           long startTime, long endTime, int limit);

  /**
   * Fetches run records for particular program.
   * Returned ProgramRunRecords are sorted by their startTime.
   *
   * @param id        id of the program
   * @param status    status of the program running/completed/failed or all
   * @param startTime fetch run history that has started after the startTime in seconds
   * @param endTime   fetch run history that has started before the endTime in seconds
   * @param limit     max number of entries to fetch for this history call
   * @param filter    predicate to be passed to filter the records
   * @return          map of logged runs
   */
  Map<ProgramRunId, RunRecordMeta> getRuns(ProgramId id, ProgramRunStatus status, long startTime, long endTime,
                                           int limit, Predicate<RunRecordMeta> filter);

  /**
   * Fetches the run records for the particular status. Same as calling
   * {@link #getRuns(ProgramRunStatus, long, long, int, Predicate)
   * getRuns(status, 0, Long.MAX_VALUE, Integer.MAX_VALUE, filter)}
   */
  Map<ProgramRunId, RunRecordMeta> getRuns(ProgramRunStatus status, Predicate<RunRecordMeta> filter);

  /**
   * Fetches run records for the particular status.
   *
   * @param status status of the program to filter the records
   * @param startTime fetch run history that has started after the startTime in seconds
   * @param endTime fetch run history that has started before the endTime in seconds
   * @param limit max number of entries to fetch for this history call
   * @param filter predicate to be passed to filter the records
   * @return a map from {@link ProgramRunId} to the corresponding {@link RunRecordMeta}.
   */
  Map<ProgramRunId, RunRecordMeta> getRuns(ProgramRunStatus status, long startTime,
                                           long endTime, int limit, Predicate<RunRecordMeta> filter);

  /**
   * Fetches the run records for given ProgramRunIds.
   * @param programRunIds  list of program RunIds to match against
   * @return        map of logged runs
   */
  Map<ProgramRunId, RunRecordMeta> getRuns(Set<ProgramRunId> programRunIds);

  /**
   * Fetches the active (i.e STARTING or RUNNING or SUSPENDED) run records across all namespaces.
   *
   * @param limit count at most that many runs, stop if there are more.
   * @return map of logged runs
   */
  int countActiveRuns(@Nullable Integer limit);

  /**
   * Fetches the active (i.e STARTING or RUNNING or SUSPENDED) run records against a given NamespaceId.
   * @param namespaceId the namespace id to match against
   * @return map of logged runs
   */
  Map<ProgramRunId, RunRecordMeta> getActiveRuns(NamespaceId namespaceId);

  /**
   * Fetches the active (i.e STARTING or RUNNING or SUSPENDED) run records from the given NamespaceId's.
   * @param namespaces the namespace id's to get active run records from
   * @param filter predicate to be passed to filter the records
   * @return map of logged runs
   */
  Map<ProgramRunId, RunRecordMeta> getActiveRuns(Set<NamespaceId> namespaces, Predicate<RunRecordMeta> filter);

  /**
   * Fetches the active (i.e STARTING or RUNNING or SUSPENDED) run records against a given ApplicationId.
   * @param applicationId the application id to match against
   * @return map of logged runs
   */
  Map<ProgramRunId, RunRecordMeta> getActiveRuns(ApplicationId applicationId);

  /**
   * Fetches the active (i.e STARTING or RUNNING or SUSPENDED) run records against a given ProgramId.
   * @param programId the program id to match against
   * @return map of logged runs
   */
  Map<ProgramRunId, RunRecordMeta> getActiveRuns(ProgramId programId);

  /**
   * Fetches the historical (i.e COMPLETED or FAILED or KILLED) run records from a given set of namespaces
   * which matches both the earliestStopTime and latestStartTime conditions.
   *
   * @param namespaces fetch run history that is belonged to one of these namespaces
   * @param earliestStopTime fetch run history that has stopped at or after the earliestStopTime in seconds
   * @param latestStartTime fetch run history that has started before the latestStartTime in seconds
   * @param limit max number of entries to fetch for this history call
   * @return map of logged runs
   */
  Map<ProgramRunId, RunRecordMeta> getHistoricalRuns(Set<NamespaceId> namespaces,
                                                     long earliestStopTime, long latestStartTime, int limit);

  /**
   * Fetches the run record for particular run of a program.
   *
   * @param id        run id of the program
   * @return          run record for the specified program and runid, null if not found
   */
  @Nullable
  RunRecordMeta getRun(ProgramRunId id);

  /**
   * Creates new application if it doesn't exist. Updates existing one otherwise.
   *
   * @param id            application id
   * @param specification application specification to store
   */
  void addApplication(ApplicationId id, ApplicationSpecification specification);


  /**
   * Return a list of program specifications that are deleted comparing the specification in the store with the
   * spec that is passed.
   *
   * @param id                   ApplicationId
   * @param specification        Application specification
   * @return                     List of ProgramSpecifications that are deleted
   */
  List<ProgramSpecification> getDeletedProgramSpecifications(ApplicationId id,
                                                             ApplicationSpecification specification);

  /**
   * Returns application specification by id.
   *
   * @param id application id
   * @return application specification
   */
  @Nullable
  ApplicationSpecification getApplication(ApplicationId id);

  /**
   * Returns a collection of all application specs in the specified namespace
   *
   * @param id the namespace to get application specs from
   * @return collection of all application specs in the namespace
   */
  Collection<ApplicationSpecification> getAllApplications(NamespaceId id);

  /**
   * Returns a collection of all application specs of all the versions of the application by id
   *
   * @param id application id
   * @return collection of all application specs of all the application versions
   */
  Collection<ApplicationSpecification> getAllAppVersions(ApplicationId id);

  /**
   * Returns a list of all versions' ApplicationId's of the application by id
   *
   * @param id application id
   * @return collection of versionIds of the application's versions
   */
  Collection<ApplicationId> getAllAppVersionsAppIds(ApplicationId id);

  /**
   * Sets the number of instances of a service.
   *
   * @param id id of the program
   * @param instances number of instances
   */
  void setServiceInstances(ProgramId id, int instances);

  /**
   * Returns the number of instances of a service.
   * @param id id of the program
   * @return number of instances
   */
  int getServiceInstances(ProgramId id);

  /**
   * Sets the number of instances of a {@link Worker}
   *
   * @param id id of the program
   * @param instances number of instances
   */
  void setWorkerInstances(ProgramId id, int instances);

  /**
   * Gets the number of instances of a {@link Worker}
   *
   * @param id id of the program
   * @return number of instances
   */
  int getWorkerInstances(ProgramId id);

  /**
   * Removes all program under the given application and also the application itself.
   *
   * @param id Application id
   */
  void removeApplication(ApplicationId id);

  /**
   * Removes all applications (with programs) associated with the given namespace.
   *
   * @param id namespace id whose applications to remove
   */
  void removeAllApplications(NamespaceId id);

  /**
   * Remove all metadata associated with the given namespace.
   *
   * @param id namespace id whose items to remove
   */
  void removeAll(NamespaceId id);

  /**
   * Get run time arguments for a program.
   *
   * @param runId id of the program run
   * @return Map of key, value pairs
   */
  Map<String, String> getRuntimeArguments(ProgramRunId runId);

  /**
   * Deletes data for an application from the WorkflowTable table
   * @param id id of application to be deleted
   */
  void deleteWorkflowStats(ApplicationId id);

  /**
   * Check if an application exists.
   * @param id id of application.
   * @return true if the application exists, false otherwise.
   */
  boolean applicationExists(ApplicationId id);

  /**
   * Check if a program exists.
   * @param id id of the program
   * @return true if the program exists, false otherwise.
   */
  boolean programExists(ProgramId id);

  /**
   * Retrieves the {@link WorkflowToken} for a specified run of a workflow.
   *
   * @param workflowId {@link WorkflowId} of the workflow whose {@link WorkflowToken} is to be retrieved
   * @param workflowRunId Run Id of the workflow for which the {@link WorkflowToken} is to be retrieved
   * @return the {@link WorkflowToken} for the specified workflow run
   */
  WorkflowToken getWorkflowToken(WorkflowId workflowId, String workflowRunId);

  /**
   * Get the node states for a given {@link Workflow} run.
   *
   * @param workflowRunId run of the Workflow.
   * @return {@link List} of {@link WorkflowNodeStateDetail}
   */
  List<WorkflowNodeStateDetail> getWorkflowNodeStates(ProgramRunId workflowRunId);

  /**
   * Used by {@link co.cask.cdap.gateway.handlers.WorkflowStatsSLAHttpHandler} to get the statistics of all completed
   * workflows in a time range.
   *
   * @param workflowId Workflow that needs to have its statistics returned
   * @param startTime StartTime of the range
   * @param endTime EndTime of the range
   * @param percentiles List of percentiles that the user wants to see
   * @return the statistics for a given workflow
   */
  WorkflowStatistics getWorkflowStatistics(WorkflowId workflowId, long startTime,
                                           long endTime, List<Double> percentiles);

  /**
   * Returns the record that represents the run of a workflow.
   *
   * @param workflowId The Workflow whose run needs to be queried
   * @param runId RunId of the workflow run
   * @return A workflow run record corresponding to the runId
   */
  WorkflowTable.WorkflowRunRecord getWorkflowRun(WorkflowId workflowId, String runId);

  /**
   * Get a list of workflow runs that are spaced apart by time interval in both directions from the run id provided.
   *
   * @param workflowId The workflow whose statistics need to be obtained
   * @param runId The run id of the workflow
   * @param limit The number of the records that the user wants to compare against on either side of the run
   * @param timeInterval The timeInterval with which the user wants to space out the runs
   * @return Map of runId of Workflow to DetailedStatistics of the run
   */
  Collection<WorkflowTable.WorkflowRunRecord> retrieveSpacedRecords(WorkflowId workflowId, String runId,
                                                                    int limit, long timeInterval);

  /**
   * @return programs that were running between given start and end time.
   */
  Set<RunId> getRunningInRange(long startTimeInSecs, long endTimeInSecs);

  /**
   * Get the run count of the given program.
   *
   * @param programId the program to get the count
   * @return the number of run count
   * @throws NotFoundException if the app or the program does not exist
   */
  long getProgramRunCount(ProgramId programId) throws NotFoundException;

  /**
   * Get the run count of the given program collection
   *
   * @param programIds collection of program ids to get the count
   * @return the run count result of each program in the collection
   */
  List<RunCountResult> getProgramRunCounts(Collection<ProgramId> programIds);

  /**
   * Fetches run records for multiple programs.
   *
   * @param programs  the programs to get run records for
   * @param status    status of the program running/completed/failed or all
   * @param startTime fetch run history that has started after the startTime in seconds
   * @param endTime   fetch run history that has started before the endTime in seconds
   * @param limit     max number of runs to fetch for each program
   * @param filter    predicate to be passed to filter the records
   * @return          runs for each program
   */
  List<ProgramHistory> getRuns(Collection<ProgramId> programs, ProgramRunStatus status, long startTime, long endTime,
                               int limit, Predicate<RunRecordMeta> filter);
}
