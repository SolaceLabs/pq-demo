
// write to screen
function writeToScreen(str) {
  console.log(str);
  d3.select('#uses-box').append('p').html(str + '<br/><br/>');
}

var mqttConn = false;
var mqttConnTs = Date.now();
var sempConn = false;

function updateConnBox() {
  const mqttStr = mqttConn ? '<span style="color:#007700;">Connected</span>' : '<span style="color:#770000;">Disconnected</span>';
  const sempStr = sempConn ? '<span style="color:#007700;">Connected</span>' : '<span style="color:#770000;">Disconnected</span>';
  d3.select('#conn-box').html('<p>MQTT ' + mqttStr + '<br/>SEMP ' + sempStr + '</p>');
  if (mqttConn) {
    // d3.select('#button').style('disabled', false);
    document.getElementById("button-ctrl").disabled = false;
    document.getElementById("button-acl").disabled = false;
    // document.body.style.background = 'linear-gradient(-14deg, #fff, #fff 25%, #444 50%, #fff 75%)';
    if (sempConn) {
      document.body.style.backgroundImage = 'url(hexes.png)';
      document.body.style.backgroundRepeat = 'no-repeat';
      document.body.style.backgroundPosition = 'left top';
    } else {
      document.body.style.backgroundImage = '';
    }
    // document.body.style.backgroundColor = '#aaffff';
  } else {
    document.getElementById("button-ctrl").disabled = true;
    document.getElementById("button-acl").disabled = true;
    // document.body.style.background = 'repeating-linear-gradient(45deg, #ffcccc, #ffcccc 3px, #ffeeee 3px, #ffeeee 10px)';
    document.body.style.background = 'repeating-linear-gradient(45deg, #fdd, #fdd 3px, transparent 3px, transparent 10px), linear-gradient(to bottom, #fee, #fcc)';
  }
}
updateConnBox();


var ctrlSet = new Set();

function updateCtrlList() {
  var ctrlArray = [ 'control-all', 'control-pub-all', 'control-sub-all' ];
  for (let c of ctrlSet) {
    var levs = c.split("/");
    ctrlArray.push('control-' + levs[1]);
  }
  d3.select('#ctrltopic').selectAll('option').data(ctrlArray).enter().append('option');
  d3.select('#ctrltopic').selectAll('option').data(ctrlArray).text(d => d);
  d3.select('#ctrltopic').selectAll('option').data(ctrlArray).exit().remove();
}
updateCtrlList();

function sendCtrlMsgToTopic(topic) {
  if (!mqttConn) {
    log("Not connected to MQTT...");
    return;
  }
  log("### Publishing MQTT control msg to topic: " + topic);
  mqttClient.publish(topic);  // publish a quit message that only that subscriber is listening to

}

function sendCtrlMsg() {
  if (!mqttConn) {
    log("Not connected to MQTT...");
    return;
  }
  var who = d3.select('#ctrltopic').node().value;
  var cmd = d3.select('#ctrlcommand').node().value;
  var value = d3.select('#ctrlinput').node().value;
  if (value == null || value == "") {
    log('Must specify something in value!');
    return;
  }
  if (isNaN(value)) {  // ok, so not a number
    // only things: # of keys setting to 'max', and disp = agg|each
    // actually, don't worry about it... send anything... the apps will log
    // if (value.toLowerCase() != "max" || value.toLowerCase() != "agg" || value.toLowerCase() != "each") {
    //   log("Invalid value");
    //   return;
    // }
  }
  var topic = "pq-demo/" + who + "/" + cmd + "/" + value;
  // document.getElementById('ctrltopic').value = 'control-all';  // reset any per-client control back to main one
  sendCtrlMsgToTopic(topic);
}

var mqttClient;

function connectMqtt(url, user, pw) {
  const options = { username: user, password: pw };
  mqttClient = mqtt.connect(url, options);
  mqttClient.on('offline', function () {
    log("MQTT disconnected");
    mqttConn = false;
    updateConnBox();
  });
  mqttClient.on('connect', function () {
    log("MQTT connected");
    mqttConn = true;
    if (Date.now() - mqttConnTs > 5000) {  // been disconnected for at least 5 seconds!
      // log("TODO: SEMPV2 TO GET ALL PARTITION STATUS! And acquire new consumers");
      log("Have been disconnected from MQTT for > 5 seconds, using SEMPv2 to refresh partition client mappings")
      getQueueDetails(props.sempUrl + sempV2PartitionsSuffix.replace("{vpn}", props.vpn) + queueObj.name);
    }
    mqttConnTs = Date.now();
    updateConnBox();
    mqttClient.subscribe('$SYS/LOG/#', function (err) {
      if (err) {
        log("couldn't subscribe to logs-over-the-bus: $SYS/LOG/#");
        mqttConn = false;
        updateConnBox();
        mqttClient.end();
      }
    });
    mqttClient.subscribe('pq-demo/stats/#', function (err) {
      if (err) {
        log("couldn't subscribe to PQPub/PQSub stats: pq-demo/stats/#");
        mqttConn = false;
        updateConnBox();
        mqttClient.end();
      }
    });
    mqttClient.subscribe('pq-demo/event/#', function (err) {
      if (err) {
        log("couldn't subscribe to app events and flow events: pq-demo/event/#");
        mqttConn = false;
        updateConnBox();
        mqttClient.end();
      }
    });
  });
  mqttClient.on('error', function (err) {
    err('MQTT error: ' + err);
  });
  mqttClient.on('message', onMessage);
}

var subIndex = 1;
var pubIndex = 1;
var subMap = new Map();  // name -> object
var pubMap = new Map();  // name -> object
var oc = null;

// var pubMap = new Map();

var queueObj = {  // initialize later
  name: '',
  state: null,
  partitionCount: -1,
  topics: "?",
  type: "?",
}
var partitionMap = new Map();


function getBlankClient() {
  return {
    name: "",
    type: "",
    connected: false,
    bound: false,
    activeFlow: false,
    rate: 0,
    index: -1,
    curIndex: 0,
    lastTs: Date.now()
  }
}

function setNotActiveFlow(c) {
  c.activeFlow = false;
  c.flow = null;
  for (let p of partitionMap.values()) {
    if (p.client && p.client == c.name) {
      p.client = null;
    }
  }
}


var rebalanceTimer = null;


