/*
 * Copyright © 2018-2019 Cask Data, Inc.
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

package co.cask.cdap.config;

import co.cask.cdap.common.BadRequestException;
import co.cask.cdap.common.NotFoundException;
import co.cask.cdap.common.ProfileConflictException;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.internal.app.runtime.SystemArguments;
import co.cask.cdap.internal.app.store.profile.ProfileStore;
import co.cask.cdap.internal.profile.AdminEventPublisher;
import co.cask.cdap.messaging.MessagingService;
import co.cask.cdap.messaging.context.MultiThreadMessagingContext;
import co.cask.cdap.proto.EntityScope;
import co.cask.cdap.proto.element.EntityType;
import co.cask.cdap.proto.id.ApplicationId;
import co.cask.cdap.proto.id.EntityId;
import co.cask.cdap.proto.id.InstanceId;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.proto.id.NamespacedEntityId;
import co.cask.cdap.proto.id.ProfileId;
import co.cask.cdap.proto.id.ProgramId;
import co.cask.cdap.runtime.spi.profile.ProfileStatus;
import co.cask.cdap.spi.data.transaction.TransactionRunner;
import co.cask.cdap.spi.data.transaction.TransactionRunners;
import com.google.common.collect.Sets;
import com.google.inject.Inject;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * This class is to manage preference related functions. It will wrap the {@link PreferencesTable} operation
 * in transaction in each method
 */
public class PreferencesService {

  private final AdminEventPublisher adminEventPublisher;
  private final TransactionRunner transactionRunner;

  @Inject
  public PreferencesService(MessagingService messagingService,
                            CConfiguration cConf, TransactionRunner transactionRunner) {
    MultiThreadMessagingContext messagingContext = new MultiThreadMessagingContext(messagingService);
    this.adminEventPublisher = new AdminEventPublisher(cConf, messagingContext);
    this.transactionRunner = transactionRunner;
  }

  private Map<String, String> getConfigProperties(EntityId entityId) {
    return TransactionRunners.run(transactionRunner, context -> {
      return new PreferencesTable(context).getPreferences(entityId);
    });
  }

  private Map<String, String> getConfigResolvedProperties(EntityId entityId) {
    return TransactionRunners.run(transactionRunner, context -> {
      return new PreferencesTable(context).getResolvedPreferences(entityId);
    });
  }

  /**
   * Validate the profile status is enabled and set the preferences in same transaction
   */
  private void setConfig(EntityId entityId,
                         Map<String, String> propertyMap)
    throws NotFoundException, ProfileConflictException, BadRequestException {
    TransactionRunners.run(transactionRunner, context -> {
      ProfileStore profileStore = ProfileStore.get(context);
      PreferencesTable preferencesTable = new PreferencesTable(context);
      setConfig(profileStore, preferencesTable, entityId, propertyMap);
    }, NotFoundException.class, ProfileConflictException.class, BadRequestException.class);
  }

  /**
   * Validate the profile status is enabled and set the preferences
   */
  private void setConfig(ProfileStore profileStore, PreferencesTable preferencesTable, EntityId entityId,
                         Map<String, String> propertyMap)
    throws NotFoundException, ProfileConflictException, BadRequestException, IOException {

    boolean isInstanceLevel = entityId.getEntityType().equals(EntityType.INSTANCE);
    NamespaceId namespaceId = isInstanceLevel ?
      NamespaceId.SYSTEM : ((NamespacedEntityId) entityId).getNamespaceId();

    // validate the profile and publish the necessary metadata change if the profile exists in the property
    Optional<ProfileId> profile = SystemArguments.getProfileIdFromArgs(namespaceId, propertyMap);
    if (profile.isPresent()) {
      ProfileId profileId = profile.get();
      // if it is instance level set, the profile has to be in SYSTEM scope, so throw BadRequestException when
      // setting a USER scoped profile
      if (isInstanceLevel && !propertyMap.get(SystemArguments.PROFILE_NAME).startsWith(EntityScope.SYSTEM.name())) {
        throw new BadRequestException(String.format("Cannot set profile %s at the instance level. " +
                                                      "Only system profiles can be set at the instance level. " +
                                                      "The profile property must look like SYSTEM:[profile-name]",
                                                    propertyMap.get(SystemArguments.PROFILE_NAME)));
      }

      if (profileStore.getProfile(profileId).getStatus() == ProfileStatus.DISABLED) {
        throw new ProfileConflictException(String.format("Profile %s in namespace %s is disabled. It cannot be " +
                                                           "assigned to any programs or schedules",
                                                         profileId.getProfile(), profileId.getNamespace()),
                                           profileId);
      }

    }

    // need to get old property and check if it contains profile information
    Map<String, String> oldProperties = preferencesTable.getPreferences(entityId);
    // get the old profile information from the previous properties
    Optional<ProfileId> oldProfile = SystemArguments.getProfileIdFromArgs(namespaceId, oldProperties);
    long seqId = preferencesTable.setPreferences(entityId, propertyMap);

    // After everything is set, publish the update message and add the association if profile is present
    if (profile.isPresent()) {
      profileStore.addProfileAssignment(profile.get(), entityId);
    }

    // if old properties has the profile, remove the association
    if (oldProfile.isPresent()) {
      profileStore.removeProfileAssignment(oldProfile.get(), entityId);
    }

    // if new profiles do not have profile information but old profiles have, it is same as deletion of the profile
    if (profile.isPresent()) {
      adminEventPublisher.publishProfileAssignment(entityId, seqId);
    } else if (oldProfile.isPresent()) {
      adminEventPublisher.publishProfileUnAssignment(entityId, seqId);
    }
  }

