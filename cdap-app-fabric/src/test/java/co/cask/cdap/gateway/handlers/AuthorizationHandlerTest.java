/*
 * Copyright © 2015-2019 Cask Data, Inc.
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

package co.cask.cdap.gateway.handlers;

import co.cask.cdap.client.AuthorizationClient;
import co.cask.cdap.client.config.ClientConfig;
import co.cask.cdap.client.config.ConnectionConfig;
import co.cask.cdap.common.FeatureDisabledException;
import co.cask.cdap.common.NotFoundException;
import co.cask.cdap.common.UnauthenticatedException;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.http.AuthenticationChannelHandler;
import co.cask.cdap.common.http.CommonNettyHttpServiceBuilder;
import co.cask.cdap.proto.element.EntityType;
import co.cask.cdap.proto.id.DatasetId;
import co.cask.cdap.proto.id.EntityId;
import co.cask.cdap.proto.id.Ids;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.proto.security.Action;
import co.cask.cdap.proto.security.Authorizable;
import co.cask.cdap.proto.security.Principal;
import co.cask.cdap.proto.security.Privilege;
import co.cask.cdap.proto.security.Role;
import co.cask.cdap.security.auth.context.MasterAuthenticationContext;
import co.cask.cdap.security.authorization.AuthorizationContextFactory;
import co.cask.cdap.security.authorization.AuthorizerInstantiator;
import co.cask.cdap.security.authorization.InMemoryAuthorizer;
import co.cask.cdap.security.authorization.NoOpAuthorizationContextFactory;
import co.cask.cdap.security.spi.authorization.AlreadyExistsException;
import co.cask.cdap.security.spi.authorization.Authorizer;
import co.cask.cdap.security.spi.authorization.UnauthorizedException;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import io.cdap.http.ChannelPipelineModifier;
import io.cdap.http.NettyHttpService;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpRequest;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * Tests for {@link AuthorizationHandler}.
 */
public class AuthorizationHandlerTest {

  private static final String USERNAME_PROPERTY = "cdap.username";
  private static final AuthorizationContextFactory FACTORY = new NoOpAuthorizationContextFactory();

  private final Principal admin = new Principal("admin", Principal.PrincipalType.USER);
  private final Properties properties = new Properties();
  private final EntityId ns1 = new NamespaceId("ns1");
  private final EntityId ns2 = new NamespaceId("ns2");

  private NettyHttpService service;
  private AuthorizationClient client;

  @Before
  public void setUp() throws Exception {
    CConfiguration conf = CConfiguration.create();
    conf.setBoolean(Constants.Security.Authorization.ENABLED, true);
    conf.setBoolean(Constants.Security.ENABLED, true);
    properties.setProperty("superusers", admin.getName());
    final InMemoryAuthorizer auth = new InMemoryAuthorizer();
    auth.initialize(FACTORY.create(properties));
    service = new CommonNettyHttpServiceBuilder(conf, getClass().getSimpleName())
      .setHttpHandlers(new AuthorizationHandler(auth, new AuthorizerInstantiator(conf, FACTORY) {
        @Override
        public Authorizer get() {
          return auth;
        }
      }, conf, new MasterAuthenticationContext()))
      .setChannelPipelineModifier(new ChannelPipelineModifier() {
        @Override
        public void modify(ChannelPipeline pipeline) {
          pipeline.addBefore("dispatcher", "usernamesetter", new TestUserNameSetter());
          pipeline.addAfter("usernamesetter", "authenticator", new AuthenticationChannelHandler());
        }
      })
      .build();
    service.start();

    client = new AuthorizationClient(
      ClientConfig.builder()
        .setConnectionConfig(
          ConnectionConfig.builder()
            .setHostname(service.getBindAddress().getHostName())
            .setPort(service.getBindAddress().getPort())
            .setSSLEnabled(false)
            .build())
        .build());
    System.setProperty(USERNAME_PROPERTY, admin.getName());
  }

  @After
  public void tearDown() throws Exception {
    service.stop();
  }

  @Test
  public void testAuthenticationDisabled() throws Exception {
    CConfiguration cConf = CConfiguration.create();
    cConf.setBoolean(Constants.Security.ENABLED, false);
    cConf.setBoolean(Constants.Security.Authorization.ENABLED, true);
    testDisabled(cConf, FeatureDisabledException.Feature.AUTHENTICATION, Constants.Security.ENABLED);
  }

  @Test
  public void testAuthorizationDisabled() throws Exception {
    CConfiguration cConf = CConfiguration.create();
    cConf.setBoolean(Constants.Security.ENABLED, true);
    cConf.setBoolean(Constants.Security.Authorization.ENABLED, false);
    testDisabled(cConf, FeatureDisabledException.Feature.AUTHORIZATION, Constants.Security.Authorization.ENABLED);
  }