function onMessage(topic, message) {  // string, Buffer
  try {
    // console.log(topic);
    var levels = topic.split("/");
    if (topic.indexOf("$SYS/LOG") == 0) {  // this a log message
      if (topic.indexOf('#rest') == -1 && topic.indexOf('CLIENT_AD_PARTITIONED_QUEUE_ASSIGNED') == -1) {  // ignore rest, and we'll print part reassign later...
        log(topic);  // ignore REST connections coming and going
      }
      var payload = message.toString();
      if (payload.slice(-1) == '\0') {
        // err("NULL TERMIANTED");
        // var test = message.toString().slice(0,-1);
        // console.error(test);
        payload = payload.slice(0, -1);
      }
      // console.log(payload);
      var payloadWords = payload.split(" ");
      if (levels[5] == 'VPN_AD_QENDPT_DELETE') {
        console.log(payloadWords);
      }

      // CLIENT_CLIENT_CONNECT
      // CLIENT_CLIENT_NAME_CHANGE
      // CLIENT_CLIENT_OPEN_FLOW
      // CLIENT_CLIENT_BIND_SUCCESS
      // CLIENT_AD_PARTITIONED_QUEUE_ASSIGNED
      // VPN_AD_PARTITIONED_QUEUE_REBALANCE_STARTED
      // VPN_AD_PARTITIONED_QUEUE_REBALANCE_COMPLETED
      // CLIENT_CLIENT_CLOSE_FLOW
      // CLIENT_CLIENT_UNBIND
      // CLIENT_CLIENT_DISCONNECT
      // log("EVENT: " + levels[5]);
      // log("EVENT: " + payloadWords[4]);
      switch (levels[5]) {
        case "CLIENT_CLIENT_CONNECT":
        case "CLIENT_CLIENT_NAME_CHANGE": {
          var clientName = payloadWords[6];
          if (clientName.indexOf("pq/sub-"+queueObj.simpleName) == 0 || subMap.has(clientName)) {
            if (!subMap.has(clientName)) {  // new guy
              var newSub = getBlankClient();
              newSub.name = clientName;
              newSub.connected = true;
              newSub.type = "sub";
              newSub.index = subIndex++;
              newSub.curIndex = subMap.size;
              subMap.set(clientName, newSub);
              log("Addding new sub " + clientName);
              d3.select('#uses-box').style('visibility', 'hidden');
              enterNewSubs();
              updateSubStatus(newSub);
              updateSubsPos();
              updateLines();
            } else {  // it's already there, need update
              const client = subMap.get(clientName);
              if (client.timerHandle) {
                clearTimeout(client.timerHandle);
                client.timerHandle = null;
              }
              client.connected = true;
              client.bound = false;
              client.activeFlow = false;
              // updateSubsPos();
              updateSubStatus(client);
            }
          } else if (clientName.indexOf("pq/pub") == 0) {
            if (!pubMap.has(clientName)) {
              var newPub = getBlankClient();
              newPub.name = clientName;
              newPub.connected = true;
              newPub.type = "pub";
              newPub.index = pubIndex++;
              pubMap.set(clientName, newPub);
              log("Addding new pub " + clientName);
              enterNewPubs();
            } else {  // it's already there, need update
              pubMap.get(clientName).connected = true;
              if (pubMap.get(clientName).timerHandle) {
                clearTimeout(pubMap.get(clientName).timerHandle);
                pubMap.get(clientName).timerHandle = null;
              }
              log("Updating " + clientName);
            }
            d3.select('#uses-box').style('visibility', 'hidden');
            updatePubs();
          } else if (clientName.indexOf('oc') == 0) {
            if (oc) {
              log("Already tracking an order checker called " + oc.name);
              return;
            }
            d3.select('#uses-box').style('visibility', 'hidden');
            oc = getBlankClient();
            oc.name = clientName;
            oc.index = 1;  // one and only!
            oc.connected = true;
            oc.type = 'oc';
            enterNewOc();
            updateOc();
          } else {
            // log("Name not recognized: " + clientName);
          }
          break;
        }
        case "CLIENT_CLIENT_OPEN_FLOW": {
          var clientName = payloadWords[6];
          if (clientName.indexOf("pq/pub") == 0) {  // only publisher open a flow
            if (!pubMap.has(clientName)) {
              var newPub = getBlankClient();
              newPub.name = clientName;
              newPub.connected = true;
              newPub.activeFlow = true;
              newPub.type = "pub";
              newPub.index = pubIndex++;
              newPub.curIndex = subMap.size;
              pubMap.set(clientName, newPub);
              enterNewPubs();
            } else {  // it's already there, need update
              pubMap.get(clientName).activeFlow = true;
            }
            updatePubStatus(pubMap.get(clientName));
            updatePubs();
          }
          break;
        }
        case "CLIENT_CLIENT_BIND_SUCCESS": {
          // console.log(levels);
          // console.log(payloadWords);
          const clientName = payloadWords[6];
          const queueName = payloadWords[16];
          if (queueName == queueObj.name) {  // binding to right queue...
            // if (clientName.indexOf("pq-demo/sub") == 0 || subMap.has(clientName)) {  // only subscribers bind
            if (!subMap.has(clientName)) {  // don't add new subs here
              var newSub = getBlankClient();
              newSub.name = clientName;
              newSub.connected = true;
              newSub.bound = true;
              newSub.type = "sub";
              newSub.index = subIndex++;
              newSub.curIndex = subMap.size;
              subMap.set(clientName, newSub);
              log("Addding new sub " + clientName);
              enterNewSubs();
              updateSubStatus(newSub);
              updateSubsPos();
              updateLines();
            } else {  // it's already there, need update
              subMap.get(clientName).queueName = queueName;
              subMap.get(clientName).bound = true;
              updateSubStatus(subMap.get(clientName));
            }
          } else if (clientName.indexOf("pq/sub") == 0) {  // wrong queue
            if (!subMap.has(clientName)) {
              log("Ignoring client " + clientName + " who has bound to wrong queue: " + queueName);
            // } else {  // it's already there, need update
            //   subMap.get(clientName).bound = "wrong";
            //   updateSubStatus(subMap.get(clientName));
            }
          }
          break;
        }
        case "CLIENT_CLIENT_UNBIND": {
          var clientName = payloadWords[6];
          // if (clientName.indexOf("pq/sub") == 0) {
            if (subMap.has(clientName)) {
              const c = subMap.get(clientName);
              setNotActiveFlow(c);
              c.bound = false;
              c.rate = 0;
              updatePartitions();
              updateSubStatus(c);
              updateLines();
            }
          // }
          break;
        }
        case "CLIENT_CLIENT_CLOSE_FLOW": {  // ingress flow
          var clientName = payloadWords[6];
          if (clientName.indexOf("pq/pub") == 0) {  // only publisher open a flow
            if (pubMap.has(clientName)) {
              pubMap.get(clientName).activeFlow = false;
              updatePubStatus(pubMap.get(clientName));
              updatePubs();
            }
          }
          break;
        }
        case "CLIENT_CLIENT_DISCONNECT": {
          var clientName = payloadWords[6];
          if (subMap.has(clientName)) {
            const sub = subMap.get(clientName);
            sub.bound = false;
            sub.connected = false;
            sub.activeFlow = false;
            setNotActiveFlow(sub);
            sub.rate = 0;
            const msgRate = d3.select('#varsubrate' + sub.index);  // this is just for visuals...
            msgRate.interrupt("msg-count-update-sub-" + sub.name);
            msgRate.text('0');  // force to 0
            if (sub.timerHandle) {
              log('*********************************************************** this shouldnt happen');
            }
            var disconnectTimer = setTimeout(function () {
              if (subMap.has(clientName) && subMap.get(clientName).connected == false) {  // still disconnected, didn't come back
                const subCurIndex = subMap.get(clientName).curIndex;
                log('subscriber DISCONNECT timer firing, deleting sub ' + clientName);
                subMap.delete(clientName);
                rebalanceSubIndexes(subCurIndex);
                updateSubsPos();  // it will be disappearing now
                updateLines();
              }
            }, 20000);  // 20 seconds, expire a disconnected client
            sub.timerHandle = disconnectTimer;
            updateSubStatus(sub);
            updateLines();
          } else if (clientName.indexOf("pq/pub") == 0) {
            if (pubMap.has(clientName)) {
              pubMap.get(clientName).connected = false;
              pubMap.get(clientName).activeFlow = false;
              if (pubMap.get(clientName).timerHandle) {
                log('*********************************************************** this shouldnt happen');
              }
              var disconnectTimer = setTimeout(function () {
                if (pubMap.get(clientName) && pubMap.get(clientName).connected == false) {  // still disconnected, didn't come back
                  log('publisher DISCONNECT timer firing, deleting pub ' + clientName);
                  pubMap.delete(clientName);
                  updatePubs();
                }
              }, 10000);  // 10 seconds, expire a disconnected client
              pubMap.get(clientName).timerHandle = disconnectTimer;  // keep track of this timer so we can cancel it later
              d3.select('#varpubrate' + pubMap.get(clientName).index).text(d3.format('d')(0));  // force to 0
              updatePubStatus(pubMap.get(clientName));
              updatePubs();
            }
          } else if (clientName.indexOf('pq/oc') == 0) {
            if (oc.name == clientName) {
              // d3.transition("octrans").interrupt();
              d3.select('#oc').selectAll('div').data([oc]).interrupt('octrans');  // stop any bgcolor change transition
              oc.connected = false;
              updateOcStatus();
              updateOc();
              setTimeout(function () {
                if (oc.name == clientName && oc.connected == false) {  // still disconnected, didn't come back
                  log('OC DISCONNECT timer firing, deleting ' + clientName);
                  oc = null;
                  updateOc();
                }
              }, 3000);
            }
          }
          break;
        }
        case "CLIENT_AD_PARTITIONED_QUEUE_ASSIGNED": {
          // console.log(payloadWords);
          log('*** ' + payloadWords[4] + "      client name: " + payloadWords[6] + ",      queue: " + payloadWords[8] + ",        part: " + payloadWords[10]);
          const clientName = payloadWords[6];
          const queueName = payloadWords[8];
          const partitionNum = payloadWords[10];  // it's actually a String, like "3"
          if (queueName == queueObj.name) {  // don't do anything if this is not the right queue!
            if (!subMap.has(clientName)) {
              log("HAVEN'T SEEN BEFORE!!!  " + clientName);
              var newSub = getBlankClient();
              newSub.name = clientName;
              newSub.connected = true;
              newSub.bound = true;
              newSub.activeFlow = true;
              newSub.type = "sub";
              newSub.index = subIndex++;
              newSub.curIndex = subMap.size;
              subMap.set(clientName, newSub);
              log("Addding new sub " + clientName);
              enterNewSubs();
              updateSubStatus(newSub);
              updateSubsPos();
              updateLines();
            }
            if (!subMap.get(clientName).activeFlow) {
              subMap.get(clientName).activeFlow = true;  // it must since it just got assigned!!
              updateSubStatus(subMap.get(clientName));
            }
            if (!partitionMap.has(partitionNum)) {  // new partition
              var partObj = { index: partitionNum, client: clientName };
              partitionMap.set(partitionNum, partObj);
              queueObj.partitionCount = partitionMap.size;
              drawQueue();
            } else {  // reassigning!
              if (partitionMap.get(partitionNum).client != clientName) {
                var partObj = { index: partitionNum, client: clientName };
                partitionMap.set(partitionNum, partObj);  // replace it
              }
            }
            updatePartitions();
            updateLines();   // why is this commented out?
          }  // else wrong queue name, ignore
          break;
        }
        case "VPN_AD_PARTITIONED_QUEUE_REBALANCE_STARTED": {
          // console.log(payloadWords);
          const queueName = payloadWords[10];
          if (queueName == queueObj.name) {
            queueObj.state = 'REBALANCING';
            updateQueueStatus();
            updatePartitions();
            if (!rebalanceTimer) {
              const start = Date.now();
              rebalanceTimer = setInterval(function timer() {
                var secondsWithTenths = Math.floor((Date.now() - start) / 100) / 10;
                d3.select('#timer').html(secondsWithTenths);
                if (sempConn && queueObj.partitionRebalanceDelay) {
                  if (queueObj.state == 'REBALANCING' && secondsWithTenths > queueObj.partitionRebalanceDelay) {
                    queueObj.state = "HAND OFF";
                    updateQueueStatus();
                  }
                }
              }, 100);
            }
          } else {
            log('Ignoring rebalance, wrong queue');
          }
          break;
        }
        case "VPN_AD_PARTITIONED_QUEUE_REBALANCE_COMPLETED": {
          if (payloadWords[10] != queueObj.name) {
            log("Ignoring rebalance, wrong queue");
            return;
          }
          clearInterval(rebalanceTimer);
          rebalanceTimer = null;
          queueObj.state = null;
          // console.log(partitionMap);
          drawQueue();  // might need to redraw w/out SEMP and after partitions have been assigned
          updateQueueStatus();  // only want to update the queue status!!!!
          updatePartitions();
          updateLines();
          break;
        }
        default:
          break;
      }  // end of switch, end of logging topics
    } else if (topic.indexOf("pq-demo/event/FLOW_") == 0) {
      var clientName = levels.slice(3).join("/");
      if (clientName.indexOf("pq/sub") == 0) {  // only subscribers have flow active events!
        if (!subMap.has(clientName)) {
          // log("******************************* NEW FLOW ACTIVE FOR CLIENT I HAVEN'T SEENT!!! " + clientName);
          // var newSub = getBlankClient();
          // newSub.name = clientName;
          // newSub.connected = true;
          // newSub.bound = true;
          // newSub.activeFlow = true;
          // newSub.type = "sub";
          // newSub.index = subIndex++;
          // subMap.set(clientName, newSub);
        } else {  // it's already there, need update
          log("updating FLow for " + clientName + ": " + levels[2]);
          subMap.get(clientName).activeFlow = levels[2] == "FLOW_ACTIVE";
          subMap.get(clientName).flow = levels[2];
          updateSubStatus(subMap.get(clientName));
          updateLines();
        }
      }
    //   STATS!!!  //////////////////////////////////////////////////////////////////////////////
    } else if (topic.indexOf("pq-demo/stats/") == 0) {
      var payload = JSON.parse(message.toString());
      var clientName = levels.slice(2).join("/");
      d3.select('#uses-box').style('visibility', 'hidden');
      d3.select('#ctrl-box').style('visibility', 'visible');
      // console.log(clientName);
      if (clientName.indexOf("pq/sub-"+queueObj.simpleName) == 0) {
        if (!ctrlSet.has(clientName)) {
          d3.select('#uses-box').style('visibility', 'hidden');  // at least one pub, so hide the text
          ctrlSet.add(clientName);
          updateCtrlList();
        }
        if (!subMap.has(clientName)) { // && payload.queueName == queueObj.name) {
          // allow him for now
          // return;  // ignore this!  we can't have previously connected guys appearing b/c we won't know their partitions (unless SEMP is active?) (too much work!)
          var newSub = getBlankClient();
          newSub.name = clientName;
          newSub.connected = true;
          newSub.type = 'sub';
          newSub.index = subIndex++;
          newSub.curIndex = subMap.size;
          newSub.activeFlow = payload.flow == 'FLOW_ACTIVE';
          newSub.bound = (payload.flow == 'FLOW_ACTIVE' || payload.flow == 'FLOW_INACTIVE' || payload.flow == 'FLOW_UP');
          // console.log(clientName + ": " + JSON.stringify(payload));
          subMap.set(clientName, newSub);
          enterNewSubs();
          updateSubsPos();
          // updateSubStatus(newSub);
          // updateSubsPos();
          // updateLines();
        }
        Object.assign(subMap.get(clientName), payload);
        updateLines();
        updateSubStatus(subMap.get(clientName));
        subMap.get(clientName).lastTs = Date.now();
        updateSubStatus(subMap.get(clientName));
        updateClientStats(subMap.get(clientName), 'sub');
      } else if (clientName.indexOf("pq/pub") == 0) {
        if (!ctrlSet.has(clientName)) {
          ctrlSet.add(clientName);
          updateCtrlList();
        }
        if (!pubMap.has(clientName)) {
          var newPub = getBlankClient();
          newPub.name = clientName;
          newPub.connected = true;
          newPub.activeFlow = true;
          newPub.type = "pub";
          newPub.index = pubIndex++;
          // newPub.curIndex = subMap.size;
          Object.assign(newPub, payload);
          pubMap.set(clientName, newPub);
          d3.select('#uses-box').style('visibility', 'hidden');  // at least one pub, so hide the text
          enterNewPubs();
          updatePubs();
        // } else {  // it's already there, need update
        }
        Object.assign(pubMap.get(clientName), payload);
        updatePubStatus(pubMap.get(clientName));
        pubMap.get(clientName).lastTs = Date.now();
        updateClientStats(pubMap.get(clientName), 'pub');
      } else if (clientName.indexOf('pq/oc') == 0) {
        if (!oc) {
          oc = getBlankClient();
          oc.name = clientName;
          oc.index = 1;
          oc.connected = true;
          oc.type = 'oc';
          enterNewOc();
          updateOc();
        } else if (clientName != oc.name) {
          log("Getting stats for another Order Checker?");
          return;
        }
        Object.assign(oc, payload);
        oc.lastTs = Date.now();
        updateOcStatus();
        updateClientStats(oc, 'oc');
      }
    }
  } catch (e) {
    err('add an issue in my onMessage() event handler');
    console.error(e);
  }
}  // end of message listener

function rebalanceSubIndexes(subCurIndex) {
  for (let p of subMap.values()) {
    if (p.curIndex > subCurIndex) {
      p.curIndex--;
    }
  }
}

// if somehow we lose a client app while we're disconnected, this will remove them
setInterval(function goneClientsChecker() {
  if (!visible) return;
  if (!mqttConn) return;
  const now = Date.now();
  if (now - mqttConnTs < 5000) return;  // have to be connected for a least a bit
  if (now - visibleTs < 5000) return;  // have to be visible for a least a bit
  // const timeOnScreen = now - visible;
  var updated = false;
  for (let client of subMap.values()) {
    if (client.name.indexOf("pq/sub") == 0 && now - client.lastTs > 25000) {  // it's one of my demo subs && 25 seconds since we've heard from them
      // this allows for non-demo clients (like SdkPerf to use this, and not have the clients disappear
      log('Removing sub ' + client.name + ' as it has been 25 seeconds since last comms');
      subMap.delete(client.name);
      rebalanceSubIndexes(client.curIndex);
      updated = true;
    }
  }
  if (updated) {
    updateSubsPos();  // it will be disappearing now
    updateLines();
  }

  updated = false;
  for (let client of pubMap.values()) {
    if (now - client.lastTs > 15000) {  // 20 seconds since we've heard from them
      log('Removing pub ' + client.name + ' as it has been 15 seeconds since last comms');
      pubMap.delete(client.name);
      updated = true;
    }
  }
  if (updated) {
    updatePubs();  // it will be disappearing now
  }

  if (oc && now - oc.lastTs > 5000) {
    log('Removing OC ' + oc.name + ' as it has been 5 seeconds since last comms');
    oc = null;
    updateOc();
  }

  updated = false;
  for (let c of ctrlSet.values()) {
    if (!subMap.has(c) && !pubMap.has(c)) {
      ctrlSet.delete(c);
      updated = true;
    }
  }
  if (updated) {
    updateCtrlList();
  }
}, 2345);  // every ~2.3 seconds do this check



