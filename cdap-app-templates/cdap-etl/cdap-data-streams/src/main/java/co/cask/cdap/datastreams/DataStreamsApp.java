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

package co.cask.cdap.datastreams;

import co.cask.cdap.api.app.AbstractApplication;
import co.cask.cdap.api.dataset.lib.FileSet;
import co.cask.cdap.etl.api.AlertPublisher;
import co.cask.cdap.etl.api.batch.BatchSink;
import co.cask.cdap.etl.api.batch.SparkSink;
import co.cask.cdap.etl.api.streaming.StreamingSource;
import co.cask.cdap.etl.proto.v2.DataStreamsConfig;
import co.cask.cdap.etl.validation.InvalidPipelineException;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableSet;

/**
 * Data Streams Application.
 */
public class DataStreamsApp extends AbstractApplication<DataStreamsConfig> {

  @Override
  public void configure() {
    DataStreamsConfig config = getConfig();
    setDescription(Objects.firstNonNull(config.getDescription(), "Data Streams Application"));

    DataStreamsPipelineSpec spec;
    try {
      spec = new DataStreamsPipelineSpecGenerator(getConfigurer(),
                                                  ImmutableSet.of(StreamingSource.PLUGIN_TYPE),
                                                  ImmutableSet.of(BatchSink.PLUGIN_TYPE, SparkSink.PLUGIN_TYPE,
                                                                  AlertPublisher.PLUGIN_TYPE)).generateSpec(config);
    } catch (InvalidPipelineException e) {
      throw new IllegalArgumentException(String.format("Failed to configure pipeline: %s", e.getMessage()), e);
    }
    addSpark(new DataStreamsSparkLauncher(spec));
  }
}