  private void testDisabled(CConfiguration cConf, FeatureDisabledException.Feature feature,
                            String configSetting) throws Exception {
    final InMemoryAuthorizer authorizer = new InMemoryAuthorizer();
    NettyHttpService service = new CommonNettyHttpServiceBuilder(cConf, getClass().getSimpleName())
      .setHttpHandlers(new AuthorizationHandler(
        authorizer, new AuthorizerInstantiator(cConf, FACTORY) {
        @Override
        public Authorizer get() {
          return authorizer;
        }
      }, cConf, new MasterAuthenticationContext()))
      .build();
    service.start();
    try {
      final AuthorizationClient client = new AuthorizationClient(
        ClientConfig.builder()
          .setConnectionConfig(
            ConnectionConfig.builder()
              .setHostname(service.getBindAddress().getHostName())
              .setPort(service.getBindAddress().getPort())
              .setSSLEnabled(false)
              .build())
          .build());

      final NamespaceId ns1 = Ids.namespace("ns1");
      final Role admins = new Role("admins");

      // Test that the right exception is thrown when any Authorization REST API is called with authorization disabled
      verifyFeatureDisabled(new DisabledFeatureCaller() {
        @Override
        public void call() throws Exception {
          client.grant(Authorizable.fromEntityId(ns1), admin, ImmutableSet.of(Action.READ));
        }
      }, feature, configSetting);

      verifyFeatureDisabled(new DisabledFeatureCaller() {
        @Override
        public void call() throws Exception {
          client.revoke(Authorizable.fromEntityId(ns1), admin, ImmutableSet.of(Action.READ));
        }
      }, feature, configSetting);

      verifyFeatureDisabled(new DisabledFeatureCaller() {
        @Override
        public void call() throws Exception {
          client.revoke(Authorizable.fromEntityId(ns1));
        }
      }, feature, configSetting);

      verifyFeatureDisabled(new DisabledFeatureCaller() {
        @Override
        public void call() throws Exception {
          client.listPrivileges(admin);
        }
      }, feature, configSetting);

      verifyFeatureDisabled(new DisabledFeatureCaller() {
        @Override
        public void call() throws Exception {
          client.addRoleToPrincipal(admins, admin);
        }
      }, feature, configSetting);

      verifyFeatureDisabled(new DisabledFeatureCaller() {
        @Override
        public void call() throws Exception {
          client.removeRoleFromPrincipal(admins, admin);
        }
      }, feature, configSetting);

      verifyFeatureDisabled(new DisabledFeatureCaller() {
        @Override
        public void call() throws Exception {
          client.createRole(admins);
        }
      }, feature, configSetting);

      verifyFeatureDisabled(new DisabledFeatureCaller() {
        @Override
        public void call() throws Exception {
          client.dropRole(admins);
        }
      }, feature, configSetting);

      verifyFeatureDisabled(new DisabledFeatureCaller() {
        @Override
        public void call() throws Exception {
          client.listAllRoles();
        }
      }, feature, configSetting);
    } finally {
      service.stop();
    }
  }

  @Test
  public void testRevokeEntityUserActions() throws Exception {
    // grant() and revoke(EntityId, String, Set<Action>)
    verifyAuthFailure(ns1, admin, Action.READ);

    client.grant(Authorizable.fromEntityId(ns1), admin, ImmutableSet.of(Action.READ));
    verifyAuthSuccess(ns1, admin, Action.READ);

    client.revoke(Authorizable.fromEntityId(ns1), admin, ImmutableSet.of(Action.READ));
    verifyAuthFailure(ns1, admin, Action.READ);
  }

  @Test
  public void testRevokeEntityUser() throws Exception {
    Principal adminGroup = new Principal("admin", Principal.PrincipalType.GROUP);
    Principal bob = new Principal("bob", Principal.PrincipalType.USER);

    // grant() and revoke(EntityId, String)
    client.grant(Authorizable.fromEntityId(ns1), adminGroup, ImmutableSet.of(Action.READ));
    client.grant(Authorizable.fromEntityId(ns1), bob, ImmutableSet.of(Action.READ));
    verifyAuthSuccess(ns1, adminGroup, Action.READ);
    verifyAuthSuccess(ns1, bob, Action.READ);

    client.revoke(Authorizable.fromEntityId(ns1), adminGroup, EnumSet.allOf(Action.class));
    verifyAuthFailure(ns1, adminGroup, Action.READ);
    verifyAuthSuccess(ns1, bob, Action.READ);
  }