// GUI STUFF!! ///////////////////////////

function getColorHue(index) {
  index--;
  // var increment = 0.56;
  // var increment = 0.29;
  var increment = 0.29;
  var hue = (increment * index) - Math.floor(increment * index);
  return hue;
}

function getColorSat(index) {
  var saturation = 1 - ((index / 15) * 0.2);
  return 1;
  return saturation;
}

function getColorValue(index) {
  var value = 1 - ((index / 15) * 0.1);
  return 0.45;
  return value;
}

//         strokeColor: HSVtoRGB(getColorHue(colorIndex),0.5*getColorSat(colorIndex),getColorValue(colorIndex)),

// https://stackoverflow.com/questions/17242144/javascript-convert-hsb-hsv-color-to-rgb-accurately
function HSVtoRGB(h, s, v) {
  var r, g, b, i, f, p, q, t;
  if (arguments.length === 1) {
    s = h.s, v = h.v, h = h.h;
  }
  i = Math.floor(h * 6);
  f = h * 6 - i;
  p = v * (1 - s);
  q = v * (1 - f * s);
  t = v * (1 - (1 - f) * s);
  switch (i % 6) {
    case 0: r = v, g = t, b = p; break;
    case 1: r = q, g = v, b = p; break;
    case 2: r = p, g = v, b = t; break;
    case 3: r = p, g = q, b = v; break;
    case 4: r = t, g = p, b = v; break;
    case 5: r = v, g = p, b = q; break;
  }
  return 'rgb(' + Math.round(r * 255) + ',' + Math.round(g * 255) + ',' + Math.round(b * 255) + ')';
  return {
    r: Math.round(r * 255),
    g: Math.round(g * 255),
    b: Math.round(b * 255)
  };
}

const colors = ['#000080', '#808000', ''];
function getCol(clientIndex) {

}





// CONSTANTS AND VARS!!!!!!

const clientDivHeight = 90;   // how big each client div is
const clientDivPadding = 40;  // how much to space out the client divs
const partitionDivSize = 40;  // how big each partition (inside the queue) div is, but we don't set the height so this kind of includes "padding"
// const partitionDivPadding = 10;

var w, h;
var colWidth;
var padding = 40;
const cssHorizPadding = 20;  // copied from CSS file, needed in the horizontal size h, colWidth calculations
const cssVertPadding = 5;
const cssQueueVertPadding = 15;
var qHeader = 50;  // how big is the "top part" of the queue div where the name + details go?

// doesn't do much except register a resize listener
function svgSetup() {
  window.addEventListener('resize', onWindowResize);
  onWindowResize();
}

// the window has changed sizes, so let's recalc stuff
function onWindowResize() {
  // grab new window sizes
  w = window.innerWidth;
  h = window.innerHeight;// - 40;
  padding = w * 0.03;
  colWidth = (w / 3) - (2 * padding) - (2 * cssHorizPadding);
  rescale();
}

function rescale() {
  d3.select("#svg").attr('width', w).attr('height', h);
  updateQueue();
  updatePubs();
  updateOc();
  updateSubsPos();
  updateLines();
}






// CLIENT CONTROL AND HELPER FUNCTIONS! ////////////////////////////////

function publishPauseMessage(clientName) {
  const clientUid = clientName.substring(clientName.lastIndexOf('/') + 1);
  mqttClient.publish("pq-demo/control-" + clientUid + "/pause");  // publish a quit message that only that subscriber is listening to
}

function publishQuitMessage(clientName) {
  log('### quit message published to client ' + clientName);  // to help correlate with app logs
  const clientUid = clientName.substring(clientName.lastIndexOf('/') + 1);
  mqttClient.publish("pq-demo/control-" + clientUid + "/quit");  // publish a quit message that only that subscriber is listening to
}

function publishKillMessage(clientName) {
  log('### kill message published to client ' + clientName);  // to help correlate with app logs
  const clientUid = clientName.substring(clientName.lastIndexOf('/') + 1);
  mqttClient.publish("pq-demo/control-" + clientUid + "/kill");  // publish a kill message that only that subscriber is listening to
}

function bounceClient(clientName) {
  if (!sempConn) return;
  log('### bounce client triggered for ' + clientName);  // to help correlate with app logs
  // const postRequest = '<rpc><show><queue><name>' + queueObj.partitionPattern + '</name></queue></show></rpc>';
  const postRequest = '<rpc><admin><client><name>' + clientName + '</name><vpn-name>' + queueObj.msgVpnName + '</vpn-name><disconnect/></client></admin></rpc>';
  const start = Date.now();
  fetch(props.sempUrl + '/SEMP', {
    method: 'POST',
    credentials: 'same-origin',
    cache: 'no-cache',
    mode: "cors",
    body: postRequest,
    headers: headers
  })
    .then(response => response.text())
    .then(str => {
      // var millis = Date.now() - start;
      // console.log(millis);
      // console.log(str);
    })
    .catch(error => err("didn't work " + error));
}

function toggleAcl(clientName, type, topic) {  // could be publish topic or connect IP addr
  if (!sempConn) return;
  log('### toggle ' + type + ' ACL triggered for ' + clientName);  // to help correlate with app logs
  headers.set('Content-Type', 'application/json');
  let otherHeaders = {
    method: 'GET',
    credentials: 'same-origin',
    cache: 'no-cache',
    mode: "cors",
    headers: headers
  };
  fetch(props.sempUrl + '/SEMP/v2/monitor/msgVpns/' + props.vpn + '/clients/' + encodeURIComponent(clientName), otherHeaders)
    .then(response => response.json())
    .then(json => {
      if (json.meta.responseCode != 200) {
        err("Error trying to connect via SEMPv2");
        console.error(json.meta);
        return;  // probably authoriztion error
      }
      var acl = json.data.aclProfileName;
      var address = json.data.clientAddress;
      address = address.substring(0, address.indexOf(':'));
      otherHeaders.method = 'POST';
      otherHeaders.body = type == 'connect' ? '{"clientConnectExceptionAddress":"' + address + '/32"}' : '{"publishTopicException":"' + topic + '","publishTopicExceptionSyntax":"smf"}';
      console.log(otherHeaders.body);
      fetch(props.sempUrl + '/SEMP/v2/config/msgVpns/' + props.vpn + '/aclProfiles/' + acl + (type == 'connect' ? '/clientConnectExceptions' : '/publishTopicExceptions'), otherHeaders)
      .then(response => response.json())
      .then(json => {
        if (json.meta.responseCode >= 400 && json.meta.error.status == 'ALREADY_EXISTS') {
          // probably alrady exists..!
          err("No problem! Just tried adding, and it already exists, so I'll just remove it instead");
          otherHeaders.method = 'DELETE';
          otherHeaders.body = null;
          fetch(props.sempUrl + '/SEMP/v2/config/msgVpns/' + props.vpn + '/aclProfiles/' + acl + (type == 'connect' ? '/clientConnectExceptions/' + address + '%2f32' : '/publishTopicExceptions/smf,' + encodeURIComponent(topic)) , otherHeaders)
          .then(response => response.json())
          .then(json => {
            if (json.meta.responseCode != 200) {
              err("Error trying to connect via SEMPv2");
              console.error(json.meta);
              return;  // probably authoriztion error
            }
            log('Removed the ACL ' + type + ' exception on this broker in ACL profile ' + acl + ': ' + address + '/32');
          })
          .catch(error => err("didn't work " + error));
        } else if (json.meta.responseCode != 200) {
          err("Error trying to connect via SEMPv2");
          console.error(json.meta);
          return;  // probably authoriztion error
        } else {
          // woohoo, done!
          log('Made an ACL ' + type + ' exception on this broker in ACL profile ' + acl + ': ' + address + '/32');
        }
      })
      .catch(error => err("didn't work " + error));
    })
    .catch(error => err("didn't work " + error));
}

function log(logEntry) {
  console.log(getTs() + logEntry);
}

function err(logEntry) {
  console.error(getTs() + logEntry);
}

function getTs() {
  const ts = (Date.now()) - (new Date().getTimezoneOffset() * 60000);
  const h = (Math.floor((ts / 3600000) % 24)).toString().padStart(2, '0');
  const m = (Math.floor((ts / 60000) % 60)).toString().padStart(2, '0');
  const s = (Math.floor((ts / 1000) % 60)).toString().padStart(2, '0');
  const ms = (Math.floor(ts % 1000)).toString().padStart(3, '0');
  return h + ':' + m + ':' + s + ',' + ms + ' ';  // comma matches log4j2 date format

  return Math.floor((ts / 3600000) % 24) + ":" +
    Math.floor((ts / 60000) % 60) + ":" +
    Math.floor((ts / 1000) % 60) + "." +
    Math.floor(ts % 1000) + ": ";
}



// SUB STUFF!!  ///////////////////////////////////////

// used by pubs too... this calculates the vertical position of a client based on its "i of n" ranking
function getClientYPos(n, i) {
  const totSize = n * clientDivHeight + (Math.max(0, n - 1) * clientDivPadding);
  const val = (h / 2) - totSize / 2 + (i * clientDivHeight) + (i * clientDivPadding) - cssVertPadding;
  // log("h: " + h + ", totSize: " + totSize + ", val: " + val);
  return val;
}

// update the span for a sub's connection/flow status, and the whole div's color
function updateSubStatus(sub) {
  var span = d3.select('#varsubstatus' + sub.index);
  span.html(getSubStatusHtml(sub));
  d3.select('#sub' + sub.index).style('background-color', d => getSubColor(d))
}

// the html text inside the status span
function getSubStatusHtml(d) {
  if (d.queueName == queueObj.name && d.flow == 'FLOW_ACTIVE') {
    d.bound = true;
    d.activeFlow = true;
  } else if (d.queueName && d.queueName != queueObj.name) {
    d.bound = 'wrong';
  }
  if (!d.connected) {
    return '<b>Disconnected</b>';
  } else {  // it's connected at least
    if (!d.bound) {
      return '<b>Connected</b>, not bound';
    } else if (d.bound == 'wrong') {//} || d.queueName != queueObj.name) {
      // d.bound = 'wrong';
      return '<b>Connected</b>, BOUND WRONG QUEUE';
    } else {  // it's bound
      if (!d.activeFlow) {
        return '<b>Connected, Bound to Queue</b>, inactive';
      } else {
        return '<b>Connected, Bound to Queue, Active Flow</b>';
      }
    }
  }
}

function injectPublishException() {
  if (pubMap.size == 0) return;
  console.log(pubMap);
  console.log(Array.from(pubMap.keys()));
  let pubName = Array.from(pubMap.keys())[0];
  let topic = document.getElementById("acl").value;
  log("topic is: '" + topic + "'");
  toggleAcl(pubName, 'publish', topic);
}

// the whole html inside the sub's div, called once at creation
function getSubDivHtml(d) {
  var firstPart = '<table><tr><td width="100%"><nobr><h2>Sub #' + d.index + ' <span style="font-size:70%; font-weight:normal;">(' + d.name.substring(d.name.lastIndexOf('/') + 1) + ')</span>&nbsp;&nbsp;<span id="varsubrate' + d.index + '">' + d.rate + '</span> mgs/s</h2></nobr></td>';
  firstPart += '<td><a href="javascript:quit()" onclick="publishQuitMessage(\'' + d.name + '\'); return false;" title="Graceful Quit Message">😇</a></td>';
  firstPart += '<td><a href="javascript:kill()" onclick="publishKillMessage(\'' + d.name + '\'); return false;" title="Instant Kill Message">💀</a></td>';
  // if (sempConn) {
  firstPart += '<td><a class="sempdep" href="javascript:bounce()" onclick="bounceClient(\'' + d.name + '\'); return false;" title="Bounce Connection">🏀</a></td>';
  firstPart += '<td><a class="sempdep" href="javascript:acl()" onclick="toggleAcl(\'' + d.name + '\', \'connect\'); return false;" title="Toggle Connect ACL">🚫</a></td>';
  // }
  firstPart += '</tr></table>';
  var statusPart = '<p><nobr><span id="varsubstatus' + d.index + '">' + getSubStatusHtml(d) + '</span></nobr></p>';
/*
{
  "queueName": "nonex",
  "rate": 10,
  "slow": 0,
  "ackd": 0,
    "red": 0,
    "oos": 0,
    "newKs": 5,
    "dupes": 0,
    "gaps": 0,

    "badSeq": 0,
    "flow": "FLOW_ACTIVE"
}
*/
  var lastPart = '<table width="100%"><tr><td colspan="2">';
  lastPart += '<nobr><span id="colsubslow' + d.index + '">Slow: <span id="varsubslow' + d.index + '">0</span> ms</span></nobr>';
  lastPart += '</td><td colspan="2">';
  lastPart += '<nobr>ACK Delay: <span id="varsubackd' + d.index + '">0</span> ms</nobr>';
  lastPart += '</td></tr><tr><td width="22%">';
  lastPart += '<nobr><span id="colsubgaps' + d.index + '">GAPS: <span id="varsubgaps' + d.index + '">0</span></span></nobr>';
  lastPart += '</td><td width="28%">';
  lastPart += '<nobr><span id="colsuboos' + d.index + '">ChgSeq: <span id="varsuboos' + d.index + '">0</span></span></nobr>';
  lastPart += '</td><td width="25%">';
  lastPart += '<nobr><span id="colsubred' + d.index + '">Redlv\'d: <span id="varsubred' + d.index + '">0</span></span></nobr>';
  lastPart += '</td><td width="25%">';
  lastPart += '<nobr><span id="colsubdupes' + d.index + '">Dupes: <span id="varsubdupes' + d.index + '">0</span></span></nobr>';
  lastPart += '</td></tr></table>';


  // var lastPart = '<p><nobr>';  // name is now above in the firstPart
  // lastPart += '<span id="colsubred' + d.index + '">Redlv\'d: <span id="varsubred' + d.index + '">0</span></span>';
  // lastPart += ', <span id="colsuboos' + d.index + '">OutOfSeq: <span id="varsuboos' + d.index + '">0</span></span>';
  // lastPart += '</nobr></p><p><nobr>'
  // lastPart += 'Slow: <span id="varsubslow' + d.index + '">0</span> ms';
  // lastPart += ', ACK Delay: <span id="varsubackd' + d.index + '">0</span> ms';
  // lastPart += '</nobr></p>';
  return firstPart + statusPart + lastPart;
}

