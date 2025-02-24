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

package net.fake.test.app;

import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.proto.ProgramRunStatus;
import co.cask.cdap.test.ApplicationManager;
import co.cask.cdap.test.ServiceManager;
import co.cask.cdap.test.SlowTests;
import co.cask.cdap.test.TestBase;
import co.cask.cdap.test.TestConfiguration;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import io.cdap.common.http.HttpRequest;
import io.cdap.common.http.HttpRequests;
import io.cdap.common.http.HttpResponse;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.util.concurrent.TimeUnit;

/**
 * Tests bundle jar feature, in which the application jar contains
 * its dependency jars inside the "/lib" folder within the application jar.
 */
@Category(SlowTests.class)
public class TestBundleJarApp extends TestBase {

  @ClassRule
  public static final TestConfiguration CONFIG = new TestConfiguration(Constants.Explore.EXPLORE_ENABLED, false);

  @Test
  public void testBundleJar() throws Exception {
    File helloWorldJar = new File(TestBundleJarApp.class.getClassLoader().getResource("helloworld.jar").toURI());
    ApplicationManager applicationManager = deployApplication(BundleJarApp.class, helloWorldJar);

    ServiceManager serviceManager = applicationManager.getServiceManager("SimpleWrite").start();
    serviceManager.waitForRun(ProgramRunStatus.RUNNING, 10, TimeUnit.SECONDS);
    URL serviceURL = serviceManager.getServiceURL(5, TimeUnit.SECONDS);
    for (int i = 0; i < 5; i++) {
      URL url = new URL(serviceURL, "put/test" + i);
      HttpResponse response = HttpRequests.execute(HttpRequest.put(url).withBody(Integer.toString(i)).build());
      Assert.assertEquals(200, response.getResponseCode());
    }
    serviceManager.stop();

    // Query the result
    serviceManager = applicationManager.getServiceManager("SimpleGetInput").start();
    serviceManager.waitForRun(ProgramRunStatus.RUNNING, 10, TimeUnit.SECONDS);

    // Verify the query result
    String queryResult = callServiceGet(serviceManager.getServiceURL(), "/get/test1");
    String expectedQueryResult = new Gson().toJson(
      ImmutableMap.of("test1", "1" + BundleJarApp.EXPECTED_LOAD_TEST_CLASSES_OUTPUT));
    Assert.assertEquals(expectedQueryResult, queryResult);
    serviceManager.stop();

    serviceManager = applicationManager.getServiceManager("PrintService").start();
    serviceManager.waitForRun(ProgramRunStatus.RUNNING, 10, TimeUnit.SECONDS);

    String helloWorldClassName = "hello.HelloWorld";
    String result = callServiceGet(serviceManager.getServiceURL(), "/load/" + helloWorldClassName);
    String expected = new Gson().toJson(
      ImmutableMap.of("Class.forName", helloWorldClassName));
    Assert.assertEquals(expected, result);
  }

  private String callServiceGet(URL serviceURL, String path) throws IOException {
    URLConnection connection = new URL(serviceURL.toString() + path).openConnection();
    try (
      BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), Charsets.UTF_8))
    ) {
      return reader.readLine();
    }
  }
}