  @Test
  public void testRevokeEntity() throws Exception {
    Principal adminGroup = new Principal("admin", Principal.PrincipalType.GROUP);
    Principal bob = new Principal("bob", Principal.PrincipalType.USER);

    // grant() and revoke(EntityId)
    client.grant(Authorizable.fromEntityId(ns1), adminGroup, ImmutableSet.of(Action.READ));
    client.grant(Authorizable.fromEntityId(ns1), bob, ImmutableSet.of(Action.READ));
    client.grant(Authorizable.fromEntityId(ns2), adminGroup, ImmutableSet.of(Action.READ));
    verifyAuthSuccess(ns1, adminGroup, Action.READ);
    verifyAuthSuccess(ns1, bob, Action.READ);
    verifyAuthSuccess(ns2, adminGroup, Action.READ);

    client.revoke(Authorizable.fromEntityId(ns1));
    verifyAuthFailure(ns1, adminGroup, Action.READ);
    verifyAuthFailure(ns1, bob, Action.READ);
    verifyAuthSuccess(ns2, adminGroup, Action.READ);
  }

  @Test
  public void testRBAC() throws Exception {
    Role admins = new Role("admins");
    Role engineers = new Role("engineers");
    // create a role
    client.createRole(admins);
    // add another role
    client.createRole(engineers);

    // listing role should show the added role
    Set<Role> roles = client.listAllRoles();
    Assert.assertEquals(Sets.newHashSet(admins, engineers), roles);

    // creating a role which already exists should throw an exception
    try {
      client.createRole(admins);
      Assert.fail(String.format("Created a role %s which already exists. Should have failed.", admins.getName()));
    } catch (AlreadyExistsException expected) {
      // expected
    }

    // drop an existing role
    client.dropRole(admins);

    // the list should not have the dropped role
    roles = client.listAllRoles();
    Assert.assertEquals(Sets.newHashSet(engineers), roles);

    // dropping a non-existing role should throw exception
    try {
      client.dropRole(admins);
      Assert.fail(String.format("Dropped a role %s which does not exists. Should have failed.", admins.getName()));
    } catch (co.cask.cdap.security.spi.authorization.NotFoundException expected) {
      // expected
    }

    // add an user to an existing role
    Principal spiderman = new Principal("spiderman", Principal.PrincipalType.USER);
    client.addRoleToPrincipal(engineers, spiderman);

    // add an user to an non-existing role should throw an exception
    try {
      client.addRoleToPrincipal(admins, spiderman);
      Assert.fail(String.format("Added role %s to principal %s. Should have failed.", admins, spiderman));
    } catch (co.cask.cdap.security.spi.authorization.NotFoundException expected) {
      // expected
    }

    // check listing roles for spiderman have engineers role
    Assert.assertEquals(Sets.newHashSet(engineers), client.listRoles(spiderman));

    // check that spiderman who has engineers roles cannot read from ns1
    verifyAuthFailure(ns1, spiderman, Action.READ);

    // give a permission to engineers role
    client.grant(Authorizable.fromEntityId(ns1), engineers, ImmutableSet.of(Action.READ));

    // check that a spiderman who has engineers role has access
    verifyAuthSuccess(ns1, spiderman, Action.READ);

    // list privileges for spiderman should have read action on ns1
    Assert.assertEquals(Sets.newHashSet(new Privilege(ns1, Action.READ)), client.listPrivileges(spiderman));

    // revoke action from the role
    client.revoke(Authorizable.fromEntityId(ns1), engineers, ImmutableSet.of(Action.READ));

    // now the privileges for spiderman should be empty
    Assert.assertEquals(new HashSet<>(), client.listPrivileges(spiderman));

    // check that the user of this role is not authorized to do the revoked operation
    verifyAuthFailure(ns1, spiderman, Action.READ);

    // remove an user from a existing role
    client.removeRoleFromPrincipal(engineers, spiderman);

    // check listing roles for spiderman should be empty
    Assert.assertEquals(new HashSet<>(), client.listRoles(spiderman));

    // remove an user from a non-existing role should throw exception
    try {
      client.removeRoleFromPrincipal(admins, spiderman);
      Assert.fail(String.format("Removed non-existing role %s from principal %s. Should have failed.", admins,
                                spiderman));
    } catch (co.cask.cdap.security.spi.authorization.NotFoundException expected) {
      // expected
    }
  }


  @Test
  public void testGrantOnNonExistingEntity() throws Exception {
    // should be able to grant on non existing entities
    client.grant(Authorizable.fromEntityId(Ids.namespace("ns3")), admin, ImmutableSet.of(Action.ADMIN));
    verifyAuthSuccess(Ids.namespace("ns3"), admin, Action.ADMIN);
  }

  @Test
  public void testRevokeOnNonExistingEntity()
    throws FeatureDisabledException, UnauthenticatedException, UnauthorizedException, IOException, NotFoundException {
    // revoke on non exiting entities should work fine
    client.revoke(Authorizable.fromEntityId(Ids.namespace("ns3")), admin, ImmutableSet.of(Action.ADMIN));
  }