// what color is the sub's div, depends on connection/flow status
function getSubColor(d) {
  if (d.connected) {
    if (d.bound) {
      if (d.bound == 'wrong') {
        return '#ffddff';  // connected to wrong queue
      } else {
        if (d.activeFlow) {
          return '#ddffdd';  // green, good to go!
        } else {
          return '#ddddff';  // just bound
        }
      }
    } else return '#ffffdd';  // just connected, not bound
  } else return '#ffdddd';  // disconnected
}

// called when adding a new sub
function enterNewSubs() {
  var numSubs = subMap.size;
  var subsSorted = Array.from(subMap.values()).sort((a, b) => a.index - b.index);  // regular
  var enteringSubs = d3.select('#subs').selectAll('div').data(subsSorted, d => d.name).enter().append('div');
  enteringSubs
    .attr('class', 'withBorder')
    .attr('id', d => 'sub' + d.index)
    .style('position', 'absolute')
    .html((d, i) => getSubDivHtml(d, i))
    .style('outline-color', d => {
      return HSVtoRGB(getColorHue(d.index), 0.5 * getColorSat(d.index), getColorValue(d.index))
    })
    ;
}

// only update their locations, called when changing window size, or if someone disconnects (i.e. the backing array subMap.values() has changed)
function updateSubsPos() {
  var numSubs = subMap.size;
  var subsSorted = Array.from(subMap.values()).sort((a, b) => a.index - b.index);  // regular
  var updatedSubs = d3.select('#subs').selectAll('div').data(subsSorted, d => d.name);
  var exitingSubs = updatedSubs.exit();
  if (exitingSubs.size() > 0) {
    // log('have some exiting subs');
    // const delay = d3.transition("delaymove").duration(100).delay(500);
    updatedSubs//.transition(delay)
      .style('top', function (d, i) { return getClientYPos(numSubs, i); })
      .style('left', (w * 2 / 3) + padding)
      .style('width', colWidth + "px")
      .style('height', clientDivHeight + "px")
      ;
    // updatedSubs.exit().remove();
    const trans = d3.transition("fadeout").duration(250);
    exitingSubs.transition(trans).style('opacity', 0).remove();
  } else {
    // log('NO exiting subs');
    updatedSubs
      .style('top', function (d, i) { return getClientYPos(numSubs, i); })
      .style('left', (w * 2 / 3) + padding)
      .style('width', colWidth + "px")
      .style('height', clientDivHeight + "px")
      ;
  }
}



// OC STUFF! //////////////////////////////////

function getOcHtml() {
  //  var firstPart = '<nobr><h2>Pub #' + d.index + ' <span style="font-size:75%; font-weight:normal;">(' + d.name.substring(d.name.lastIndexOf('/')+1) + ')</span> &mdash; <span id="varpubrate' + d.index + '">0</span> mgs/s</h2></nobr>';
  var firstPart = '<table><tr><td width="100%"><nobr><h2>Order Checker <span style="font-size:70%; font-weight:normal;">(' + oc.name.substring(oc.name.lastIndexOf('/') + 1) + ')</span>&nbsp;&nbsp;&nbsp;<span id="varocrate1">' + oc.rate + '</span> mgs/s</h2></nobr></td>';
  firstPart += '</tr></table>';
  var statusPart = '<p><nobr><span id="varocstatus">' + getOcStatusHtml() + '</span></nobr></p>';
  /*
  {
    "red": 0,
    "oos": 0,
    "rate": 10,
    "newKs": 0,
    "dupes": 0,
    "keys": 4,
    "badSeq": 0,
    "gaps": 0
  }
  */
  var lastPart = '<table width="100%"><tr><td colspan="2">';
  lastPart += '<nobr>Total Keys Seen: <b><span id="varockeys">0</span></b></nobr>';
  lastPart += '</td><td colspan="2">';
  lastPart += '<nobr><span id="colocmissing">Total Missing Msgs: <span id="varocmissing">0</span></span></nobr>';
  lastPart += '</td></tr><tr><td width="22%">';
  lastPart += '<nobr><span id="colocgaps1">GAPS: <span id="varocgaps1">0</span></span></nobr>';
  lastPart += '</td><td width="28%">';
  lastPart += '<nobr><span id="colocoos1">ChgSeq: <span id="varocoos1">0</span></span></nobr>';
  lastPart += '</td><td width="25%">';
  lastPart += '<nobr><span id="colocred1">Redlv\'d: <span id="varocred1">0</span></span></nobr>';
  lastPart += '</td><td width="25%">';
  lastPart += '<nobr><span id="colocdupes1">Dupes: <span id="varocdupes1">0</span></span></nobr>';
  lastPart += '</td></tr></table>';
  return firstPart + statusPart + lastPart;
}

function getOcStatusHtml() {
  if (!oc.connected) {
    return '<b>Disconnected</b>';
  } else {  // it's connected at least
    return '<b>Connected</b>';
  }
}

function enterNewOc() {
  var enteringOc = d3.select('#oc').selectAll('div').data([oc]).enter().append('div');  // enter new oc
  // var enteringOc = d3.select('#oc').select('div').datum(oc).enter().append('div');  // enter new oc
  enteringOc
    .attr('class', 'withBorder')
    .attr('id', d => 'oc1')
    .style('position', 'absolute')
    .attr('class', 'thickBorder')
    .style('border-radius', '0px')
    .html((d, i) => getOcHtml(d, i))
    .property('_bgColorArray_', oc.connected ? [0xdd, 0xff, 0xdd] : [0xff, 0xdd, 0xdd])
    .property('aaron', 'rules')
    .style('background-color', oc.connected ? "#ddffdd" : "#ffdddd" )
    ;
  oc.bgColorArray = enteringOc.property('_bgColorArray_');
}

// update the span for a sub's connection/flow status, and the whole div's color
function updateOcStatus() {
  var span = d3.select('#varocstatus');
  span.html(getOcStatusHtml());
  // d3.select('#pub' + pub.index).style('background-color', d => getSubColor(d))
}

function updateOc() {
  const ocArray = oc == null ? [] : [oc]
  var updatedOc = d3.select('#oc').selectAll('div').data(ocArray);
  // var updatedOc = d3.select('#oc').select('div').datum(oc);
  // log('CONECTED: ' + (oc ? oc.connected : 'null!'));
  updatedOc
    .style('top', h - 200) //(d, i) => getClientYPos(numPubs, i))
    .style('left', padding)
    .style('width', colWidth + "px")
    .style('height', clientDivHeight + "px")
    .style('background-color', function (d) { return d.connected ? "#ddffdd" : "#ffdddd" })
    // .style('background-color', function (d) { log('INDIDE ' + d.connected); return d.connected ? "#ddffdd" : "#ffdddd"; })
  ;
  // if (oc && !oc.connected) updatedOc.style('background-color', "#ddffdd");
  updatedOc.exit().remove();

    const ocArrayArrows = oc && oc.connected? [oc] : [];

    d3.select("#ocarrow").selectAll('line').data(ocArrayArrows).enter().append('line');  // inserts new line if one doesn't exist
    var updatedArrows = d3.select("#ocarrow").selectAll('line').data(ocArrayArrows);
    updatedArrows.exit().remove();
    d3.select("#ocarrow").selectAll('polygon').data(ocArrayArrows).enter().append('polygon');  // inserts new polygons (arrow head)
    var updatedPolys = d3.select("#ocarrow").selectAll('polygon').data(ocArrayArrows);
    updatedPolys.exit().remove();

    updatedArrows
    .style('stroke-width', '0.2em')
    .style('stroke', 'black')
    .attr("x1", (w / 3) - padding + 10)
    .attr("x2", (w / 3))
    .attr("y1", h - 200 + (clientDivHeight/2) + cssVertPadding)
    .attr("y2", h - 200 + (clientDivHeight/2) + cssVertPadding)
    ;
  updatedPolys
    .style('stroke-width', '0.2em')
    .style('stroke', 'black')
    .attr('points', makePolyPoints(w / 3 - padding + 10, h - 200 + clientDivHeight / 2 + cssVertPadding, true))
    ;

}




// PUB STUFF!!  ////////////////////////////

function getPubHtml(d) {
  //  var firstPart = '<nobr><h2>Pub #' + d.index + ' <span style="font-size:75%; font-weight:normal;">(' + d.name.substring(d.name.lastIndexOf('/')+1) + ')</span> &mdash; <span id="varpubrate' + d.index + '">0</span> mgs/s</h2></nobr>';
  var firstPart = '<table><tr><td width="100%"><nobr><h2>Pub #' + d.index + ' <span style="font-size:70%; font-weight:normal;">(' + d.name.substring(d.name.lastIndexOf('/') + 1) + ')</span>&nbsp;&nbsp;&nbsp;<span id="varpubrate' + d.index + '">' + d.rate + '</span> mgs/s</h2></nobr></td>';
  firstPart += '<td><a href="javascript:quit()" onclick="publishPauseMessage(\'' + d.name + '\'); return false;" title="Pause">⏸</a></td>';
  firstPart += '<td><a href="javascript:quit()" onclick="publishQuitMessage(\'' + d.name + '\'); return false;" title="Graceful Quit">😇</a></td>';
  firstPart += '<td><a href="javascript:kill()" onclick="publishKillMessage(\'' + d.name + '\'); return false;" title="Instant Kill">💀</a></td>';
  // if (sempConn) {
  firstPart += '<td><a class="sempdep" href="javascript:bounce()" onclick="bounceClient(\'' + d.name + '\'); return false;" title="Bounce Connection">🏀</a></td>';
  // }
  firstPart += '</tr></table>';
  var statusPart = '<p><nobr><span id="varpubstatus' + d.index + '">' + getPubStatusHtml(d) + '</span></nobr></p>';
  /*
  "rate": 10,
  "keys": 4096,
  "prob": 0.9,
  "delay": 0,
  "resendQ": 0,
  "nacks": 0,
  "activeFlow": true
  "paused": false,
  */
  var lastPart = '<table width="100%"><tr><td width="35%">';
  lastPart += '<nobr>Keys: <b><span id="varpubkeys' + d.index + '">0</span></b></nobr>';
  lastPart += '</td><td width="30%">';
  lastPart += '<nobr>Prob: <span id="varpubprob' + d.index + '">0.0</span>%</nobr>';
  lastPart += '</td><td width="30%">';
  lastPart += '<nobr>Delay: <span id="varpubdelay' + d.index + '">0</span> ms</nobr>';
  lastPart += '</tr><tr><td>'
  lastPart += '<nobr><span id="colpubnacks' + d.index + '">NACKs: <span id="varpubnacks' + d.index + '">0</span></span></nobr>';
  lastPart += '</td><td colspan="2">';
  lastPart += '<nobr>ResendQ: <span id="varpubresendQ' + d.index + '">0</span></span></nobr>';
  lastPart += '</tr></table>';
 
  return firstPart + statusPart + lastPart;
}

function getPubStatusHtml(d) {
  if (!d.connected) {
    return '<b>Disconnected</b>';
  } else {  // it's connected at least
    if (!d.activeFlow) {
      return '<b>Connected</b>, Not Active';
    } else {
      if (d.paused) {
        return '<b>Connected, Active Publisher Flow</b> (paused)';
      } else {
        return '<b>Connected, Active Publisher Flow</b>';
      }
    }
  }
}

