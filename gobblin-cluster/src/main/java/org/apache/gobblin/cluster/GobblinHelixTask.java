/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.gobblin.cluster;

import java.io.IOException;
import java.util.List;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.helix.task.Task;
import org.apache.helix.task.TaskCallbackContext;
import org.apache.helix.task.TaskConfig;
import org.apache.helix.task.TaskResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.common.io.Closer;
import com.typesafe.config.ConfigFactory;

import org.apache.gobblin.annotation.Alpha;
import org.apache.gobblin.broker.SharedResourcesBrokerFactory;
import org.apache.gobblin.broker.gobblin_scopes.GobblinScopeTypes;
import org.apache.gobblin.broker.gobblin_scopes.JobScopeInstance;
import org.apache.gobblin.broker.iface.SharedResourcesBroker;
import org.apache.gobblin.configuration.ConfigurationKeys;
import org.apache.gobblin.runtime.AbstractJobLauncher;
import org.apache.gobblin.runtime.GobblinMultiTaskAttempt;
import org.apache.gobblin.runtime.JobState;
import org.apache.gobblin.runtime.TaskState;
import org.apache.gobblin.runtime.util.StateStores;
import org.apache.gobblin.source.workunit.MultiWorkUnit;
import org.apache.gobblin.source.workunit.WorkUnit;
import org.apache.gobblin.util.Id;
import org.apache.gobblin.util.JobLauncherUtils;
import org.apache.gobblin.util.SerializationUtils;


/**
 * An implementation of Helix's {@link org.apache.helix.task.Task} that wraps and runs one or more Gobblin
 * {@link org.apache.gobblin.runtime.Task}s.
 *
 * <p>
 *   Upon startup, a {@link GobblinHelixTask} reads the property
 *   {@link GobblinClusterConfigurationKeys#WORK_UNIT_FILE_PATH} for the path of the file storing a serialized
 *   {@link WorkUnit} on the {@link FileSystem} of choice and de-serializes the {@link WorkUnit}. Depending on
 *   if the serialized {@link WorkUnit} is a {@link MultiWorkUnit}, it then creates one or more Gobblin
 *   {@link org.apache.gobblin.runtime.Task}s to run the {@link WorkUnit}(s) (possibly wrapped in the {@link MultiWorkUnit})
 *   and waits for the Gobblin {@link org.apache.gobblin.runtime.Task}(s) to finish. Upon completion of the Gobblin
 *   {@link org.apache.gobblin.runtime.Task}(s), it persists the {@link TaskState} of each {@link org.apache.gobblin.runtime.Task} to
 *   a file that will be collected by the {@link GobblinHelixJobLauncher} later upon completion of the job.
 * </p>
 *
 * @author Yinan Li
 */
@Alpha
public class GobblinHelixTask implements Task {

  private static final Logger LOGGER = LoggerFactory.getLogger(GobblinHelixTask.class);

  private final TaskConfig taskConfig;
  // An empty JobState instance that will be filled with values read from the serialized JobState
  private final JobState jobState = new JobState();
  private final String jobName;
  private final String jobId;
  private final String jobKey;

  private final FileSystem fs;
  private final StateStores stateStores;
  private final TaskAttemptBuilder taskAttemptBuilder;

  private GobblinMultiTaskAttempt taskAttempt;

  public GobblinHelixTask(TaskCallbackContext taskCallbackContext, FileSystem fs, Path appWorkDir,
      TaskAttemptBuilder taskAttemptBuilder, StateStores stateStores)
      throws IOException {

    this.taskConfig = taskCallbackContext.getTaskConfig();
    this.stateStores = stateStores;
    this.taskAttemptBuilder = taskAttemptBuilder;
    this.jobName = this.taskConfig.getConfigMap().get(ConfigurationKeys.JOB_NAME_KEY);
    this.jobId = this.taskConfig.getConfigMap().get(ConfigurationKeys.JOB_ID_KEY);
    this.jobKey = Long.toString(Id.parse(this.jobId).getSequence());

    this.fs = fs;

    Path jobStateFilePath = new Path(appWorkDir, this.jobId + "." + AbstractJobLauncher.JOB_STATE_FILE_NAME);
    SerializationUtils.deserializeState(this.fs, jobStateFilePath, this.jobState);

  }