  private void deleteConfig(EntityId entityId) {
    TransactionRunners.run(transactionRunner, context -> {
      PreferencesTable dataset = new PreferencesTable(context);
      Map<String, String> oldProp = dataset.getPreferences(entityId);
      NamespaceId namespaceId = entityId.getEntityType().equals(EntityType.INSTANCE) ?
        NamespaceId.SYSTEM : ((NamespacedEntityId) entityId).getNamespaceId();
      Optional<ProfileId> oldProfile = SystemArguments.getProfileIdFromArgs(namespaceId, oldProp);
      long seqId = dataset.deleteProperties(entityId);

      // if there is profile properties, publish the message to update metadata and remove the assignment
      if (oldProfile.isPresent()) {
        ProfileStore.get(context).removeProfileAssignment(oldProfile.get(), entityId);
        adminEventPublisher.publishProfileUnAssignment(entityId, seqId);
      }
    });
  }

  /**
   * Get instance level preferences
   */
  public Map<String, String> getProperties() {
    return getConfigProperties(new InstanceId(""));
  }

  /**
   * Get namespace level preferences
   */
  public Map<String, String> getProperties(NamespaceId namespaceId) {
    return getConfigProperties(namespaceId);
  }

  /**
   * Get app level preferences
   */
  public Map<String, String> getProperties(ApplicationId applicationId) {
    return getConfigProperties(applicationId);
  }

  /**
   * Get program level preferences
   */
  public Map<String, String> getProperties(ProgramId programId) {
    return getConfigProperties(programId);
  }

  /**
   * Get instance level resolved preferences
   */
  public Map<String, String> getResolvedProperties() {
    return getConfigResolvedProperties(new InstanceId(""));
  }

  /**
   * Get namespace level resolved preferences
   */
  public Map<String, String> getResolvedProperties(NamespaceId namespaceId) {
    return getConfigResolvedProperties(namespaceId);
  }

  /**
   * Get app level resolved preferences
   */
  public Map<String, String> getResolvedProperties(ApplicationId appId) {
    return getConfigResolvedProperties(appId);
  }

  /**
   * Get program level resolved preferences
   */
  public Map<String, String> getResolvedProperties(ProgramId programId) {
   return getConfigResolvedProperties(programId);
  }

  /**
   * Set instance level preferences
   */
  public void setProperties(Map<String, String> propMap)
    throws NotFoundException, ProfileConflictException, BadRequestException {
    setConfig(new InstanceId(""), propMap);
  }

  /**
   * Set instance level preferences if they are not already set. Only adds the properties that don't already exist.
   *
   * @param properties the preferences to add
   * @return the preference keys that were added
   */
  public Set<String> addProperties(Map<String, String> properties)
    throws NotFoundException, ProfileConflictException, BadRequestException {
    InstanceId instanceId = new InstanceId("");

    Set<String> added = new HashSet<>();
    TransactionRunners.run(transactionRunner, context -> {
      ProfileStore profileStore = ProfileStore.get(context);
      PreferencesTable preferencesTable = new PreferencesTable(context);
      Map<String, String> oldProperties = preferencesTable.getPreferences(instanceId);
      Map<String, String> newProperties = new HashMap<>(properties);

      added.addAll(Sets.difference(newProperties.keySet(), oldProperties.keySet()));
      newProperties.putAll(oldProperties);

      setConfig(profileStore, preferencesTable, instanceId, newProperties);
    }, NotFoundException.class, ProfileConflictException.class, BadRequestException.class);
    return added;
  }

  /**
   * Set namespace level preferences
   */
  public void setProperties(NamespaceId namespaceId, Map<String, String> propMap)
    throws NotFoundException, ProfileConflictException, BadRequestException {
    setConfig(namespaceId, propMap);
  }

  /**
   * Set app level preferences
   */
  public void setProperties(ApplicationId appId, Map<String, String> propMap)
    throws NotFoundException, ProfileConflictException, BadRequestException {
    setConfig(appId, propMap);
  }

  /**
   * Set program level preferences
   */
  public void setProperties(ProgramId programId, Map<String, String> propMap)
    throws NotFoundException, ProfileConflictException, BadRequestException {
    setConfig(programId, propMap);
  }

  /**
   * Delete instance level preferences
   */
  public void deleteProperties() {
    deleteConfig(new InstanceId(""));
  }

  /**
   * Delete namespace level preferences
   */
  public void deleteProperties(NamespaceId namespaceId) {
    deleteConfig(namespaceId);
  }

  /**
   * Delete app level preferences
   */
  public void deleteProperties(ApplicationId appId) {
    deleteConfig(appId);
  }

  /**
   * Delete program level preferences
   */
  public void deleteProperties(ProgramId programId) {
    deleteConfig(programId);
  }
}