function enterNewPubs() {
  const pubArray = Array.from(pubMap.values());
  var enteringPubs = d3.select('#pubs').selectAll('div').data(pubArray, d => d.name).enter().append('div');  // enter new pubs
  enteringPubs
    .attr('class', 'withBorder')
    .attr('id', d => 'pubdiv' + d.index)
    .style('position', 'absolute')
    .html((d, i) => getPubHtml(d, i))  // doesn't matter as much as subs
    .style('background-color', function (d) { return d.connected ? d.activeFlow ? "#ddffdd" : '#ffffdd' : "#ffdddd" })
    ;

}


// update the span for a sub's connection/flow status, and the whole div's color
function updatePubStatus(pub) {
  var span = d3.select('#varpubstatus' + pub.index);
  span.html(getPubStatusHtml(pub));
  // d3.select('#pub' + pub.index).style('background-color', d => getSubColor(d))
}


function updatePubs() {
  const pubArray = Array.from(pubMap.values());
  // d3.select('#pubs').selectAll('div').data(pubArray, d => d.name).enter().append('div');  // enter new pubs
  var updatedPubs = d3.select('#pubs').selectAll('div').data(pubArray, d => d.name);  // get them all
  d3.select("#pubarrows").selectAll('line').data(pubArray, d => d.name).enter().append('line');  // inserts new lines
  var updatedArrows = d3.select("#pubarrows").selectAll('line').data(pubArray, d => d.name);
  d3.select("#pubarrows").selectAll('polygon').data(pubArray, d => d.name).enter().append('polygon');  // inserts new polygons
  var updatedPolys = d3.select("#pubarrows").selectAll('polygon').data(pubArray, d => d.name);
  const numPubs = pubArray.length;

  updatedPubs
    // .attr('class', 'withBorder')
    // .attr('id', d => 'pubdiv' + d.index)
    // .style('position', 'absolute')
    // .html((d, i) => getPubHtml(d, i))  // doesn't matter as much as subs
    .style('top', (d, i) => getClientYPos(numPubs, i))
    .style('left', padding)
    .style('width', colWidth + "px")
    .style('height', clientDivHeight + "px")
    .style('background-color', function (d) { return d.connected ? d.activeFlow ? "#ddffdd" : '#ffffdd' : "#ffdddd" })
    ;

  updatedPubs.exit().remove();

  updatedArrows
    .style('stroke-width', '0.2em')
    .style('stroke', 'black')
    .attr("x1", d => d.activeFlow ? (w / 3) - (1 * padding) : -1)
    .attr("x2", d => d.activeFlow ? (w / 3) + (0.0 * padding) : -1)
    .attr("y1", (d, i) => d.activeFlow ? getClientYPos(numPubs, i) + clientDivHeight / 2 + cssVertPadding : -1)
    .attr("y2", (d, i) => d.activeFlow ? getClientYPos(numPubs, i) + clientDivHeight / 2 + cssVertPadding : -1)
    // .attr("y1", (d, i) => getClientYPos(numPubs, i) + (document.getElementById('pubdiv'+d.index).clientHeight)/2 )
    ;
  updatedArrows.exit().remove();

  updatedPolys
    .style('stroke-width', '0.2em')
    .style('stroke', 'black')
    .attr('points', (d, i) => d.activeFlow ? makePolyPoints(w / 3, getClientYPos(numPubs, i) + clientDivHeight / 2 + cssVertPadding) : '-1,-1')
    ;
  updatedPolys.exit().remove();
}

function makePolyPoints(x1, y1, rev) {
  if (rev) return (x1 + 5) + ',' + y1 + ' ' + (x1 + 8) + ',' + (y1 - 5) + ' ' + (x1 - 5) + ',' + y1 + ' ' + (x1 + 8) + ',' + (y1 + 5);
  else return (x1 - 5) + ',' + y1 + ' ' + (x1 - 8) + ',' + (y1 - 5) + ' ' + (x1 + 5) + ',' + y1 + ' ' + (x1 - 8) + ',' + (y1 + 5);
}







// ACTIVE FLOW LINES!! //////////////////////////////////////

function updateLines() {
  // log("partition count on updateLines() is " + queueObj.partitionCount);
  if (queueObj.partitionCount > 0) {
    // if (partitionMap.size > 0) {
    var partitionArray = Array.from(partitionMap.values()).sort(function (a, b) { return a.index - b.index });

    var enterLines = d3.select("#lines").selectAll('line').data(partitionArray, d => d.index).enter().append('line');  // inserts new lines
    enterLines
        .attr('id', d => 'partline'+d.index)
        .style('stroke-width', '0.2em')
        .style('stroke-linecap', 'square')
    ;
    var updatedLines = d3.select("#lines").selectAll('line').data(partitionArray, d => d.index);
    // var qHeight = qHeader + (partitionDivSize * partitionMap.size);
    // const qHeight = qHeader + (partitionDivSize * partitionMap.size) - cssQueueVertPadding;
    // var qPos = h / 2 - qHeight / 2;
    const qHeight = qHeader + (partitionDivSize * partitionMap.size) - cssQueueVertPadding;
    const qPos = h / 2 - qHeight / 2 - cssQueueVertPadding;  

    updatedLines
      .style('stroke', d => {
        if (subMap.get(d.client)) return HSVtoRGB(getColorHue(subMap.get(d.client).index), 0.5 * getColorSat(subMap.get(d.client).index), getColorValue(subMap.get(d.client).index))
        else return '#ff0000';
      })
      .attr("x1", (w * 2 / 3) - (1.5 * padding))
      .attr("y1", (d, i) => qPos + qHeader + (partitionDivSize / 2) + i * (partitionDivSize) - cssVertPadding/2)
      .attr("x2", d => {
        if (d.client && subMap.get(d.client) && subMap.get(d.client).bound) return (w * 2 / 3) + (1.05 * padding);
        else return (w * 2 / 3) - padding / 2;
      })
      .attr("y2", (d, i) => {
        if (d.client && subMap.get(d.client) && subMap.get(d.client).bound) {
          var subNum = subMap.size;
          var subIndex = subMap.get(d.client).curIndex;
          return getClientYPos(subNum, subIndex) + clientDivHeight / 2 + cssVertPadding;
          // return getClientYPos(subNum, (subNum - (subIndex + 1))) + clientDivHeight/2 + 8;  // reverse the order
        }
        // else...
        return qPos + qHeader + (partitionDivSize / 2) + i * partitionDivSize - cssVertPadding/2;
      })
      ;

    var updatedParts = d3.select('#partitions').selectAll('div').data(partitionArray, d => d.index);  // grab all the partition divs
    updatedParts.style('outline-color', d => {
      if (subMap.get(d.client)) return HSVtoRGB(getColorHue(subMap.get(d.client).index), 0.5 * getColorSat(subMap.get(d.client).index), getColorValue(subMap.get(d.client).index))
      else return '#ff0000';
    });
    updatedLines.exit().remove();

  } else {  // it's a regular exclusive or non-exclusive queue, so just draw lines from the queue to the active flow(s)
    var flowArray = [];
    for (let sub of subMap.values()) {
      // console.log(sub.name + ": has queue: " + sub.queueName + ", and active flow: " + sub.activeFlow);
      if (sub.queueName == queueObj.name && sub.activeFlow) flowArray.push(sub);
    }
    // log('flowarray: ' + JSON.stringify(flowArray));
    // flowArray.sort(function (a, b) { return b.index - a.index });
    d3.select("#lines").selectAll('line').data(flowArray, d => d.index).enter().append('line');  // inserts new lines
    var updatedLines = d3.select("#lines").selectAll('line').data(flowArray, d => d.index);
    var qHeight = qHeader + (partitionDivSize * partitionMap.size) - cssQueueVertPadding/2;
    var qPos = h / 2 - qHeight / 2;

    updatedLines
      .style('stroke-width', '0.2em')
      .style('stroke', d => {
        return HSVtoRGB(getColorHue(d.index), 0.5 * getColorSat(d.index), getColorValue(d.index))
      })
      .attr("x1", (w * 2 / 3) - (1 * padding) + 1)
      .attr("y1", (d, i) => {
        return h / 2;  // literally, the middle of the screen
        return qPos + qHeader;// + (partitionDivSize/2) + (i) * partitionDivSize;

        if (subMap.get(d.client) && subMap.get(d.client).activeFlow) {
          return qPos + qHeader + 20 + d.index * partitionDivSize;
        }
      })
      .attr("x2", d => {
        if (d.activeFlow) return (w * 2 / 3) + (1.05 * padding);
        else return (w * 2 / 3);// - padding;
      })
      .attr("y2", (d, i) => {
        if (d.activeFlow) {
          return getClientYPos(subMap.size, d.curIndex) + clientDivHeight / 2 + cssVertPadding;
        }
        // else...
        return qPos + qHeader;// + (partitionDivSize/2) + i * partitionDivSize;

      })
      ;
    updatedLines.exit().remove();
  }
}











// QUEUE AND PARTITIONS STUFF! ////////////////////////////////////////////////////////////

function getPartitionHtml(d) {
  if (queueObj.partitionCount == 0) {  // this is a regular queue, no partitions, but draw one anyway to show 'depth'
    return '<table id="tabpart' + d.index + '" width="100%"><tr><td>&nbsp;</td></tr></table>';
  }
  if (sempConn) {
    var resp = '<table id="tabpart' + d.index + '" width="100%"><tr>';
    if (d.client) {
      // resp += '<td width="40%"><nobr>Partition ' + d.index + ': <span id="partsubnum'+d.index+'">Sub '+d.client+'</span></nobr></td>';
      // resp += '<td width="20%" style="padding-left: 2px"><nobr>Part ' + d.index + '</nobr></td><td width="20%"><nobr><span id="partsubnum' + d.index + '">Sub ' + d.client + '</span></nobr></td>';
      resp += '<td width="20%" style="padding-left: 2px"><nobr>Part ' + d.index + '</nobr></td>';
    } else {
      // resp += '<td width="40%"><nobr>Partition ' + d.index + ': <span id="partsubnum'+d.index+'">No Sub</span></nobr></td>';
      // resp += '<td width="20%" style="padding-left: 2px"><nobr>Part ' + d.index + '</nobr></td><td width="20%"><nobr><span id="partsubnum' + d.index + '">No Sub</span></nobr></td>';
      resp += '<td width="20%" style="padding-left: 2px"><nobr>Part ' + d.index + '</nobr></td>';
    }
    resp += '<td width="30%"><nobr>Depth: <span id="varpartdepth' + d.index + '">0</span></nobr></td>' +
      '<td width="40%"><nobr>In/Egr: <span id="varpartingress' + d.index + '">0</span>/<span id="varpartegress' + d.index + '">0</span></nobr></td>' +
      '<td width="60px"><svg id="sparkline' + d.index + '" width="50px" height="19px"><g><rect class="bar" x="10" y="10" width="4" height="10" fill="black"></rect></g></svg></td>'
      '</tr></table>';
    return resp;
  } else {  // SEMP needs to be connected before this method runs!  Uh, this is only on ENTER, need to update sub names manually
    return '<p>Partition ' + d.index + '</p>';
    if (subMap.get(d.client)) {
      return '<p>Partition ' + d.index + ' &ndash; <span id="partsubnum' + d.index + '">Sub ' + d.client + '</span></p>';
    } else {
      return '<p>Partition ' + d.index + ' &ndash; <span id="partsubnum' + d.index + '">No Sub</span></p>';
    }
  }
}

function getQueueHtml(d) {
  // state is like "rebalancing" or not
  const h2 = "<h2>Queue '" + d.name + "' <span id=\"queueStatus\"></span></h2>";
  if (d.partitionCount >= 0) {  // only happens if SEMP available
    return h2 + '<nobr><p>Type: <b>' + d.accessType + '</b>, Partition Count: <b><span id="varqpart">' + d.partitionCount + '</span></b>, # Topics: <b><span id="varqtopics">' + d.topics + '</span></b></nobr></p>' +
      // '<table width="100%"><tr><td width="40%"><nobr>Depth: <span id="varqdepth">0</span> msgs</nobr></td>' +
      // '<td width="30%"><nobr>In: <span id="varqingress">0</span> msg/s</nobr></td><td width="30%"><nobr>Egr: <span id="varqegress">0</span> msg/s</nobr></td></tr></table>';
      '<table width="100%"><tr><td width="40%"><nobr>Depth: <span id="varqdepth">0</span> msgs</td>' +
      '<td width="60%"><nobr>Rates: <span id="varqingress">0</span> / <span id="varqegress">0</span> msg/s</nobr></td></tr></table>';
  } else {
    return h2;
  }
}

function updateQueueStatus() {
  if (queueObj.state) {
    d3.select('#queueStatus').html("&mdash; " + queueObj.state + ": <span id=\"timer\"></span>s</h2>");
  } else {
    d3.select('#queueStatus').html("");
  }
  updateQueue();  // will change background colours and stuff
}