  @Test
  public void testRevokeAllOnNonExistingEntity() throws Exception {
    // revoke on non existing entities should work fine
    client.revoke(Authorizable.fromEntityId(Ids.namespace("ns3")));
  }

  @Test
  public void testWildCardEntities() throws Exception {
    // in-mem authorizer does not support wildcard privileges so we can't actually enforce after grant.
    // this test is just to check that our grant apis work fine. After grant we list privileges to check for existence
    DatasetId datasetId = new DatasetId("ns", "wildcard");
    String wildcardEntityStar = datasetId.toString().replace("wildcard", "*");
    client.grant(Authorizable.fromString(wildcardEntityStar), admin, ImmutableSet.of(Action.ADMIN));
    String wildcardEntityQuestion = datasetId.toString().replace("wildcard", "someSt?");
    client.grant(Authorizable.fromString(wildcardEntityQuestion), admin, ImmutableSet.of(Action.ADMIN));

    Set<Privilege> privileges = client.listPrivileges(admin);
    Assert.assertTrue(privileges.contains(new Privilege(Authorizable.fromString(wildcardEntityStar), Action.ADMIN)));
    Assert.assertTrue(privileges.contains(new Privilege(Authorizable.fromString(wildcardEntityQuestion),
                                                        Action.ADMIN)));

    // revoke privilege from the wildcard entity and it should not exist anymore
    client.revoke(Authorizable.fromString(wildcardEntityQuestion), admin, ImmutableSet.of(Action.ADMIN));
    privileges = client.listPrivileges(admin);
    Assert.assertFalse(privileges.contains(new Privilege(Authorizable.fromString(wildcardEntityQuestion),
                                                         Action.ADMIN)));

    // revoke all should work too
    String authFromPrivilege = null;
    for (Privilege privilege : privileges) {
      if (privilege.getAuthorizable().getEntityType().equals(EntityType.DATASET)) {
        authFromPrivilege = privilege.getAuthorizable().toString();
      }
    }
    client.revoke(Authorizable.fromString(authFromPrivilege));
    privileges = client.listPrivileges(admin);
    Assert.assertFalse(privileges.contains(new Privilege(Authorizable.fromString(wildcardEntityStar), Action.ADMIN)));
  }

  /**
   * Interface to centralize testing the exception thrown when Authorization is disabled.
   */
  private interface DisabledFeatureCaller {
    void call() throws Exception;
  }

  /**
   * Calls a {@link DisabledFeatureCaller} and verifies that the right exception was thrown.
   *
   * @param caller the {@link DisabledFeatureCaller} that wraps the operation to test
   * @param expectedDisabledFeature the disabled feature
   * @param expectedEnableConfig the expected config setting to enable the disabled feature
   */
  private void verifyFeatureDisabled(DisabledFeatureCaller caller,
                                     FeatureDisabledException.Feature expectedDisabledFeature,
                                     String expectedEnableConfig) throws Exception {
    try {
      caller.call();
    } catch (FeatureDisabledException expected) {
      Assert.assertEquals(expectedDisabledFeature, expected.getFeature());
      Assert.assertEquals(FeatureDisabledException.CDAP_SITE, expected.getConfigName());
      Assert.assertEquals(expectedEnableConfig, expected.getEnableConfigKey());
      Assert.assertEquals("true", expected.getEnableConfigValue());
    }
  }

  private void verifyAuthSuccess(EntityId entity, Principal principal, Action action) throws Exception {
    Set<Privilege> privileges = client.listPrivileges(principal);
    Privilege privilegeToCheck = new Privilege(entity, action);
    Assert.assertTrue(
      String.format(
        "Expected principal %s to have the privilege %s, but found that it did not.", principal, privilegeToCheck
      ),
      privileges.contains(privilegeToCheck)
    );
  }

  private void verifyAuthFailure(EntityId entity, Principal principal, Action action) throws Exception {
    Set<Privilege> privileges = client.listPrivileges(principal);
    Privilege privilegeToCheck = new Privilege(entity, action);
    Assert.assertFalse(
      String.format(
        "Expected principal %s to not have the privilege %s, but found that it did.", principal, privilegeToCheck
      ),
      privileges.contains(privilegeToCheck)
    );
  }

  /**
   * Test {@link ChannelInboundHandlerAdapter} to set the username as an HTTP Header
   * {@link co.cask.cdap.common.conf.Constants.Security.Headers#USER_ID}. In production, this is done in the router by
   * SecurityAuthenticationHandler
   */
  private static final class TestUserNameSetter extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      if (msg instanceof HttpRequest) {
        HttpRequest request = (HttpRequest) msg;
        request.headers().set(Constants.Security.Headers.USER_ID, System.getProperty(USERNAME_PROPERTY));
      }

      ctx.fireChannelRead(msg);
    }
  }
}
