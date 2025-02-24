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

angular.module(PKG.name + '.commons')
  .controller('DAGPlusPlusCtrl', function MyDAGController(jsPlumb, $scope, $timeout, DAGPlusPlusFactory, GLOBALS, DAGPlusPlusNodesActionsFactory, $window, DAGPlusPlusNodesStore, $rootScope, $popover, uuid, DAGPlusPlusNodesDispatcher, NonStorePipelineErrorFactory, AvailablePluginsStore, myHelpers, HydratorPlusPlusCanvasFactory, HydratorPlusPlusConfigStore, HydratorPlusPlusPreviewActions, HydratorPlusPlusPreviewStore) {

    var vm = this;

    var dispatcher = DAGPlusPlusNodesDispatcher.getDispatcher();
    var undoListenerId = dispatcher.register('onUndoActions', resetEndpointsAndConnections);
    var redoListenerId = dispatcher.register('onRedoActions', resetEndpointsAndConnections);

    let localX, localY;

    const SHOW_METRICS_THRESHOLD = 0.8;

    const separation = $scope.separation || 200; // node separation length

    const nodeWidth = 200;
    const nodeHeight = 80;

    var dragged = false;

    vm.isDisabled = $scope.isDisabled;
    vm.disableNodeClick = $scope.disableNodeClick;

    var metricsPopovers = {};
    var selectedConnections = [];
    let conditionNodes = [];
    let normalNodes = [];
    let splitterNodesPorts = {};

    vm.pluginsMap = {};

    vm.scale = 1.0;

    vm.panning = {
      style: {
        'top': 0,
        'left': 0
      },
      top: 0,
      left: 0
    };

    vm.comments = [];
    vm.nodeMenuOpen = null;

    vm.selectedNode = null;

    var repaintTimeout,
        commentsTimeout,
        nodesTimeout,
        fitToScreenTimeout,
        initTimeout,
        metricsPopoverTimeout,
        resetTimeout;

    var Mousetrap = window.CaskCommon.Mousetrap;

    vm.selectNode = (event, node) => {
      if (vm.isDisabled) { return; }
      event.stopPropagation();
      clearConnectionsSelection();
      vm.selectedNode = node;
    };

    function repaintEverything() {
      if (repaintTimeout) {
        $timeout.cancel(repaintTimeout);
      }

      repaintTimeout = $timeout(function () { vm.instance.repaintEverything(); });
    }

    function init() {
      $scope.nodes = DAGPlusPlusNodesStore.getNodes();
      $scope.connections = DAGPlusPlusNodesStore.getConnections();
      vm.undoStates = DAGPlusPlusNodesStore.getUndoStates();
      vm.redoStates = DAGPlusPlusNodesStore.getRedoStates();
      vm.comments = DAGPlusPlusNodesStore.getComments();

      initTimeout = $timeout(function () {
        initNodes();
        addConnections();
        bindJsPlumbEvents();
        bindKeyboardEvents();

        if (vm.isDisabled) {
          disableAllEndpoints();
        }

        // Process metrics data
        if ($scope.showMetrics) {

          angular.forEach($scope.nodes, function (node) {
            var elem = angular.element(document.getElementById(node.name)).children();

            var scope = $rootScope.$new();
            scope.data = {
              node: node
            };
            scope.version = node.plugin.artifact.version;

            metricsPopovers[node.name] = {
              scope: scope,
              element: elem,
              popover: null,
              isShowing: false
            };

            $scope.$on('$destroy', function () {
              elem.remove();
              elem = null;
              scope.$destroy();
            });

          });

          $scope.$watch('metricsData', function () {
            if (Object.keys($scope.metricsData).length === 0) {
              angular.forEach(metricsPopovers, function (value) {
                value.scope.data.metrics = 0;
              });
            }

            angular.forEach($scope.metricsData, function (pluginMetrics, pluginName) {
              let metricsToDisplay = {};
              let pluginMetricsKeys = Object.keys(pluginMetrics);
              for (let i = 0; i < pluginMetricsKeys.length; i++) {
                let pluginMetric = pluginMetricsKeys[i];
                if (typeof pluginMetrics[pluginMetric] === 'object') {
                  metricsToDisplay[pluginMetric] = _.sum(Object.keys(pluginMetrics[pluginMetric]).map(key => pluginMetrics[pluginMetric][key]));
                } else {
                  metricsToDisplay[pluginMetric] = pluginMetrics[pluginMetric];
                }
              }

              metricsPopovers[pluginName].scope.data.metrics = metricsToDisplay;
            });
          }, true);
        }
      });

      // This is here because the left panel is initially in the minimized mode and expands
      // based on user setting on local storage. This is taking more than a single angular digest cycle
      // Hence the timeout to 1sec to render it in subsequent digest cycles.
      // FIXME: This directive should not be dependent on specific external component to render itself.
      // The left panel should default to expanded view and cleaning up the graph and fit to screen should happen in parallel.
      fitToScreenTimeout = $timeout(() => {
        vm.cleanUpGraph();
        vm.fitToScreen();
      }, 500);
    }

    function bindJsPlumbEvents() {
      vm.instance.bind('connection', addConnection);
      vm.instance.bind('connectionDetached', removeConnection);
      vm.instance.bind('connectionMoved', moveConnection);
      vm.instance.bind('beforeDrop', checkIfConnectionExistsOrValid);

      // jsPlumb docs say the event for clicking on an endpoint is called 'endpointClick',
      // but seems like the 'click' event is triggered both when clicking on an endpoint &&
      // clicking on a connection
      vm.instance.bind('click', toggleConnections);
    }

    function bindKeyboardEvents() {
      Mousetrap.bind(['command+z', 'ctrl+z'], vm.undoActions);
      Mousetrap.bind(['command+shift+z', 'ctrl+shift+z'], vm.redoActions);
      Mousetrap.bind(['del', 'backspace'], onKeyboardDelete);
      Mousetrap.bind(['command+c', 'ctrl+c'], onKeyboardCopy);
    }

    function unbindKeyboardEvents() {
      Mousetrap.unbind(['command+z', 'ctrl+z']);
      Mousetrap.unbind(['command+shift+z', 'ctrl+shift+z']);
      Mousetrap.unbind(['command+c', 'ctrl+c']);
      Mousetrap.unbind(['del', 'backspace']);
    }

    function closeMetricsPopover(node) {
      var nodeInfo = metricsPopovers[node.name];
      if (metricsPopoverTimeout) {
        $timeout.cancel(metricsPopoverTimeout);
      }
      if (nodeInfo && nodeInfo.popover) {
        nodeInfo.popover.hide();
        nodeInfo.popover.destroy();
        nodeInfo.popover = null;
      }
    }

    function onKeyboardDelete() {
      if (vm.selectedNode) {
        vm.onNodeDelete(null, vm.selectedNode);
      } else {
        vm.removeSelectedConnections();
      }
    }

    vm.nodeMouseEnter = function (node) {
      if (!$scope.showMetrics || vm.scale >= SHOW_METRICS_THRESHOLD) { return; }

      var nodeInfo = metricsPopovers[node.name];

      if (metricsPopoverTimeout) {
        $timeout.cancel(metricsPopoverTimeout);
      }

      if (nodeInfo.element && nodeInfo.scope) {
        nodeInfo.popover = $popover(nodeInfo.element, {
          trigger: 'manual',
          placement: 'auto right',
          target: angular.element(nodeInfo.element[0]),
          templateUrl: $scope.metricsPopoverTemplate,
          container: 'main',
          scope: nodeInfo.scope
        });
        nodeInfo.popover.$promise
          .then(function () {

            // Needs a timeout here to avoid showing popups instantly when just moving
            // cursor across a node
            metricsPopoverTimeout = $timeout(function () {
              if (nodeInfo.popover && typeof nodeInfo.popover.show === 'function') {
                nodeInfo.popover.show();
              }
            }, 500);
          });
      }
    };

    vm.nodeMouseLeave = function (node) {
      if (!$scope.showMetrics || vm.scale >= SHOW_METRICS_THRESHOLD) { return; }

      closeMetricsPopover(node);
    };

    vm.zoomIn = function () {
      vm.scale += 0.1;

      setZoom(vm.scale, vm.instance);
    };

    vm.zoomOut = function () {
      if (vm.scale <= 0.2) { return; }

      vm.scale -= 0.1;
      setZoom(vm.scale, vm.instance);
    };

    /**
     * Utily function from jsPlumb
     * https://jsplumbtoolkit.com/community/doc/zooming.html
     *
     * slightly modified to fit our needs
     **/
    function setZoom(zoom, instance, transformOrigin, el) {
      if ($scope.nodes.length === 0) { return; }

      transformOrigin = transformOrigin || [0.5, 0.5];
      instance = instance || jsPlumb;
      el = el || instance.getContainer();
      var p = ['webkit', 'moz', 'ms', 'o'],
          s = 'scale(' + zoom + ')',
          oString = (transformOrigin[0] * 100) + '% ' + (transformOrigin[1] * 100) + '%';

      for (var i = 0; i < p.length; i++) {
        el.style[p[i] + 'Transform'] = s;
        el.style[p[i] + 'TransformOrigin'] = oString;
      }

      el.style['transform'] = s;
      el.style['transformOrigin'] = oString;

      instance.setZoom(zoom);
    }

    function initNodes() {
      angular.forEach($scope.nodes, function (node) {
        if (node.type === 'condition') {
          initConditionNode(node.name);
        } else if (node.type === 'splittertransform') {
          initSplitterNode(node);
        } else {
          initNormalNode(node);
        }

        if (!vm.instance.isTarget(node.name)) {
          let targetOptions = Object.assign({}, vm.targetNodeOptions);
          if (node.type === 'alertpublisher') {
            targetOptions.scope = 'alertScope';
          } else if (node.type === 'errortransform') {
            targetOptions.scope = 'errorScope';
          }

          // Disabling the ability to disconnect a connection from target
          if (vm.isDisabled) {
            targetOptions.connectionsDetachable = false;
          }
          vm.instance.makeTarget(node.name, targetOptions);
        }
      });
    }

    function initNormalNode(node) {
      if (normalNodes.indexOf(node.name) !== -1) {
        return;
      }
      addEndpointForNormalNode('endpoint_' + node.name);
      if (!_.isEmpty(vm.pluginsMap) && !vm.isDisabled) {
        addErrorAlertEndpoints(node);
      }
      normalNodes.push(node.name);
    }

    function initConditionNode(nodeName) {
      if (conditionNodes.indexOf(nodeName) !== -1) {
        return;
      }
      addEndpointForConditionNode('endpoint_' + nodeName + '_condition_true', vm.conditionTrueEndpointStyle, 'yesLabel');
      addEndpointForConditionNode('endpoint_' + nodeName + '_condition_false', vm.conditionFalseEndpointStyle, 'noLabel');
      conditionNodes.push(nodeName);
    }

    function initSplitterNode(node) {
      if (!node.outputSchema || !Array.isArray(node.outputSchema) || (Array.isArray(node.outputSchema) && node.outputSchema[0].name === GLOBALS.defaultSchemaName)) {
        let splitterPorts = splitterNodesPorts[node.name];
        if (!_.isEmpty(splitterPorts)) {
          angular.forEach(splitterPorts, (port) => {
            let portElId = 'endpoint_' + node.name + '_port_' + port;
            deleteEndpoints(portElId);
          });
          DAGPlusPlusNodesActionsFactory.setConnections($scope.connections);
          delete splitterNodesPorts[node.name];
        }
        return;
      }

      let newPorts = node.outputSchema
        .map(schema => schema.name);

      let splitterPorts = splitterNodesPorts[node.name];

      let portsChanged = !_.isEqual(splitterPorts, newPorts);

      if (!portsChanged) {
        return;
      }

      angular.forEach(splitterPorts, (port) => {
        let portElId = 'endpoint_' + node.name + '_port_' + port;
        deleteEndpoints(portElId);
      });

      angular.forEach(node.outputSchema, (outputSchema) => {
        addEndpointForSplitterNode('endpoint_' + node.name + '_port_' + outputSchema.name);
      });

      DAGPlusPlusNodesActionsFactory.setConnections($scope.connections);
      splitterNodesPorts[node.name] = newPorts;
    }

    function addEndpointForNormalNode(endpointDOMId, customConfig) {
      let endpointDOMEl = document.getElementById(endpointDOMId);
      let endpointObj = Object.assign({}, { isSource: true }, customConfig);
      if (vm.isDisabled) {
        endpointObj.enabled = false;
      }
      let endpoint = vm.instance.addEndpoint(endpointDOMEl, endpointObj);
      addListenersForEndpoint(endpoint, endpointDOMEl);
    }

    function addEndpointForConditionNode(endpointDOMId, endpointStyle, overlayLabel) {
      let endpointDOMEl = document.getElementById(endpointDOMId);
      let newEndpoint = vm.instance.addEndpoint(endpointDOMEl, endpointStyle);
      newEndpoint.hideOverlay(overlayLabel);
      addListenersForEndpoint(newEndpoint, endpointDOMEl, overlayLabel);
    }

    function addEndpointForSplitterNode(endpointDOMId) {
      let endpointDOMEl = document.getElementsByClassName(endpointDOMId);
      endpointDOMEl = endpointDOMEl[endpointDOMEl.length - 1];

      let splitterEndpointStyleWithUUID = Object.assign({}, vm.splitterEndpointStyle, { uuid: endpointDOMId });
      let splitterEndpoint = vm.instance.addEndpoint(endpointDOMEl, splitterEndpointStyleWithUUID);
      addListenersForEndpoint(splitterEndpoint, endpointDOMEl);
    }

    function getPortEndpointClass(splitterNodeClassList) {
      let portElemClassList = [].slice.call(splitterNodeClassList);
      let portClass = _.find(portElemClassList, (className) => className.indexOf('_port_') !== -1);
      return portClass;
    }

    function addConnections() {
      angular.forEach($scope.connections, function (conn) {
        var sourceNode = $scope.nodes.find(node => node.name === conn.from);
        var targetNode = $scope.nodes.find(node => node.name === conn.to);

        if (!sourceNode || !targetNode) {
          return;
        }

        let connObj = {
          target: conn.to
        };

        if (conn.hasOwnProperty('condition')) {
          connObj.source = vm.instance.getEndpoints(`endpoint_${conn.from}_condition_${conn.condition}`)[0];
        } else if (conn.hasOwnProperty('port')) {
          connObj.source = vm.instance.getEndpoint(`endpoint_${conn.from}_port_${conn.port}`);
        } else if (targetNode.type === 'errortransform' || targetNode.type === 'alertpublisher') {
          if (!_.isEmpty(vm.pluginsMap) && !vm.isDisabled) {
            addConnectionToErrorsAlerts(conn, sourceNode, targetNode);
            return;
          }
        } else {
          connObj.source = vm.instance.getEndpoints(`endpoint_${conn.from}`)[0];
        }

        if (connObj.source && connObj.target) {
          let newConn = vm.instance.connect(connObj);
          if (
            targetNode.type === 'condition' ||
            sourceNode.type === 'action' ||
            targetNode.type === 'action' ||
            sourceNode.type === 'sparkprogram' ||
            targetNode.type === 'sparkprogram'
          ) {
            newConn.setType('dashed');
          }
        }
      });
    }

    function addErrorAlertEndpoints(node) {
      if (vm.shouldShowAlertsPort(node)) {
        addEndpointForNormalNode('endpoint_' + node.name + '_alert', vm.alertEndpointStyle);
      }
      if (vm.shouldShowErrorsPort(node)) {
        addEndpointForNormalNode('endpoint_' + node.name + '_error', vm.errorEndpointStyle);
      }
    }

    const addConnectionToErrorsAlerts = (conn, sourceNode, targetNode) => {
      let connObj = {
        target: conn.to
      };

      if (targetNode.type === 'errortransform' && vm.shouldShowErrorsPort(sourceNode)) {
        connObj.source = vm.instance.getEndpoints(`endpoint_${conn.from}_error`)[0];
      } else if (targetNode.type === 'alertpublisher' && vm.shouldShowAlertsPort(sourceNode)) {
        connObj.source = vm.instance.getEndpoints(`endpoint_${conn.from}_alert`)[0];
      } else {
        connObj.source = vm.instance.getEndpoints(`endpoint_${conn.from}`)[0];
        // this is for backwards compability with old pipelines where we don't specify
        // emit-alerts and emit-error in the plugin config yet. In those cases we should
        // still connect to the Error Collector/Alert Publisher using the normal endpoint
        let scopeString = vm.instance.getDefaultScope() + ' alertScope errorScope';
        connObj.source.scope = scopeString;
      }
      let defaultConnectorSettings = vm.defaultDagSettings.Connector;
      connObj.connector = [defaultConnectorSettings[0], Object.assign({}, defaultConnectorSettings[1], { midpoint: 0 })];

      vm.instance.connect(connObj);
    };

    function addErrorAlertsEndpointsAndConnections() {
      // Need the timeout because it takes an Angular tick for the Alert and Error port DOM elements
      // to show up after vm.pluginsMap is populated
      let addErrorAlertEndpointsTimeout = $timeout(() => {
        angular.forEach($scope.nodes, (node) => {
          addErrorAlertEndpoints(node);
        });
        vm.instance.unbind('connection');
        angular.forEach($scope.connections, (conn) => {
          var sourceNode = $scope.nodes.find(node => node.name === conn.from);
          var targetNode = $scope.nodes.find(node => node.name === conn.to);

          if (!sourceNode || !targetNode) {
            return;
          }

          if (targetNode.type === 'errortransform' || targetNode.type === 'alertpublisher') {
            addConnectionToErrorsAlerts(conn, sourceNode, targetNode);
          }
        });
        vm.instance.bind('connection', addConnection);
        repaintEverything();
        $timeout.cancel(addErrorAlertEndpointsTimeout);
      });
    }

    function transformCanvas (top, left) {
      const newTop = top + vm.panning.top;
      const newLeft = left + vm.panning.left;

      vm.setCanvasPanning(newTop, newLeft);
    }

    vm.setCanvasPanning = (top, left) => {
      vm.panning.top = top;
      vm.panning.left = left;

      vm.panning.style = {
        'top': vm.panning.top + 'px',
        'left': vm.panning.left + 'px'
      };
    };

    vm.handleCanvasClick = () => {
      vm.toggleNodeMenu();
      vm.selectedNode = null;
      vm.clearCommentSelection();
    };

    function addConnection(newConnObj) {
      let connection = {
        from: newConnObj.sourceId,
        to: newConnObj.targetId
      };

      let sourceIsCondition = newConnObj.sourceId.indexOf('_condition_') !== -1;
      let sourceIsPort = newConnObj.source.className.indexOf('_port_') !== -1;

      let sourceIdSplit;

      if (sourceIsPort) {
        let portClass = getPortEndpointClass(newConnObj.source.classList);
        // port endpoint marker is of the form "endpoint_UnionSplitter-9c1564a4-fc99-4927-bfc0-ef7a0bd607e1_port_string"
        // We need everything between endpoint and _port_ as node id
        // and type after _port_ as port name
        sourceIdSplit = portClass.split('_');
        connection.from = sourceIdSplit.slice(1, sourceIdSplit.length - 2).join('_');
        connection.port = sourceIdSplit[sourceIdSplit.length - 1];
      } else if (sourceIsCondition) {
        sourceIdSplit = newConnObj.sourceId.split('_');
        connection.from = sourceIdSplit.slice(1, sourceIdSplit.length - 2).join('_');
        connection.condition = sourceIdSplit[sourceIdSplit.length - 1];
      } else {
        // The regular endpoints are marked as "endpoint_nodename"
        // So this split just skips the "endpoint" and assigns the rest as nodeid.
        sourceIdSplit = newConnObj.sourceId.split('_').slice(1).join('_');
        connection.from = sourceIdSplit;
      }
      $scope.connections.push(connection);
      DAGPlusPlusNodesActionsFactory.setConnections($scope.connections);
    }

    function removeConnection(detachedConnObj, updateStore = true) {
      let connObj = Object.assign({}, detachedConnObj);
      /**
       * This is still not perfect. We shouldn't be splitting names by '_' and randomly take at index 1
       * Underscore is something that very common in names used by developers.
       *
       * FIXME: This needs a much bigger refactor
       */
      if (myHelpers.objectQuery(detachedConnObj, 'sourceId') && detachedConnObj.sourceId.indexOf('_condition_') !== -1) {
        // The regular endpoints are marked as "endpoint_nodename"
        // So this split just skips the "endpoint" and assigns the rest as nodeid.
        let conditionSplit = detachedConnObj.sourceId.split('_');
        connObj.sourceId = conditionSplit.slice(1, conditionSplit.length - 2).join('_');
      } else if (myHelpers.objectQuery(detachedConnObj, 'source', 'className') && detachedConnObj.source.className.indexOf('_port_') !== -1) {
        /**
         * The nodes are marked as endpoint_somename_port_nonnull
         * and endpoint_somename_port_null. So we need to remove the endpoint_ and _port_nonull
         * part from the label correctly.
         */
        let portClass = getPortEndpointClass(detachedConnObj.source.classList);
        let portSplit = portClass.split('_');
        connObj.sourceId = portSplit.slice(1, portSplit.length - 2).join('_');
      } else {
        // The regular endpoints are marked as "endpoint_nodename"
        // So this split just skips the "endpoint" and assigns the rest as nodeid.
        connObj.sourceId = detachedConnObj.sourceId.split('_').slice(1).join('_');
      }
      var connectionIndex = _.findIndex($scope.connections, function (conn) {
        return conn.from === connObj.sourceId && conn.to === connObj.targetId;
      });
      if (connectionIndex !== -1) {
        $scope.connections.splice(connectionIndex, 1);
      }
      if (updateStore) {
        DAGPlusPlusNodesActionsFactory.setConnections($scope.connections);
      }
    }

    function moveConnection(moveInfo) {
      let oldConnection = {
        sourceId: moveInfo.originalSourceId,
        targetId: moveInfo.originalTargetId
      };
      if (myHelpers.objectQuery(moveInfo, 'originalSourceEndpoint', 'element')) {
        oldConnection.source = moveInfo.originalSourceEndpoint.element;
      }
      if (myHelpers.objectQuery(moveInfo, 'originalTargetEndpoint', 'element')) {
        oldConnection.target = moveInfo.originalTargetEndpoint.element;
      }
      // don't need to call addConnection for the new connection, since that will be done
      // automatically as part of the 'connection' event
      removeConnection(oldConnection, false);
    }

    vm.removeSelectedConnections = function() {
      if (selectedConnections.length === 0 || vm.isDisabled) { return; }

      vm.instance.unbind('connectionDetached');
      angular.forEach(selectedConnections, function (selectedConnectionObj) {
        removeConnection(selectedConnectionObj, false);
        vm.instance.detach(selectedConnectionObj);
      });
      vm.instance.bind('connectionDetached', removeConnection);
      selectedConnections = [];
      DAGPlusPlusNodesActionsFactory.setConnections($scope.connections);
    };

    function toggleConnections(selectedObj) {
      if (vm.isDisabled) { return; }

      vm.selectedNode = null;

      // is connection
      if (selectedObj.sourceId && selectedObj.targetId) {
        toggleConnection(selectedObj);
        return;
      }

      if (!selectedObj.connections || !selectedObj.connections.length) {
        return;
      }

      // else is endpoint
      if (selectedObj.isTarget) {
        toggleConnection(selectedObj.connections[0]);
        return;
      }

      let connectionsToToggle = selectedObj.connections;

      let notYetSelectedConnections = _.difference(connectionsToToggle, selectedConnections);

      // This is to toggle all connections coming from an endpoint.
      // If zero, one or more (but not all) of the connections are already selected,
      // then just select the remaining ones. Else if they're all selected,
      // then unselect them.

      if (notYetSelectedConnections.length !== 0) {
        notYetSelectedConnections.forEach(connection => {
          selectedConnections.push(connection);
          connection.addType('selected');
        });
      } else {
        connectionsToToggle.forEach(connection => {
          selectedConnections.splice(selectedConnections.indexOf(connection), 1);
          connection.removeType('selected');
        });
      }
    }

    function toggleConnection(connObj) {
      vm.selectedNode = null;

      if (selectedConnections.indexOf(connObj) === -1) {
        selectedConnections.push(connObj);
      } else {
        selectedConnections.splice(selectedConnections.indexOf(connObj), 1);
      }
      connObj.toggleType('selected');
    }

    function clearConnectionsSelection() {
      selectedConnections.forEach((conn) => {
        conn.toggleType('selected');
      });

      selectedConnections = [];
    }

    function deleteEndpoints(elementId) {
      vm.instance.unbind('connectionDetached');
      let endpoint = vm.instance.getEndpoint(elementId);

      if (endpoint && endpoint.connections) {
        angular.forEach(endpoint.connections, (conn) => {
          removeConnection(conn, false);
          vm.instance.detach(conn);
        });
      }

      vm.instance.deleteEndpoint(endpoint);
      vm.instance.bind('connectionDetached', removeConnection);
    }

    function disableEndpoint(uuid) {
      let endpoint = vm.instance.getEndpoint(uuid);
      if (endpoint) {
        endpoint.setEnabled(false);
      }
    }

    function disableEndpoints(elementId) {
      let endpointArr = vm.instance.getEndpoints(elementId);

      if (endpointArr) {
        angular.forEach(endpointArr, (endpoint) => {
          endpoint.setEnabled(false);
        });
      }
    }

    function disableAllEndpoints() {
      angular.forEach($scope.nodes, function (node) {
        if (node.plugin.type === 'condition') {
          let endpoints = [`endpoint_${node.name}_condition_true`, `endpoint_${node.name}_condition_false`];
          angular.forEach(endpoints, (endpoint) => {
            disableEndpoints(endpoint);
          });
        } else if (node.plugin.type === 'splittertransform')  {
          let portNames = node.outputSchema.map(port => port.name);
          let endpoints = portNames.map(portName => `endpoint_${node.name}_port_${portName}`);
          angular.forEach(endpoints, (endpoint) => {
            // different from others because the name here is the uuid of the splitter endpoint,
            // not the id of DOM element
            disableEndpoint(endpoint);
          });
        } else {
          disableEndpoints('endpoint_' + node.name);
          if (vm.shouldShowAlertsPort(node)) {
            disableEndpoints('endpoint_' + node.name + '_alert');
          }
          if (vm.shouldShowErrorsPort(node)) {
            disableEndpoints('endpoint_' + node.name + '_error');
          }
        }
      });
    }

    function addHoverListener(endpoint, domCircleEl, labelId) {
      if (!domCircleEl.classList.contains('hover')) {
        domCircleEl.classList.add('hover');
      }
      if (labelId) {
        endpoint.showOverlay(labelId);
      }
    }

    function removeHoverListener(endpoint, domCircleEl, labelId) {
      if (domCircleEl.classList.contains('hover')) {
        domCircleEl.classList.remove('hover');
      }
      if (labelId) {
        endpoint.hideOverlay(labelId);
      }
    }

    function addListenersForEndpoint(endpoint, domCircleEl, labelId) {
      endpoint.canvas.removeEventListener('mouseover', addHoverListener);
      endpoint.canvas.removeEventListener('mouseout', removeHoverListener);
      endpoint.canvas.addEventListener('mouseover', addHoverListener.bind(null, endpoint, domCircleEl, labelId));
      endpoint.canvas.addEventListener('mouseout', removeHoverListener.bind(null, endpoint, domCircleEl, labelId));
    }

    function checkIfConnectionExistsOrValid(connObj) {
      // return false if connection already exists, which will prevent the connecton from being formed

      if (connObj.connection.source.className.indexOf('_port_') !== -1) {
        let portClass = getPortEndpointClass(connObj.connection.source.classList);
        /**
         * The nodes are marked as endpoint_somename_port_nonnull
         * and endpoint_somename_port_null. So we need to remove the endpoint_ and _port_nonull
         * part from the label correctly.
         */
        let portSplit = portClass.split('_');
        connObj.sourceId = portSplit.slice(1, portSplit.length - 2).join('_');
      } else if (connObj.sourceId.indexOf('_condition_') !== -1) {
        let conditionalSplit = connObj.sourceId.split('_');
        connObj.sourceId = conditionalSplit.slice(1, conditionalSplit.length - 2).join('_');
      } else {
        // The regular endpoints are marked as "endpoint_nodename"
        // So this split just skips the "endpoint" and assigns the rest as nodeid.
        connObj.sourceId = connObj.sourceId.split('_').slice(1).join('_');
      }

      var exists = _.find($scope.connections, function (conn) {
        return conn.from === connObj.sourceId && conn.to === connObj.targetId;
      });

      var sameNode = connObj.sourceId === connObj.targetId;

      if (exists || sameNode) {
        return false;
      }

      // else check if the connection is valid
      var sourceNode = $scope.nodes.find( node => node.name === connObj.sourceId);
      var targetNode = $scope.nodes.find( node => node.name === connObj.targetId);

      var valid = true;

      NonStorePipelineErrorFactory.connectionIsValid(sourceNode, targetNode, function(invalidConnection) {
        if (invalidConnection) { valid = false; }
      });

      if (!valid) {
        return valid;
      }

      // If valid, then modifies the look of the connection before showing it
      if (
        sourceNode.type === 'action' ||
        targetNode.type === 'action' ||
        sourceNode.type === 'sparkprogram' ||
        targetNode.type === 'sparkprogram'
      ) {
        connObj.connection.setType('dashed');
      } else if (sourceNode.type !== 'condition' && targetNode.type !== 'condition') {
        connObj.connection.setType('basic solid');
      } else {
        if (sourceNode.type === 'condition') {
          if (connObj.connection.endpoints && connObj.connection.endpoints.length > 0) {
            let sourceEndpoint = connObj.dropEndpoint;
            // TODO: simplify this?
            if (sourceEndpoint.canvas.classList.contains('condition-endpoint-true')) {
              connObj.connection.setType('conditionTrue');
            } else if (sourceEndpoint.canvas.classList.contains('condition-endpoint-false')) {
              connObj.connection.setType('conditionFalse');
            }
          }
        } else {
          connObj.connection.setType('basic');
        }
        if (targetNode.type === 'condition') {
          connObj.connection.addType('dashed');
        }
      }

      repaintEverything();
      return valid;
    }

    function resetEndpointsAndConnections() {
      if (resetTimeout) {
        $timeout.cancel(resetTimeout);
      }

      resetTimeout = $timeout(function () {
        vm.instance.reset();
        normalNodes = [];
        conditionNodes = [];
        splitterNodesPorts = {};

        $scope.nodes = DAGPlusPlusNodesStore.getNodes();
        $scope.connections = DAGPlusPlusNodesStore.getConnections();
        vm.undoStates = DAGPlusPlusNodesStore.getUndoStates();
        vm.redoStates = DAGPlusPlusNodesStore.getRedoStates();
        makeNodesDraggable();
        initNodes();
        addConnections();
        selectedConnections = [];
        bindJsPlumbEvents();

        if (commentsTimeout) {
          vm.comments = DAGPlusPlusNodesStore.getComments();
          $timeout.cancel(commentsTimeout);
        }

        commentsTimeout = $timeout(function () {
          makeCommentsDraggable();
        });
      });
    }

    function makeNodesDraggable() {
      if (vm.isDisabled) { return; }

      var nodes = document.querySelectorAll('.box');

      vm.instance.draggable(nodes, {
        start: function (drag) {
          let currentCoordinates = {
            x: drag.e.clientX,
            y: drag.e.clientY,
          };
          if (currentCoordinates.x === localX && currentCoordinates.y === localY) {
            return;
          }
          localX = currentCoordinates.x;
          localY = currentCoordinates.y;

          dragged = true;
        },
        stop: function (dragEndEvent) {
          var config = {
            _uiPosition: {
              top: dragEndEvent.el.style.top,
              left: dragEndEvent.el.style.left
            }
          };
          DAGPlusPlusNodesActionsFactory.updateNode(dragEndEvent.el.id, config);
        }
      });
    }

    function makeCommentsDraggable() {
      var comments = document.querySelectorAll('.comment-box');
      vm.instance.draggable(comments, {
        start: function () {
          dragged = true;
        },
        stop: function (dragEndEvent) {
          var config = {
            _uiPosition: {
              top: dragEndEvent.el.style.top,
              left: dragEndEvent.el.style.left
            }
          };
          DAGPlusPlusNodesActionsFactory.updateComment(dragEndEvent.el.id, config);
        }
      });
    }

    vm.selectEndpoint = function(event, node) {
      if (event.target.className.indexOf('endpoint-circle') === -1) { return; }
      vm.selectedNode = null;

      let sourceElem = node.name;
      let endpoints = vm.instance.getEndpoints(sourceElem);

      if (!endpoints) { return; }

      for (let i = 0; i < endpoints.length; i++) {
        let endpoint = endpoints[i];
        if (endpoint.connections && endpoint.connections.length > 0) {
          if (endpoint.connections[0].sourceId === node.name) {
            toggleConnections(endpoint);
            break;
          }
        }
      }
    };

    jsPlumb.ready(function() {
      var dagSettings = DAGPlusPlusFactory.getSettings();
      var {defaultDagSettings, defaultConnectionStyle, selectedConnectionStyle, dashedConnectionStyle, solidConnectionStyle, conditionTrueConnectionStyle, conditionTrueEndpointStyle, conditionFalseConnectionStyle, conditionFalseEndpointStyle, splitterEndpointStyle, alertEndpointStyle, errorEndpointStyle, targetNodeOptions} = dagSettings;
      vm.defaultDagSettings = defaultDagSettings;
      vm.conditionTrueEndpointStyle = conditionTrueEndpointStyle;
      vm.conditionFalseEndpointStyle = conditionFalseEndpointStyle;
      vm.splitterEndpointStyle = splitterEndpointStyle;
      vm.alertEndpointStyle = alertEndpointStyle;
      vm.errorEndpointStyle = errorEndpointStyle;
      vm.targetNodeOptions = targetNodeOptions;

      vm.instance = jsPlumb.getInstance(defaultDagSettings);
      vm.instance.registerConnectionType('basic', defaultConnectionStyle);
      vm.instance.registerConnectionType('selected', selectedConnectionStyle);
      vm.instance.registerConnectionType('dashed', dashedConnectionStyle);
      vm.instance.registerConnectionType('solid', solidConnectionStyle);
      vm.instance.registerConnectionType('conditionTrue', conditionTrueConnectionStyle);
      vm.instance.registerConnectionType('conditionFalse', conditionFalseConnectionStyle);

      init();

      // Making canvas draggable
      vm.secondInstance = jsPlumb.getInstance();
      if (!vm.disableNodeClick) {
        vm.secondInstance.draggable('diagram-container', {
          stop: function (e) {
            e.el.style.left = '0px';
            e.el.style.top = '0px';
            transformCanvas(e.pos[1], e.pos[0]);
            DAGPlusPlusNodesActionsFactory.resetPluginCount();
            DAGPlusPlusNodesActionsFactory.setCanvasPanning(vm.panning);
          }
        });
      }

      // doing this to listen to changes to just $scope.nodes instead of everything else
      $scope.$watch('nodes', function() {
        if (!vm.isDisabled) {
          if (nodesTimeout) {
            $timeout.cancel(nodesTimeout);
          }
          nodesTimeout = $timeout(function () {
            makeCommentsDraggable();
            makeNodesDraggable();
            initNodes();
          });
        }
      }, true);

      // This is needed to redraw connections and endpoints on browser resize
      angular.element($window).on('resize', vm.instance.repaintEverything);

      DAGPlusPlusNodesStore.registerOnChangeListener(function () {
        vm.activeNodeId = DAGPlusPlusNodesStore.getActiveNodeId();

        // can do keybindings only if no node is selected
        if (!vm.activeNodeId) {
          bindKeyboardEvents();
        } else {
          unbindKeyboardEvents();
        }
      });

    });

    vm.onPreviewData = function(event, node) {
      event.stopPropagation();
      HydratorPlusPlusPreviewStore.dispatch(HydratorPlusPlusPreviewActions.setPreviewData());
      DAGPlusPlusNodesActionsFactory.selectNode(node.name);
    };

    vm.onNodeClick = function(event, node) {
      closeMetricsPopover(node);

      window.CaskCommon.PipelineMetricsActionCreator.setMetricsTabActive(false);
      DAGPlusPlusNodesActionsFactory.selectNode(node.name);
    };

    vm.onMetricsClick = function(event, node, portName) {
      event.stopPropagation();
      if ($scope.disableMetricsClick) {
        return;
      }
      closeMetricsPopover(node);
      window.CaskCommon.PipelineMetricsActionCreator.setMetricsTabActive(true, portName);
      DAGPlusPlusNodesActionsFactory.selectNode(node.name);
    };

    vm.onNodeDelete = function (event, node) {
      if (event) {
        event.stopPropagation();
      }

      DAGPlusPlusNodesActionsFactory.removeNode(node.name);

      if (Object.keys(splitterNodesPorts).indexOf(node.name) !== -1) {
        delete splitterNodesPorts[node.name];
      }
      let nodeType = node.plugin.type || node.type;
      if (nodeType === 'splittertransform' && node.outputSchema && Array.isArray(node.outputSchema)) {
        let portNames = node.outputSchema.map(port => port.name);
        let endpoints = portNames.map(portName => `endpoint_${node.name}_port_${portName}`);
        angular.forEach(endpoints, (endpoint) => {
          deleteEndpoints(endpoint);
        });
      }

      vm.instance.unbind('connectionDetached');
      selectedConnections = selectedConnections.filter(function(selectedConnObj) {
        return selectedConnObj.sourceId !== node.name && selectedConnObj.targetId !== node.name;
      });
      vm.instance.unmakeTarget(node.name);

      vm.instance.remove(node.name);
      vm.instance.bind('connectionDetached', removeConnection);

      vm.selectedNode = null;
    };

    vm.cleanUpGraph = function () {
      if ($scope.nodes.length === 0) { return; }

      let newConnections = HydratorPlusPlusCanvasFactory.orderConnections($scope.connections, HydratorPlusPlusConfigStore.getAppType() || window.CaskCommon.PipelineDetailStore.getState().artifact.name, $scope.nodes);
      let connectionsSwapped = false;
      for (let i = 0; i < newConnections.length; i++) {
        if (newConnections[i].from !== $scope.connections[i].from || newConnections[i].to !== $scope.connections[i].to) {
          connectionsSwapped = true;
          break;
        }
      }

      if (connectionsSwapped) {
        $scope.connections = newConnections;
        DAGPlusPlusNodesActionsFactory.setConnections($scope.connections);
      }

      let graphNodesNetworkSimplex = DAGPlusPlusFactory.getGraphLayout($scope.nodes, $scope.connections, separation)._nodes;
      let graphNodesLongestPath = DAGPlusPlusFactory.getGraphLayout($scope.nodes, $scope.connections, separation, 'longest-path')._nodes;

      angular.forEach($scope.nodes, function (node) {
        let locationX = graphNodesNetworkSimplex[node.name].x;
        let locationY = graphNodesLongestPath[node.name].y;
        node._uiPosition = {
          left: locationX - 50 + 'px',
          top: locationY + 'px'
        };
      });

      $scope.getGraphMargins($scope.nodes);

      vm.panning.top = 0;
      vm.panning.left = 0;

      vm.panning.style = {
        'top': vm.panning.top + 'px',
        'left': vm.panning.left + 'px'
      };

      repaintEverything();

      DAGPlusPlusNodesActionsFactory.resetPluginCount();
      DAGPlusPlusNodesActionsFactory.setCanvasPanning(vm.panning);
    };

    vm.toggleNodeMenu = function (node, event) {
      if (event) {
        event.preventDefault();
        event.stopPropagation();
      }

      if (!node || vm.nodeMenuOpen === node.name) {
        vm.nodeMenuOpen = null;
      } else {
        vm.nodeMenuOpen = node.name;
        vm.selectedNode = node;
      }
    };

    // This algorithm is f* up
    vm.fitToScreen = function () {
      if ($scope.nodes.length === 0) { return; }

      /**
       * Need to find the furthest nodes:
       * 1. Left most nodes
       * 2. Right most nodes
       * 3. Top most nodes
       * 4. Bottom most nodes
       **/
      var minLeft = _.min($scope.nodes, function (node) {
        if (node._uiPosition.left.indexOf('vw') !== -1) {
          var left = parseInt(node._uiPosition.left, 10)/100 * document.documentElement.clientWidth;
          node._uiPosition.left = left + 'px';
        }
        return parseInt(node._uiPosition.left, 10);
      });
      var maxLeft = _.max($scope.nodes, function (node) {
        if (node._uiPosition.left.indexOf('vw') !== -1) {
          var left = parseInt(node._uiPosition.left, 10)/100 * document.documentElement.clientWidth;
          node._uiPosition.left = left + 'px';
        }
        return parseInt(node._uiPosition.left, 10);
      });

      var minTop = _.min($scope.nodes, function (node) {
        return parseInt(node._uiPosition.top, 10);
      });

      var maxTop = _.max($scope.nodes, function (node) {
        return parseInt(node._uiPosition.top, 10);
      });

      /**
       * Calculate the max width and height of the actual diagram by calculating the difference
       * between the furthest nodes
       **/
      var width = parseInt(maxLeft._uiPosition.left, 10) - parseInt(minLeft._uiPosition.left, 10) + nodeWidth;
      var height = parseInt(maxTop._uiPosition.top, 10) - parseInt(minTop._uiPosition.top, 10) + nodeHeight;

      var parent = $scope.element[0].parentElement.getBoundingClientRect();

      // margins from the furthest nodes to the edge of the canvas (75px each)
      var leftRightMargins = 150;
      var topBottomMargins = 150;

      // calculating the scales and finding the minimum scale
      var widthScale = (parent.width - leftRightMargins) / width;
      var heightScale = (parent.height - topBottomMargins) / height;

      vm.scale = Math.min(widthScale, heightScale);

      if (vm.scale > 1) {
        vm.scale = 1;
      }
      setZoom(vm.scale, vm.instance);


      // This will move all nodes by the minimum left and minimum top
      var offsetLeft = parseInt(minLeft._uiPosition.left, 10);
      angular.forEach($scope.nodes, function (node) {
        node._uiPosition.left = (parseInt(node._uiPosition.left, 10) - offsetLeft) + 'px';
      });

      var offsetTop = parseInt(minTop._uiPosition.top, 10);
      angular.forEach($scope.nodes, function (node) {
        node._uiPosition.top = (parseInt(node._uiPosition.top, 10) - offsetTop) + 'px';
      });

      $scope.getGraphMargins($scope.nodes);

      repaintEverything();

      vm.panning.left = 0;
      vm.panning.top = 0;

      vm.panning.style = {
        'top': vm.panning.top + 'px',
        'left': vm.panning.left + 'px'
      };

      DAGPlusPlusNodesActionsFactory.resetPluginCount();
      DAGPlusPlusNodesActionsFactory.setCanvasPanning(vm.panning);
    };

    vm.addComment = function () {
      var canvasPanning = DAGPlusPlusNodesStore.getCanvasPanning();

      var config = {
        content: '',
        isActive: false,
        id: 'comment-' + uuid.v4(),
        _uiPosition: {
          'top': 250 - canvasPanning.top + 'px',
          'left': (10/100 * document.documentElement.clientWidth) - canvasPanning.left + 'px'
        }
      };

      DAGPlusPlusNodesActionsFactory.addComment(config);
    };

    vm.clearCommentSelection = function clearCommentSelection() {
      angular.forEach(vm.comments, function (comment) {
        comment.isActive = false;
      });
    };

    vm.commentSelect = function (event, comment) {
      event.stopPropagation();
      vm.clearCommentSelection();

      if (dragged) {
        dragged = false;
        return;
      }

      comment.isActive = true;
    };

    vm.deleteComment = function (comment) {
      DAGPlusPlusNodesActionsFactory.deleteComment(comment);
    };

    vm.undoActions = function () {
      if (!vm.isDisabled && vm.undoStates.length > 0) {
        DAGPlusPlusNodesActionsFactory.undoActions();
      }
    };

    vm.redoActions = function () {
      if (!vm.isDisabled && vm.redoStates.length > 0) {
        DAGPlusPlusNodesActionsFactory.redoActions();
      }
    };

    vm.shouldShowAlertsPort = (node) => {
      let key = generatePluginMapKey(node);

      return myHelpers.objectQuery(vm.pluginsMap, key, 'widgets', 'emit-alerts');
    };

    vm.shouldShowErrorsPort = (node) => {
      let key = generatePluginMapKey(node);

      return myHelpers.objectQuery(vm.pluginsMap, key, 'widgets', 'emit-errors');
    };

    vm.onNodeCopy = (node) => {
      const config = {
        icon: node.icon,
        type: node.type,
        plugin: {
          name: node.plugin.name,
          artifact: node.plugin.artifact,
          properties: angular.copy(node.plugin.properties),
          label: node.plugin.label,
        },
      };

      vm.nodeMenuOpen = null;

      // The idea behind this clipboard object is to replicate the stage config as much as possible.
      // The roadmap is to be able to support copying multiple stages with their connections.
      const clipboardObj = {
        stages: [config]
      };

      const clipboardText = JSON.stringify(clipboardObj);

      const textArea = document.createElement('textarea');
      textArea.value = clipboardText;
      document.body.appendChild(textArea);
      textArea.select();
      document.execCommand('copy');
      document.body.removeChild(textArea);
    };

    function onKeyboardCopy() {
      if (!vm.selectedNode) { return; }

      vm.onNodeCopy(vm.selectedNode);
    }

    // handling node paste
    document.body.onpaste = (e) => {
      const activeNode = DAGPlusPlusNodesStore.getActiveNodeId();
      const target = myHelpers.objectQuery(e, 'target', 'tagName');
      const INVALID_TAG_NAME = ['INPUT', 'TEXTAREA'];

      if (activeNode || INVALID_TAG_NAME.indexOf(target) !== -1) {
        return;
      }

      let nodeText;
      if (window.clipboardData && window.clipboardData.getData) {
        // for IE......
        nodeText = window.clipboardData.getData('Text');
      } else {
        nodeText = e.clipboardData.getData('text/plain');
      }

      handleNodePaste(nodeText);
    };

    function handleNodePaste(text) {
      try {
        const config = JSON.parse(text);

        // currently only handling 1 node copy/paste
        const node = myHelpers.objectQuery(config, 'stages', 0);

        if (!node) { return; }

        // change name
        let newName = `copy ${node.plugin.label}`;
        const filteredNodes = HydratorPlusPlusConfigStore.getNodes()
          .filter(filteredNode => {
            return filteredNode.plugin.label ? filteredNode.plugin.label.startsWith(newName) : false;
          });

        newName = filteredNodes.length > 0 ? `${newName}${filteredNodes.length + 1}` : newName;

        node.plugin.label = newName;

        DAGPlusPlusNodesActionsFactory.addNode(node);
      } catch (e) {
        console.log('error parsing node config', e);
      }
    }

    // CUSTOM ICONS CONTROL
    function generatePluginMapKey(node) {
      let plugin = node.plugin;
      let type = node.type || plugin.type;

      return `${plugin.name}-${type}-${plugin.artifact.name}-${plugin.artifact.version}-${plugin.artifact.scope}`;
    }

    vm.shouldShowCustomIcon = (node) => {
      let key = generatePluginMapKey(node);

      let iconSourceType = myHelpers.objectQuery(vm.pluginsMap, key, 'widgets', 'icon', 'type');

      return ['inline', 'link'].indexOf(iconSourceType) !== -1;
    };

    vm.getCustomIconSrc = (node) => {
      let key = generatePluginMapKey(node);
      let iconSourceType = myHelpers.objectQuery(vm.pluginsMap, key, 'widgets', 'icon', 'type');

      if (iconSourceType === 'inline') {
        return myHelpers.objectQuery(vm.pluginsMap, key, 'widgets', 'icon', 'arguments', 'data');
      }

      return myHelpers.objectQuery(vm.pluginsMap, key, 'widgets', 'icon', 'arguments', 'url');
    };

    let subAvailablePlugins = AvailablePluginsStore.subscribe(() => {
      vm.pluginsMap = AvailablePluginsStore.getState().plugins.pluginsMap;
      if (!_.isEmpty(vm.pluginsMap)) {
        addErrorAlertsEndpointsAndConnections();
        subAvailablePlugins();
      }
    });

    $scope.$on('$destroy', function () {
      DAGPlusPlusNodesActionsFactory.resetNodesAndConnections();
      DAGPlusPlusNodesStore.reset();

      if (subAvailablePlugins) {
        subAvailablePlugins();
      }

      angular.element($window).off('resize', vm.instance.repaintEverything);

      // Cancelling all timeouts, key bindings and event listeners
      $timeout.cancel(repaintTimeout);
      $timeout.cancel(commentsTimeout);
      $timeout.cancel(nodesTimeout);
      $timeout.cancel(fitToScreenTimeout);
      $timeout.cancel(initTimeout);
      $timeout.cancel(metricsPopoverTimeout);
      Mousetrap.reset();
      dispatcher.unregister('onUndoActions', undoListenerId);
      dispatcher.unregister('onRedoActions', redoListenerId);
      vm.instance.reset();

      document.body.onpaste = null;
    });
  });