function updatePartitions() {
  for (let p of partitionMap.values()) {
    if (p.client) {
      if (subMap.get(p.client) == null) { // || !subMap.get(p.client).activeFlow) {
        // they could be deleted by my "offline for too long" thing, so don't error here anymore
        p.client = null;
        d3.select('#partsubnum' + p.index).text('No Sub');
        return;
        err("INSIDE UPDATE PARTITIONS- SOMEHOW HAVE MISSING SUB");
        console.error(p);
        console.error(subMap);
        return;
      } else {
        d3.select('#partsubnum' + p.index).text('Sub ' + subMap.get(p.client).index);
      }
    } else {
      d3.select('#partsubnum' + p.index).text('No Sub');
    }
  }
}


// should only be called once!
function drawQueue() {
  var queueDiv = d3.select('#queue').selectAll('div').data([queueObj]).enter().append('div');  // make sure the queue is added
  queueDiv
    .style('position', 'absolute')
    .attr('class', 'thickBorder')
    .attr('id', 'queueDiv')
    .style('padding', cssQueueVertPadding + 'px 20px ' + cssQueueVertPadding + 'px 20px')
    .html(d => getQueueHtml(d))
    ;

  // need to draw the partitions too...
  const partitionArray = Array.from(partitionMap.values()).sort(function (a, b) { return a.index - b.index });
  var enterParts = d3.select('#partitions').selectAll('div').data(partitionArray, d => d.index).enter().append('div');   // enter the right number of partitions
  enterParts
    .style('position', 'absolute')
    .attr('class', 'withBorder')
    // .style('border-radius', queueObj.accessType === 'Partitioned' ? '5px' : '0px')  // can't use this b/c we may not have SEMP
    .style('border-radius', queueObj.partitionCount > 0 ? '5px' : '0px')
    .html(d => getPartitionHtml(d))
    .style('background-color', 'white')
    ;

  d3.select('#partitions').selectAll('div').data(partitionArray, d => d.index).exit().remove();
  // d3.select('#subs').selectAll('div').data(subsSorted, d => d.name)
  updateQueue();  // to set the sizes and stuff
}


// should now only be used for resizing things!
function updateQueue() {

  // d3.select('#queue').selectAll('div').data([queueObj]).enter().append('div');  // make sure the queue is added
  var updateQ = d3.select('#queue').selectAll('div').data([queueObj]);

  const qHeight = qHeader + (partitionDivSize * partitionMap.size) - cssQueueVertPadding;
  const qPos = h / 2 - qHeight / 2 - cssQueueVertPadding;

  updateQ
    .style('top', qPos)
    .style('left', w / 3 + padding)
    .style('height', qHeight)
    // .style('height', h - (2 * padding))
    .style('width', colWidth)
    .style('background-color', (d) => !d.state ? '#00000022' : '#88004422')
    ;

  // are there any partitions we need to worry about?

  const partitionArray = Array.from(partitionMap.values()).sort(function (a, b) { return a.index - b.index });
  var updatePart = d3.select('#partitions').selectAll('div').data(partitionArray, d => d.index);  // grab all the partition divs

  // TODO!!!  //////////////////////
  // sub name / index not working right when connecting after all disconnect

  // just in case, we should enter any partitions! (e.g. SEMP connectivity is no good, so need to draw them in after a rebalance finishes)



  updatePart
    .style('top', (d, i) => qPos + qHeader + partitionDivSize * i)
    .style('left', w / 3 + (1.5 * padding))
    // .style('height', 20)
    .style('width', colWidth - (1 * padding))
    ;

}









// STATS STUFF! /////////////////////////////////////////

function anotherSmoothStats(client, stat, trans, colStr) {
  const statName = client.type + stat + client.index;
  const curVar = d3.select('#var' + statName);
  // const curRedelivers = d3.select('#varsubred' + client.index);
  // const curRedeliversValue = +(curRedelivers.text().replaceAll(',', ''));
  const curVarValue = +(curVar.text().replaceAll(',', ''));  // in case there are commas
  const valueToSetTo = client[stat];
  if (visible) {
    if (curVarValue <= valueToSetTo) {  // set it straight
      d3.select('#var' + statName).text(d3.format('d')(valueToSetTo));
      if (curVarValue < valueToSetTo) d3.select('#col' + statName).style('color', colStr).style('font-weight', 'bold');
      else if (curVarValue == 0 && valueToSetTo == 0) d3.select('#col' + statName).style('color', '#000000').style('font-weight', 'normal');
    } else {
      // var t = d3.transition("redelivered-" + client.name).duration(900).ease(d3.easeLinear);
      curVar.datum(valueToSetTo).transition(trans).textTween(d => {
        const i = d3.interpolateRound(curVarValue, d);
        return function (t) { return d3.format('d')(i(t)); };
      });
      if (curVarValue == 0) {
        d3.select('#col' + statName).style('color', '#000000').style('font-weight', 'normal');
      } else {
        d3.select('#col' + statName).style('color', colStr).style('font-weight', 'bold');
      }
    }
  } else {  // not visible, just set straight
    d3.select('#varsub' + stat + client.index).text(d3.format('d')(valueToSetTo));
    if (valueToSetTo == 0) {
      d3.select('#col' + statName).style('color', '#000000').style('font-weight', 'normal');
    } else {
      d3.select('#col' + statName).style('color', colStr).style('font-weight', 'bold');
    }
  }  
}



function updateClientStats(client, type) {  // type == 'pub' | 'sub' | 'oc'
  try {
    // if (type == 'oc') console.log(client.rate);
    // log("msgRate: "+rate);
    // if (!client.activeFlow) return;  // this seems dumb!  // don't update a disconnected guy!
    if (visible) {
      var t = d3.transition("msg-count-update-" + type + "-" + client.name).duration(1000).ease(d3.easeLinear);
      const msgRate = d3.select('#var' + type + 'rate' + client.index);
      if (!msgRate) log('uh oh msgRate is null');
      const o2 = +(msgRate.text().replaceAll(',', ''));
      msgRate.datum(client.rate).transition(t).textTween(d => {
        const i2 = d3.interpolateRound(o2, d);
        return function (t) { return d3.format('d')(i2(t)); };
      });
    } else {
      d3.select('#var' + type + 'rate' + client.index).text(d3.format('d')(client.rate));
    }
    if (type == 'pub') {
      if (client.keys == 2147483647) {
        d3.select('#varpubkeys' + client.index).text("max");
      } else {
        d3.select('#varpubkeys' + client.index).text(d3.format(',d')(client.keys));
      }
      if (client.prob > 0) {
        d3.select('#varpubprob' + client.index).html('<b>' + d3.format('.1f')(client.prob * 100) + '</b>');
        d3.select('#varpubdelay' + client.index).html('<b>' + d3.format(',d')(client.delay) + '</b>');
      }  
      else {
        d3.select('#varpubprob' + client.index).html(d3.format('.1f')(client.prob * 100));
        d3.select('#varpubdelay' + client.index).html(d3.format(',d')(client.delay));
      }
      if (client.resendQ > 0) d3.select('#varpubresendQ' + client.index).html('<b>' + d3.format(',d')(client.resendQ) + '</b>');
      else d3.select('#varpubresendQ' + client.index).html(d3.format(',d')(client.resendQ));

      d3.select('#varpubnacks' + client.index).text(d3.format('d')(client.nacks));
      if (client.nacks == 0) {
        d3.select('#colpubnacks' + client.index).style('color', '#000000').style('font-weight', 'normal');
      } else {
        d3.select('#colpubnacks' + client.index).style('color', '#ff0000').style('font-weight', 'bold');
      }



    } else if (type == 'sub') {
      var t2 = d3.transition("oos-" + client.name).duration(900).ease(d3.easeLinear);
      anotherSmoothStats(client, 'oos', t2, '#cc8800');
      anotherSmoothStats(client, 'dupes', t2, '#00aa00');
      anotherSmoothStats(client, 'gaps', t2, '#ff0000');
      anotherSmoothStats(client, 'red', t2, '#0000aa');

      // just set these two directly
      d3.select('#varsubslow' + client.index).text(d3.format('d')(client.slow));
      if (client.slow == 0) {
        d3.select('#varsubslow' + client.index).style('font-weight', 'normal');
        d3.select('#colsubslow' + client.index).style('color', '#000000').style('font-weight', 'normal');
      } else {
        d3.select('#varsubslow' + client.index).style('font-weight', 'bold');
        if (client.slow >= 50) {
          d3.select('#colsubslow' + client.index).style('color', '#880000').style('font-weight', 'bold');
        } else {
          d3.select('#colsubslow' + client.index).style('color', '#000000').style('font-weight', 'normal');
        }
      }
      d3.select('#varsubackd' + client.index).text(d3.format('d')(client.ackd));
      if (client.ackd == 0) {
        d3.select('#varsubackd' + client.index).style('font-weight', 'normal');
      } else {
        d3.select('#varsubackd' + client.index).style('font-weight', 'bold');
      }
    } // end sub block
    else if (type == 'oc') {
      // set directly, no bold or antthing
      if (client.newKs > 0) {
        d3.select('#varockeys').text(d3.format(',d')(client.keys) + ' (+' + d3.format(',d')(client.newKs) + ')');
      } else {
        d3.select('#varockeys').text(d3.format(',d')(client.keys));
      }
      // d3.select('#varocnewks').text(d3.format('d')(client.newKs));  // ignore new keys
      // d3.select('#varocbadseq').text(d3.format('d')(client.badSeq));  // ignore num seqs with mising
      // if (client.badSeq == 0) {
      //   d3.select('#colocbadseq').style('font-weight', 'normal');
      // } else {
      //   d3.select('#colocbadseq').style('font-weight', 'bold');
      // }
      d3.select('#varocmissing').text(d3.format('d')(client.missing));
      if (client.missing == 0) {
        d3.select('#colocmissing').style('color', '#000000').style('font-weight', 'normal');
      } else {
        d3.select('#colocmissing').style('color', '#ff0088').style('font-weight', 'bold');  // magenta red, to make it super obvious!
      }

      var t3 = d3.transition("octrans2").duration(900).ease(d3.easeLinear);
      anotherSmoothStats(client, 'oos', t3, '#cc8800');
      anotherSmoothStats(client, 'dupes', t3, '#00aa00');
      anotherSmoothStats(client, 'gaps', t3, '#ff0000');
      anotherSmoothStats(client, 'red', t3, '#0000aa');

      var t2 = d3.transition("octrans").duration(900);//.ease(d3.easeLinear);
      if (visible) {
        var bgColor;
        var bodyFlash = false;
        if (client.gaps > 0) {  // uh-oh!  gaps are bad!!
          bgColor = [0xff, 0xaa, 0xaa];
          bodyFlash = true;
        } else if (client.oos > 0) {  // had some jumps in the sequencing, but all recoverable b/c no gaps
          bgColor = [0xff, 0xdd, 0xaa];
          bodyFlash = true;
        } else if (client.red > 0 || client.dupes > 0) {
          if (client.dupes >= client.red) {  // exactly the same amount, or more dupes than reds, no worries at all
            bgColor = [0xaa, 0xff, 0xaa];  // flash green
            bodyFlash = true;
          } else {  // had some redeliveries that I hadn't seen before, expected during a client loss
            bgColor = [0xaa, 0xaa, 0xff];  // flash blue
            bodyFlash = true;
          }
        } else {  // regular color, slightly plusing to show that it's receiving stats updates
          bgColor = oc.connected ? [0xd0, 0xff, 0xd0] : [0xff, 0xdd, 0xdd];
          // NO bgColor = oc.connected ? [0xe6, 0xff, 0xe6] : [0xff, 0xdd, 0xdd];
        }
        // let's set teh background color of my thing
        d3.select('#oc1').datum(oc).transition(t2).styleTween("background", function (d) {
          const i2 = d3.interpolateNumberArray(bgColor, d.bgColorArray);
          return function (t) {
            // const rightColor = 'rgba(255,0,127,' + (0.0004 * (Math.min(1000, Math.max(0, i2(t) - 400)))) + ')';
            // return 'linear-gradient(270deg, ' + rightColor + ' 0%, rgba(0,0,0,0.2) ' + i2(t) + 'px, rgba(255,255,255,1) ' + i2(t) + 'px)';
            return 'rgb(' + i2(t)[0] + ',' + i2(t)[1] + ',' + i2(t)[2] + ')';
          };
        });
        // mildly flash the background too...
        if (bodyFlash) d3.select('body').datum(oc).transition(t2).styleTween("background", function (d) {
          const i2 = d3.interpolateNumberArray(bgColor, [0xff, 0xff, 0xff]);
          return function (t) {
            return 'rgba(' + i2(t)[0] + ',' + i2(t)[1] + ',' + i2(t)[2] + ', 0.2)';
          };
        });
        


      } else {  // else not visible, so just set it straight, no interpolation
        // const rightColor = 'rgba(255,0,127,' + (0.0004 * (Math.max(500,sa[i]))) + ')';
        // const rightColor = 'rgba(255,63,127,' + (0.0004 * (Math.min(1000, Math.max(0, sa[i] - 400)))) + ')';
        // d3.select('#tabpart' + i).style('background', 'linear-gradient(270deg, ' + rightColor + ' 0%, rgba(0,0,0,0.2) ' + sa[i] + 'px, rgba(255,255,255,1) ' + sa[i] + 'px)');
      }


    } 
  } catch (e) {
    err('had an issue in update client ' + type + ' stats');
    console.error(client);
    console.error(e);
    mqttClient.end();
  }

}



