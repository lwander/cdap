/*
 * Copyright © 2014-2017 Cask Data, Inc.
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

package co.cask.cdap.cli.command.artifact;

import co.cask.cdap.api.artifact.ArtifactScope;
import co.cask.cdap.cli.ArgumentName;
import co.cask.cdap.cli.CLIConfig;
import co.cask.cdap.cli.ElementType;
import co.cask.cdap.cli.util.AbstractAuthCommand;
import co.cask.cdap.cli.util.RowMaker;
import co.cask.cdap.cli.util.table.Table;
import co.cask.cdap.client.ArtifactClient;
import co.cask.cdap.proto.artifact.PluginSummary;
import co.cask.cdap.proto.id.ArtifactId;
import com.google.common.collect.Lists;
import com.google.inject.Inject;
import io.cdap.common.cli.Arguments;

import java.io.PrintStream;
import java.util.List;

/**
 * Lists all plugins of a specific type available to an artifact.
 */
public class ListArtifactPluginsCommand extends AbstractAuthCommand {

  private final ArtifactClient artifactClient;

  @Inject
  public ListArtifactPluginsCommand(ArtifactClient artifactClient, CLIConfig cliConfig) {
    super(cliConfig);
    this.artifactClient = artifactClient;
  }

  @Override
  public void perform(Arguments arguments, PrintStream output) throws Exception {

    String artifactName = arguments.get(ArgumentName.ARTIFACT_NAME.toString());
    String artifactVersion = arguments.get(ArgumentName.ARTIFACT_VERSION.toString());
    ArtifactId artifactId = cliConfig.getCurrentNamespace().artifact(artifactName, artifactVersion);
    String pluginType = arguments.get(ArgumentName.PLUGIN_TYPE.toString());

    final List<PluginSummary> pluginSummaries;
    String scopeStr = arguments.getOptional(ArgumentName.SCOPE.toString());
    if (scopeStr == null) {
      pluginSummaries = artifactClient.getPluginSummaries(artifactId, pluginType);
    } else {
      pluginSummaries = artifactClient.getPluginSummaries(artifactId, pluginType,
        ArtifactScope.valueOf(scopeStr.toUpperCase()));
    }
    Table table = Table.builder()
      .setHeader("type", "name", "classname", "description", "artifact")
      .setRows(pluginSummaries, new RowMaker<PluginSummary>() {
        @Override
        public List<?> makeRow(PluginSummary object) {
          return Lists.newArrayList(
            object.getType(), object.getName(), object.getClassName(), object.getDescription(),
            object.getArtifact().toString());
        }
      }).build();
    cliConfig.getTableRenderer().render(cliConfig, output, table);
  }

  @Override
  public String getPattern() {
    return String.format("list artifact plugins <%s> <%s> <%s> [<%s>]", ArgumentName.ARTIFACT_NAME,
      ArgumentName.ARTIFACT_VERSION, ArgumentName.PLUGIN_TYPE, ArgumentName.SCOPE);
  }

  @Override
  public String getDescription() {
    return String.format("Lists all plugins of a specific type available to a specific %s. " +
      "Returns the type, name, classname, and description of the plugin, as well as the %s the plugin came from. " +
      "If no scope is provided, %s are looked for first in the 'SYSTEM' and then in the 'USER' scope.",
      ElementType.ARTIFACT.getName(), ElementType.ARTIFACT.getName(), ElementType.ARTIFACT.getNamePlural());
  }
}
