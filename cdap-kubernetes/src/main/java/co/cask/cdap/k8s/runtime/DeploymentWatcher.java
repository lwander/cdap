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

package co.cask.cdap.k8s.runtime;

import co.cask.cdap.k8s.common.AbstractWatcherThread;
import co.cask.cdap.k8s.common.ResourceChangeListener;
import com.squareup.okhttp.Call;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.AppsV1Api;
import io.kubernetes.client.models.V1Deployment;
import io.kubernetes.client.util.Config;
import org.apache.twill.common.Cancellable;

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * A thread for monitoring deployment state change.
 */
public final class DeploymentWatcher extends AbstractWatcherThread<V1Deployment> {

  private final String selector;
  private final Queue<ResourceChangeListener<V1Deployment>> listeners;
  private volatile AppsV1Api appsApi;

  public DeploymentWatcher(String namespace, String selector) {
    super("kube-run-watcher", namespace);
    setDaemon(true);
    this.selector = selector;
    this.listeners = new ConcurrentLinkedQueue<>();
  }

  public Cancellable addListener(ResourceChangeListener<V1Deployment> listener) {
    // Wrap the listener for removal
    ResourceChangeListener<V1Deployment> wrappedListener = wrapListener(listener);
    listeners.add(wrappedListener);
    resetWatch();
    return () -> listeners.remove(wrappedListener);
  }

  @Nullable
  @Override
  protected String getSelector() {
    return selector;
  }

  @Override
  public void resourceAdded(V1Deployment deployment) {
    listeners.forEach(l -> l.resourceAdded(deployment));
  }

  @Override
  public void resourceModified(V1Deployment deployment) {
    listeners.forEach(l -> l.resourceModified(deployment));
  }

  @Override
  public void resourceDeleted(V1Deployment deployment) {
    listeners.forEach(l -> l.resourceDeleted(deployment));
  }

  @Override
  protected Call createCall(String namespace, @Nullable String labelSelector) throws IOException, ApiException {
    return getAppsApi().listNamespacedDeploymentCall(namespace, null, null, null, null, labelSelector,
                                                     null, null, null, true, null, null);
  }

  @Override
  protected ApiClient getApiClient() throws IOException {
    return getAppsApi().getApiClient();
  }

  /**
   * Returns a {@link AppsV1Api} instance for interacting with the API server.
   *
   * @throws IOException if exception was raised during creation of {@link AppsV1Api}
   */
  private AppsV1Api getAppsApi() throws IOException {
    AppsV1Api api = appsApi;
    if (api != null) {
      return api;
    }

    synchronized (this) {
      api = appsApi;
      if (api != null) {
        return api;
      }

      ApiClient client = Config.defaultClient();

      // Set a reasonable timeout for the watch.
      client.getHttpClient().setReadTimeout(5, TimeUnit.MINUTES);

      appsApi = api = new AppsV1Api(client);
      return api;
    }
  }

  private ResourceChangeListener<V1Deployment> wrapListener(ResourceChangeListener<V1Deployment> listener) {
    return new ResourceChangeListener<V1Deployment>() {

      @Override
      public void resourceAdded(V1Deployment resource) {
        listener.resourceAdded(resource);
      }

      @Override
      public void resourceModified(V1Deployment resource) {
        listener.resourceModified(resource);
      }

      @Override
      public void resourceDeleted(V1Deployment resource) {
        listener.resourceDeleted(resource);
      }
    };
  }
}