function getQueueDetails(sempRequestPossiblyPaged) {



  fetch(sempRequestPossiblyPaged, {
    method: 'GET',
    credentials: 'same-origin',
    cache: 'no-cache',
    mode: "cors",
    headers: headers
  })
    .then(response => response.json())
    .then(json => {
      // console.log(json);
      // now, this response will either contain the info we want, or we might have to ask a 2nd timd following the paging cookie
      if (json.data && json.data.length > 0) {  // good! this response has data
        var pqName = json.data[0].queueName
        queueObj.partitionPattern = pqName.split("/").splice(0, 2).join("/") + "/*";  // replace the last "partition number" part of the name with a * for wildcard searching
        for (let i=0; i<json.data.length; i++) {
          if (json.data[i].partitionClientName) {
            partitionMap.get("" + json.data[i].partitionNumber).client = json.data[i].partitionClientName;
          } else {
            partitionMap.get("" + json.data[i].partitionNumber).client = null;
          }
        }
      }
      if (json.meta && json.meta.paging && json.meta.paging.nextPageUri) {  // more to go, so call myself again
        getQueueDetails(json.meta.paging.nextPageUri);
      } else {  // else no more cookie, so we are done!
        activateSemp();
        drawQueue();  // shows the access type + num parts (and draws the partitions)
        updateLines();  // adds in the unconnected flow lines
      }
    })
    .catch(error4 => { err("Could not fetch individual partitions details via SEMPv2 paged request"); console.error(error4); });
}

let headers = new Headers();  // we'll need these multiple times

function trySempConnectivity(props) {

  // SEMPv1
  /* <partition-count>12</partition-count>
  <partition-oper-count>12</partition-oper-count>
  <partition-rebalance-delay>5</partition-rebalance-delay>
  <partition-rebalance-max-handoff-time>3</partition-rebalance-max-handoff-time>
  <partition-rebalance-status>ready</partition-rebalance-status>
  <partition-scale-status>ready</partition-scale-status> */

  // const sempQueueInfoSuffix = '/SEMP/v2/config/msgVpns/{vpn}/queues/';// + queueObj.name;

  headers.set('Authorization', 'Basic ' + btoa(props.sempUser + ":" + props.sempPw));
  var start = Date.now();
  fetch(props.sempUrl + '/SEMP/v2/config/msgVpns/' + props.vpn + '/queues/' + queueObj.name, {
    method: 'GET',
    credentials: 'same-origin',
    cache: 'no-cache',
    mode: "cors",
    headers: headers
  })
    .then(response => response.json())
    .then(json => {
      console.log(json);
      if (json.meta.responseCode != 200) {
        err("Error trying to connect via SEMPv2");
        console.error(json.meta);
        // still need to update queues and such
        drawQueue();
        updateLines();  // this shouldn't matter b/c we don't have any partiions..!
        return;  // probably authoriztion error
      }
      queueObj.partitionCount = json.data.partitionCount;  // could be 0 for excl or regular non-ex
      queueObj.accessType = json.data.accessType === 'exclusive' ? 'Exclusive' : queueObj.partitionCount == 0 ? 'Non-Exclusive' : 'Partitioned';
      queueObj.msgVpnName = json.data.msgVpnName;
      // console.log(queueObj);
      if (queueObj.accessType == 'Partitioned') {
        log("LOOKS LIEK A PARITIONED QUEUE!!");
        // let's add the right number of uninitialized partitions to our Map
        for (let i = 0; i < queueObj.partitionCount; i++) {
          partitionMap.set("" + i, { index: "" + i, client: null });
        }

        queueObj.partitionRebalanceDelay = json.data.partitionRebalanceDelay;
        queueObj.partitionRebalanceMaxHandoffTime = json.data.partitionRebalanceMaxHandoffTime;
        // console.log(props.sempUrl + sempPartitionsSuffix + queueObj.name);

        getQueueDetails(props.sempUrl + sempV2PartitionsSuffix.replace("{vpn}", props.vpn) + queueObj.name);


/* 


        fetch(props.sempUrl + sempPartitionsSuffix.replace("{vpn}", props.vpn) + queueObj.name, {
          method: 'GET',
          credentials: 'same-origin',
          cache: 'no-cache',
          mode: "cors",
          headers: headers
        })
          .then(response => response.json())
          .then(json => {
            console.log(json);
            // now, this response will either contain the info we want, or we might have to ask a 2nd timd following the paging cookie
            if (json.data && json.data.length > 0) {  // good! this response has data
              // var millis = Date.now() - start;
              // log("Time for two SEMPv2 queue details queries: " + millis + "ms");
              var pqName = json.data[0].queueName
              // console.log(pqName);
              queueObj.partitionPattern = pqName.split("/").splice(0, 2).join("/") + "/*";  // replace the last "partition number" part of the name with a * for wildcard searching
              // console.log(pqName.split("/").splice(0,2).join("/") + "/*");
              // console.log(queueObj.partitionPattern);
              // done, that's enough... time to start the SEMPv1 polling
              activateSemp();
              drawQueue();  // shows the access type + num parts (and draws the partitions)
              updateLines();  // adds in the unconnected flow lines
            } else if (json.meta.paging.nextPageUri) {
              fetch(json.meta.paging.nextPageUri, {
                method: 'GET',
                credentials: 'same-origin',
                cache: 'no-cache',
                mode: "cors",
                headers: headers
              })
                .then(response2 => response2.json())
                .then(json2 => {
                  console.log(json2);
                  // var millis = Date.now() - start;
                  // log("Time for three SEMPv2 queue details queries: " + millis + "ms");
                  var pqName = json2.data[0].queueName;
                  queueObj.partitionPattern = pqName.split("/").splice(0, 2).join("/") + "/*";  // replace the last "partition number" part of the name with a * for wildcard searching
                  // done, that's enough... time to start the SEMPv1 polling
                  activateSemp();
                  drawQueue();  // shows the access type + num parts (and draws the partitions)
                  updateLines();  // adds in the unconnected flow lines
                })
                .catch(error4 => { err("Could not fetch individual partitions details via SEMPv2 paged request"); console.error(error4); });
            } else {
              err("Issue is SEMPv2 trying to get partition names..!")
            }
          })
          .catch(error3 => { err("Could not fetch individual partitions details via SEMPv2"); console.error(error3); });

 */

      } else {     // end of parition queue block, so this not a partitioned queue!!!
        log("This is an Exclusive queue or regular Non-Excl queue");
        var millis = Date.now() - start;
        log("Time for a single SEMPv2 queue details query: " + millis + "ms");
        queueObj.partitionCount = 0;
        partitionMap.set("0", { index: "0", client: null });
        queueObj.partitionPattern = queueObj.name;  // used for the SEMPv1 queries
        activateSemp();
        drawQueue();  // adds the access-type and stuff
      }
    })  // initial SEMPv2 queue fetch block
    .catch(e => {
      err("Could not fetch queue details with SEMPv2");
      console.error(e);
      drawQueue();
    });

}

// this method parses through a SEMPv1 XML response string looking for rates or depths or whatever
function parseSempResponse(sempResponse, orderedArrayOfSempStats) {
  const start = Date.now();
  var sempTags = [];  // must be ordered in the order they appear in the SEMP response, so like ['<cur-ingress-per-sec>', '<cur-egress-per-sec>']
  var stats = {};   //     like { 'cur-ingress-per-sec': [ 1,2,3] }
  for (let stat of orderedArrayOfSempStats) {
    sempTags.push('<' + stat + '>');
    stats[stat] = [];
  }
  var searchCursor = -1;  // position in the massive SEMP response
  var searchIndex = 0;    // which SEMP stat are we on now
  while ((searchCursor = sempResponse.indexOf(sempTags[searchIndex], searchCursor)) >= 0) {
    stats[orderedArrayOfSempStats[searchIndex]].push(sempResponse.substring(searchCursor + sempTags[searchIndex].length, sempResponse.indexOf('<', searchCursor + sempTags[searchIndex].length)));
    searchCursor += sempTags[searchIndex].length;
    searchIndex++;
    while (searchIndex < sempTags.length) {
      searchCursor = sempResponse.indexOf(sempTags[searchIndex], searchCursor);
      stats[orderedArrayOfSempStats[searchIndex]].push(sempResponse.substring(searchCursor + sempTags[searchIndex].length, sempResponse.indexOf('<', searchCursor + sempTags[searchIndex].length)));
      searchCursor += sempTags[searchIndex].length;
      searchIndex++;
    }
    searchIndex = 0;
  }
  const end = Date.now();
  // log("searching the XML took " + (end - start) + "ms.");
  // log('SEMP stats: ' + JSON.stringify(stats));
  return stats;
}

const sempV1Requests = {
  detail: '<rpc><show><queue><name>{queuePattern}</name></queue></show></rpc>',
  rates: '<rpc><show><queue><name>{queuePattern}</name><rates/></queue></show></rpc>'
}

const sempV2PartitionsSuffix = '/SEMP/v2/monitor/msgVpns/{vpn}/queues?where=partitionQueueName==';// + queueObj.name;


function sempV1Poll(postRequest, statsToFindArray) {
  // let sempv1 = 'https://pq.messaging.solace.cloud:943/SEMP';
  let sempv1 = props.sempUrl + '/SEMP';

  // const postRequest = '<rpc><show><queue><name>' + queueObj.partitionPattern + '</name></queue></show></rpc>';
  // let postRequest = '<rpc><show><queue><name>' + queueObj.partitionPattern + '</name><rates/></queue></show></rpc>';  // will work for PQs or non-PQs
  // let postRequest = '<rpc><show><queue><name>' + queueObj.partitionPattern + '</name></queue></show></rpc>';  // will work for PQs or non-PQs

  start = Date.now();
  fetch(sempv1, {
    method: 'POST',
    credentials: 'same-origin',
    cache: 'no-cache',
    mode: "cors",
    body: postRequest.replace('{queuePattern}', queueObj.partitionPattern),
    headers: headers
  })
    .then(response => response.text())
    .then(str => {
      // console.log(str);
      updateStatsFromSemp(parseSempResponse(str, statsToFindArray));
      return;
    })
    .catch(error2 => { err("Exception while trying to fetch SEMPv1 response. Oh well."); console.error(error2); });
}


var sempRatesTimerHandle;
var sempDepthTimerHandle;

function killSemp() {
  if (sempDepthTimerHandle) clearTimeout(sempDepthTimerHandle);
  if (sempRatesTimerHandle) clearTimeout(sempRatesTimerHandle);
  sempConn = false;
  updateConnBox();
}

function activateSemp() {
  log("### ACTIVATING SEMP!");
  if (!sempConn) {  // first time
    sempConn = true;
    updateConnBox();
    qHeader = 90;
    document.querySelector(':root').style.setProperty('--semp-dep-pointer', 'all');  // set the stylesheet to allow clicking on the 'bounce' icon

    // start timers
    sempRatesTimerHandle = setInterval(function ratesSemp() {
      sempV1Poll(sempV1Requests.rates, ['current-ingress-rate-per-second', 'current-egress-rate-per-second']);
    }, 777);
    // stagger it
    setTimeout(function () {
      sempDepthTimerHandle = setInterval(function depthSemp() {
        sempV1Poll(sempV1Requests.detail, ['num-messages-spooled', 'topic-subscription-count']);
      }, 777);
    }, 388);
  }
}


function changeStatSmoothUsingTrans(transition, statId, val) {  // statId like '#varpartdepth3'
  try {
    if (visible) {
      // var t = d3.transition(statId).duration(1000).ease(d3.easeLinear);
      const curValText = d3.select(statId);
      const curVal = +(curValText.text().replaceAll(',', ''));
      curValText.datum(val).transition(transition).textTween(d => {
        const i2 = d3.interpolateRound(curVal, d);
        return function (t) { return d3.format(',d')(i2(t)); };
      });
    } else {
      d3.select(statId).text(d3.format('d')(val));
    }
  } catch (e) {
    err('had an issue in my changeStatSmoothUsingTrans function');
    console.error(e);
  }
}


