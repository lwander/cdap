/*
 * Copyright © 2014-2015 Cask Data, Inc.
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

package co.cask.cdap.cli.command;

import co.cask.cdap.cli.CLIConfig;
import co.cask.cdap.cli.ElementType;
import co.cask.cdap.client.ProgramClient;
import com.google.common.collect.Lists;
import com.google.inject.Inject;
import io.cdap.common.cli.Command;
import io.cdap.common.cli.CommandSet;

import java.util.List;

/**
 * Contains commands for getting program runs.
 */
public class GetProgramRunsCommandSet extends CommandSet<Command> {

  @Inject
  public GetProgramRunsCommandSet(ProgramClient programClient, CLIConfig cliConfig) {
    super(generateCommands(programClient, cliConfig));
  }

  private static Iterable<Command> generateCommands(ProgramClient programClient, CLIConfig cliConfig) {
    List<Command> commands = Lists.newArrayList();
    for (ElementType elementType : ElementType.values()) {
      if (elementType.hasRuns()) {
        commands.add(new GetProgramRunsCommand(elementType, programClient, cliConfig));
      }
    }
    return commands;
  }
}