  @Override
  public TaskResult run() {
    SharedResourcesBroker<GobblinScopeTypes> globalBroker = null;
    try (Closer closer = Closer.create()) {
      closer.register(MDC.putCloseable(ConfigurationKeys.JOB_NAME_KEY, this.jobName));
      closer.register(MDC.putCloseable(ConfigurationKeys.JOB_KEY_KEY, this.jobKey));
      Path workUnitFilePath =
          new Path(this.taskConfig.getConfigMap().get(GobblinClusterConfigurationKeys.WORK_UNIT_FILE_PATH));

      String fileName = workUnitFilePath.getName();
      String storeName = workUnitFilePath.getParent().getName();
      WorkUnit workUnit;

      if (workUnitFilePath.getName().endsWith(AbstractJobLauncher.MULTI_WORK_UNIT_FILE_EXTENSION)) {
        workUnit = stateStores.mwuStateStore.getAll(storeName, fileName).get(0);
      } else {
        workUnit = stateStores.wuStateStore.getAll(storeName, fileName).get(0);
      }

      // The list of individual WorkUnits (flattened) to run
      List<WorkUnit> workUnits = Lists.newArrayList();

      if (workUnit instanceof MultiWorkUnit) {
        // Flatten the MultiWorkUnit so the job configuration properties can be added to each individual WorkUnits
        List<WorkUnit> flattenedWorkUnits =
            JobLauncherUtils.flattenWorkUnits(((MultiWorkUnit) workUnit).getWorkUnits());
        workUnits.addAll(flattenedWorkUnits);
      } else {
        workUnits.add(workUnit);
      }

      globalBroker = SharedResourcesBrokerFactory.createDefaultTopLevelBroker(
          ConfigFactory.parseProperties(this.jobState.getProperties()), GobblinScopeTypes.GLOBAL.defaultScopeInstance());
      SharedResourcesBroker<GobblinScopeTypes> jobBroker =
          globalBroker.newSubscopedBuilder(new JobScopeInstance(this.jobState.getJobName(), this.jobState.getJobId())).build();

      this.taskAttempt = this.taskAttemptBuilder.build(workUnits.iterator(), this.jobId, this.jobState, jobBroker);
      this.taskAttempt.runAndOptionallyCommitTaskAttempt(GobblinMultiTaskAttempt.CommitPolicy.IMMEDIATE);
      return new TaskResult(TaskResult.Status.COMPLETED, String.format("completed tasks: %d", workUnits.size()));
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      return new TaskResult(TaskResult.Status.CANCELED, "");
    } catch (Throwable t) {
      LOGGER.error("GobblinHelixTask failed due to " + t.getMessage(), t);
      return new TaskResult(TaskResult.Status.ERROR, Throwables.getStackTraceAsString(t));
    } finally {
      if (globalBroker != null) {
        try {
          globalBroker.close();
        } catch (IOException ioe) {
          LOGGER.error("Could not close shared resources broker.", ioe);
        }
      }
    }
  }

  @Override
  public void cancel() {
    if (this.taskAttempt != null) {
      try {
        LOGGER.info("Task cancelled: Shutdown starting for tasks with jobId: {}", this.jobId);
        this.taskAttempt.shutdownTasks();
        LOGGER.info("Task cancelled: Shutdown complete for tasks with jobId: {}", this.jobId);
      } catch (InterruptedException e) {
        throw new RuntimeException("Interrupted while shutting down task with jobId: " + this.jobId, e);
      }
    } else {
      LOGGER.error("Task cancelled but taskAttempt is null, so ignoring.");
    }
  }
}