function updateStatsFromSemp(stats) {
  // log('updateDetph stats called!  ' + JSON.stringify(stats));
  if (stats['num-messages-spooled']) {
    // we know this is a SEMPv1 call for message-spool depths, let's use a single transition for all...
    var t = d3.transition('num-messages-spooled').duration(789).ease(d3.easeLinear);
    const sa = stats['num-messages-spooled'];
    // log('detail stats lengh: ' + sa.length + ',  partCount: ' + queueObj.partitionCount);
    if (queueObj.partitionCount > 0) {
      if (sa.length > queueObj.partitionCount) {  // grew
        for (let i = queueObj.partitionCount; i < sa.length; i++) {
          partitionMap.set("" + i, { index: "" + i, client: null });
        }
        queueObj.partitionCount = partitionMap.size;
        drawQueue();
        updateLines();
      } else if (sa.length < queueObj.partitionCount) {  // shrunk!
        for (let i = partitionMap.size-1; i >= sa.length; i--) {
          partitionMap.delete("" + i);
          queueObj.partitionCount--;
          log('deleting ' + i);
        }
        drawQueue();
        updateLines();
      }
      d3.select('#varqpart').text(queueObj.partitionCount);
      var totalDepth = 0;
      for (let i = 0; i < sa.length; i++) {
        totalDepth += +(sa[i]);  // convert the string to a number
        changeStatSmoothUsingTrans(t, '#varpartdepth' + i, sa[i]);
        if (sa[i] > 0) {  // some number of messages spooled
          d3.select('#varpartdepth' + i).style('font-weight', 'bold');
        } else {
          d3.select('#varpartdepth' + i).style('font-weight', 'normal');
        }
      }
      changeStatSmoothUsingTrans(t, '#varqdepth', totalDepth);
      // let's do something custom for showing off the message depth!  shade the background of each partition div
      for (let i = 0; i < sa.length; i++) {
        if (visible) {
          const curVal = +(d3.select('#varpartdepth' + i).text());
          d3.select('#tabpart' + i).datum(sa[i]).transition(t).styleTween("background", function (d) {
            const i2 = d3.interpolateRound(curVal, d);
            return function (t) {
              const rightColor = 'rgba(255,0,127,' + (0.0004 * (Math.min(1000, Math.max(0, i2(t) - 400)))) + ')';
              return 'linear-gradient(270deg, ' + rightColor + ' 0%, rgba(0,0,0,0.2) ' + i2(t) + 'px, rgba(255,255,255,1) ' + i2(t) + 'px)';
            };
          });
        } else {  // else not visible, so just set it straight, no interpolation
          // const rightColor = 'rgba(255,0,127,' + (0.0004 * (Math.max(500,sa[i]))) + ')';
          const rightColor = 'rgba(255,63,127,' + (0.0004 * (Math.min(1000, Math.max(0, sa[i] - 400)))) + ')';
          d3.select('#tabpart' + i).style('background', 'linear-gradient(270deg, ' + rightColor + ' 0%, rgba(0,0,0,0.2) ' + sa[i] + 'px, rgba(255,255,255,1) ' + sa[i] + 'px)');
        }
      }
    } else {  // must be exclusive or regular non-exclusive
      changeStatSmoothUsingTrans(t, '#varqdepth', sa[0]);
      if (visible) {
        const curVal = +(d3.select('#varqdepth').text());
        d3.select('#tabpart0').datum(sa[0]).transition(t).styleTween("background", function (d) {
          const i2 = d3.interpolateRound(curVal, d);
          return function (t) {
            const rightColor = 'rgba(255,63,127,' + (0.0004 * (Math.min(1000, Math.max(0, i2(t) - 400)))) + ')';
            return 'linear-gradient(270deg, ' + rightColor + ' 0%, rgba(0,0,0,0.2) ' + i2(t) + 'px, rgba(255,255,255,1) ' + i2(t) + 'px)';
          };
        });

      } else {  // else not visible, so just set it straight, no interpolation
        const rightColor = 'rgba(255,63,127,' + (0.0004 * (Math.min(1000, Math.max(0, sa[0] - 400)))) + ')';
        // d3.select('#queueDiv').style('background', 'linear-gradient(270deg, ' + rightColor + ' 0%, rgba(0,0,0,0.2) ' + sa[0] + 'px, rgba(255,255,255,1) ' + sa[0] + 'px)');
        d3.select('#tabpart0').style('background', 'linear-gradient(270deg, ' + rightColor + ' 0%, rgba(0,0,0,0.2) ' + sa[0] + 'px, rgba(255,255,255,1) ' + sa[0] + 'px)');
      }
    }


  } else {  // it better be the rates per second!
    var t = d3.transition('current-rates-per-second').duration(789).ease(d3.easeLinear);
    var sa = stats['current-ingress-rate-per-second'];
    // log('rates stats lengh: ' + sa.length + ',  partCount: ' + queueObj.partitionCount);
    if (queueObj.partitionCount > 0) {  // partitioned queue
      if (sa.length-1 > queueObj.partitionCount) {  // grew  (-1 because the rates includes the total for all queues, so # parts + total)
        for (let i = queueObj.partitionCount; i < sa.length-1; i++) {
          partitionMap.set("" + i, { index: "" + i, client: null });
        }
        drawQueue();
        updateLines();
        queueObj.partitionCount = partitionMap.size;
      } else if (sa.length-1 < queueObj.partitionCount) {  // shrunk!
        for (let i = partitionMap.size-1; i >= sa.length-1; i--) {
          partitionMap.delete("" + i);
          queueObj.partitionCount--;
          log('deleting ' + i);
        }
        drawQueue();
        updateLines();
      }
      d3.select('#varqpart').text(queueObj.partitionCount);
      for (let i = 0; i < sa.length - 1; i++) {
        changeStatSmoothUsingTrans(t, '#varpartingress' + i, sa[i]);
      }
    }
    changeStatSmoothUsingTrans(t, '#varqingress', sa[sa.length - 1]);  // the queue total is included at the end
    sa = stats['current-egress-rate-per-second'];
    let max = +sa[sa.length - 1] + 1;  // the total egress rate!  +1 so zero doesn't give a divide by 0 error..!
    if (queueObj.partitionCount > 0) {
      for (let i = 0; i < sa.length - 1; i++) {
        changeStatSmoothUsingTrans(t, '#varpartegress' + i, sa[i]);
        let lineSize = (0.4 * (sa[i] / max)) + 0.2;
        d3.select('#partline'+i).style('stroke-width', lineSize+'em');
        if (sa[i] == 0 && stats['current-ingress-rate-per-second'][i] > 3) {  // egress is 0, and there it at least some ingress (spooling)
          d3.select('#varpartegress' + i).style('color', 'red').style('font-weight', 'bold');
        } else if (sa[i] > 10 && sa[i] > 1.25 * stats['current-ingress-rate-per-second'][i]) {  // there is some egress, nad it's 1.25 times ingress (draining)
          d3.select('#varpartegress' + i).style('color', 'darkgreen').style('font-weight', 'bold');
        } else {
          d3.select('#varpartegress' + i).style('color', 'black').style('font-weight', 'normal');
        }
      }
    }
    // and now the whole queue
    changeStatSmoothUsingTrans(t, '#varqegress', sa[sa.length - 1]);  // the queue total is included at the end
    if (sa[sa.length - 1] == 0 && stats['current-ingress-rate-per-second'][sa.length - 1] > 3) {  // egress is 0, and there it at least some ingress (spooling)
      d3.select('#varqegress').style('color', 'red').style('font-weight', 'bold');
    } else if (sa[sa.length - 1] > 10 && sa[sa.length - 1] > 1.25 * stats['current-ingress-rate-per-second'][sa.length - 1]) {  // there is some egress, nad it's 1.25 times ingress (draining)
      d3.select('#varqegress').style('color', 'darkgreen').style('font-weight', 'bold');
    } else {
      d3.select('#varqegress').style('color', 'black').style('font-weight', 'normal');
    }
  }
}




// this is the code that runs when it loads!

var params = window.location.search.substring(1);
var props = {};
var showPublishAcl = false;

log("PARAMS: " + params);
params = params.split('&'); // first element of split
params.forEach(p => {
  var blah = p.split('=');
  switch (blah[0]) {
    case 'queue':
      props.queue = blah[1];
      queueObj.name = props.queue;
      queueObj.simpleName = queueObj.name.replaceAll("[^a-zA-Z0-9]", "_");
      document.getElementById("acl").value = queueObj.name + "/>";
      break;
    case 'mqttUrl': props.mqttUrl = blah[1]; break;
    case 'user': props.user = blah[1]; break;
    case 'pw': props.pw = blah[1]; break;
    case 'vpn': props.vpn = blah[1]; break;
    case 'sempUrl': props.sempUrl = blah[1]; break;
    case 'sempUser': props.sempUser = blah[1]; break;
    case 'sempPw': props.sempPw = blah[1]; break;
    case 'nacks': if (blah.length == 1) showPublishAcl = true; else log('nope'); break;
    case '': break;  // handles a trailing & or something...
    default: writeToScreen("<b>ERROR: DIDN'T PARSE THIS URL PARAM: '" + p + "'</b>");
  }
});

console.log(props);
var shouldStop = false;
if (props.sempUrl) {  // they've provided a SEMP URL, make sure the other optional properties are there
  if (!props.vpn) writeToScreen("<b>ERROR: MISSING 'vpn' URL PARAM</b>");
  if (!props.sempUser) writeToScreen("<b>ERROR: MISSING 'sempUser' URL PARAM</b>");
  if (!props.sempPw) writeToScreen("<b>ERROR: MISSING 'sempPw' URL PARAM</b>");
  shouldStop = !(props.sempUser && props.sempPw && props.vpn);
  // (props.sempUser && props.sempPw) ? log("yes semp user") : log("no semp user");
}

if (!params || params == "" || !props.mqttUrl || !props.user || !props.pw || !props.queue || shouldStop) {
  writeToScreen("This JavaScript demo will connect to a Message VPN on a Solace broker and attempt to listen to information/events from the broker, PQPublishers, PQSubscribers, and OrderCheckers.<br/>" +
    "Please ensure you have 'Events over the Message Bus' enabled in the Message VPN settings, and using MQTT format.<br/>This demo works with and without SEMP connectivity, " +
    "but will be able to show queue/partition rates and depths if SEMP access is provided (and also the ability to bounce subscriber connections).<br/>" +
    "This demo also works with Exclusive and (regular) Non-Exclusive queues, and can be used to demonstrate the differences in message routing and behaviour between them.");
  writeToScreen("Add URL params: <b>queue</b>, <b>mqttUrl</b>, <b>user</b>, <b>pw</b>,&nbsp;&nbsp;and <i>optional (all required)</i>: <b>vpn</b>, <b>sempUrl</b>, <b>sempUser</b>, <b>sempPw</b>");
  writeToScreen("'<b>mqttUrl</b>' = MQTT WS or WSS host and port, '<b>sempUrl</b>' (<i>optional</i>) = HTTP or HTTPS management host and port");
  writeToScreen("e.g.: <code>?queue=pq1&mqttUrl=ws://localhost:8000&user=default&pw=blah</code>");
  writeToScreen(" ~ or ~");
  writeToScreen("<code>?queue=pq1&mqttUrl=ws://localhost:8000&user=default&pw=blah&vpn=default&sempUrl=http://localhost:8080&sempUser=admin&sempPw=admin</code>");
  writeToScreen(" ~ or ~");
  writeToScreen("<code>?queue=pq3&mqttUrl=wss://mr123abc.messaging.solace.cloud:8443&user=solace-cloud-client&pw=secret&vpn=pqdemo&sempUrl=https://mr123abc.messaging.solace.cloud:943&sempUser=aaron-demo-admin&sempPw=secret-semp-pw</code>");
} else {  // we should have enough params to successfully connect..!
  // document.getElementById('ulist').style.visibility = 'hidden';
  // document.getElementById('instructions').style.visibility = 'visible';
  // document.getElementById('instructions').hidden = false;
  if (props.sempUrl) {
    headers.set('Authorization', 'Basic ' + btoa(props.sempUser + ":" + props.sempPw));
    trySempConnectivity(props);
  } else {
    log("Not attempting SEMP connectivity");
  }
  log("CONNECTING MQTT to " + props.mqttUrl + "...");
  connectMqtt(props.mqttUrl, props.user, props.pw);
  svgSetup();
}

// window.onload = function () {
// }

var visible = true; //Date.now();
var visibleTs = Date.now();

// this thing is used to track when the demo isn't visible (tab gone out of focus, other window in front, etc.) and is used to stop the animations b/c it causes super lag
document.addEventListener("visibilitychange", function () {
  visible = document.visibilityState == 'visible';// ? Date.now() : 0;
  if (visible) {
    log("Gained focus, reenabling animated transitions");
    visibleTs = Date.now();
  } else {
    log("Lost focus, disabling animated transitions");
  }
  // log("CHANGE IN VISIBILITY!  " + document.visibilityState + " at time " + Math.floor((Math.floor(Date.now() / 1000) % 3600) / 60) + "m" + (((Date.now() / 1000).toFixed(0) % 3600) % 60).toFixed(0) + "s");
}, false);
