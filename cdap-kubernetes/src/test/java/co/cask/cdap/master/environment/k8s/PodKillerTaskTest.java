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

import org.junit.Ignore;
import org.junit.Test;

import java.util.Collections;

/**
 * Unit test for {@link PodKillerTask}. The test is disabled by default since it requires a running
 * kubernetes cluster for the test to run. This class is kept for development purpose.
 */
@Ignore
public class PodKillerTaskTest {

  @Test
  public void test() {
    PodKillerTask task = new PodKillerTask("default", "cdap.container=preview", 1000);
    task.run(Collections::emptyMap);
  }
}
