/*
 * Copyright © 2015 Cask Data, Inc.
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

import co.cask.cdap.cli.ArgumentName;
import co.cask.cdap.cli.CLIConfig;
import co.cask.cdap.cli.ElementType;
import co.cask.cdap.cli.english.Article;
import co.cask.cdap.cli.english.Fragment;
import co.cask.cdap.cli.util.AbstractAuthCommand;
import co.cask.cdap.client.ArtifactClient;
import co.cask.cdap.proto.id.ArtifactId;
import com.google.inject.Inject;
import io.cdap.common.cli.Arguments;

import java.io.PrintStream;

/**
 * Deletes an artifact.
 */
public class DeleteArtifactCommand extends AbstractAuthCommand {

  private final ArtifactClient artifactClient;

  @Inject
  public DeleteArtifactCommand(ArtifactClient artifactClient, CLIConfig cliConfig) {
    super(cliConfig);
    this.artifactClient = artifactClient;
  }

  @Override
  public void perform(Arguments arguments, PrintStream output) throws Exception {

    String artifactName = arguments.get(ArgumentName.ARTIFACT_NAME.toString());
    String artifactVersion = arguments.get(ArgumentName.ARTIFACT_VERSION.toString());
    ArtifactId artifactId = cliConfig.getCurrentNamespace().artifact(artifactName, artifactVersion);

    artifactClient.delete(artifactId);

    output.printf("Successfully deleted artifact\n");
  }

  @Override
  public String getPattern() {
    return String.format("delete artifact <%s> <%s>", ArgumentName.ARTIFACT_NAME, ArgumentName.ARTIFACT_VERSION);
  }

  @Override
  public String getDescription() {
    return String.format("Deletes %s", Fragment.of(Article.A, ElementType.ARTIFACT.getName()));
  }
}
